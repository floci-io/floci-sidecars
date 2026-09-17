package io.floci.sidecar.graphql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.execution.DataFetcherResult;
import graphql.language.Document;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.OperationDefinition;
import graphql.language.SourceLocation;
import graphql.language.UnionTypeDefinition;
import graphql.parser.InvalidSyntaxException;
import graphql.parser.Parser;
import graphql.schema.Coercing;
import graphql.schema.DataFetcher;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLUnionType;
import graphql.schema.TypeResolver;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.errors.SchemaProblem;
import io.floci.sidecar.core.Json;
import io.floci.sidecar.graphql.scalars.ScalarKinds;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateless HTTP boundary around graphql-java: SDL parsing/schema generation, query planning and
 * execution. No emulator or cloud vocabulary crosses this boundary: scalars are generic named
 * kinds (see {@link ScalarKinds}) a caller maps its own scalar names onto, and field-level
 * authorization is entirely the caller's concern. {@code /v1/plan} lists every {@code (typeName,
 * fieldName)} coordinate a query would visit, with the directives on each and zero interpretation
 * of what they mean, so a caller can precompute an allow/deny decision before calling {@code
 * /v1/execute} and pass the result back as an opaque {@code denyFields} list this sidecar nulls
 * out, never knowing why.
 */
@Path("/v1")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class GraphqlResource {

    /** Scalars graphql-java's RuntimeWiring registers by default; anything else needs a wiring entry. */
    private static final List<String> BUILTIN_SCALAR_NAMES = List.of("Int", "Float", "String", "Boolean", "ID");

    private final SchemaCache schemaCache;

    @Inject
    public GraphqlResource(SchemaCache schemaCache) {
        this.schemaCache = schemaCache;
    }

    @POST
    @Path("/schema/validate")
    public JsonNode validateSchema(String raw) throws Exception {
        return validateSchema(Json.body(raw));
    }

    @POST
    @Path("/plan")
    public JsonNode plan(String raw) throws Exception {
        return plan(Json.body(raw));
    }

    @POST
    @Path("/execute")
    public JsonNode execute(String raw) throws Exception {
        return execute(Json.body(raw));
    }

    private JsonNode validateSchema(JsonNode body) {
        String sdl = Json.requiredText(body, "sdl");
        buildSchema(sdl, scalarsOf(body));
        return Json.object().put("valid", true);
    }

    /**
     * Only a query that does not parse gets an empty plan (no fields, no operation type) instead
     * of an error: {@code /v1/execute} runs the same query through graphql-java's own engine,
     * which already produces the correct spec-compliant response for it. An ambiguous document
     * (more than one operation, no {@code operationName}) is different: it is a usage error of
     * this endpoint's own contract, so it is a hard 400, uncaught. Once the query parses and
     * names a real operation, it will go on to visit real fields unless something stops it, so
     * from there a {@link QueryPlanner} failure is deliberately left to propagate rather than
     * being swallowed into an empty plan: a planner bug must refuse the request, not silently
     * authorize everything by reporting zero fields to redact.
     */
    private JsonNode plan(JsonNode body) {
        String sdl = Json.requiredText(body, "sdl");
        Map<String, String> scalars = scalarsOf(body);
        String query = Json.requiredText(body, "query");
        String operationName = body.path("operationName").asText(null);

        GraphQLSchema schema = buildSchema(sdl, scalars);
        ObjectNode result = Json.object();
        ArrayNode array = result.putArray("fields");

        Document document;
        try {
            document = new Parser().parseDocument(query);
        } catch (RuntimeException e) {
            result.putNull("operationType");
            return result;
        }
        requireUnambiguousOperation(document, operationName);

        OperationDefinition operation;
        try {
            operation = selectOperation(document, operationName);
        } catch (RuntimeException e) {
            result.putNull("operationType");
            return result;
        }

        result.put("operationType", operation.getOperation().name());
        List<QueryPlanner.VisitedField> fields = QueryPlanner.plan(schema, document, operation);
        for (QueryPlanner.VisitedField field : fields) {
            ObjectNode node = array.addObject();
            node.put("typeName", field.typeName());
            node.put("fieldName", field.fieldName());
            writeDirectives(node.putArray("directives"), field.directives());
            writeDirectives(node.putArray("typeDirectives"), field.typeDirectives());
        }
        return result;
    }

    private static void writeDirectives(ArrayNode array, List<QueryPlanner.AppliedDirective> directives) {
        for (QueryPlanner.AppliedDirective directive : directives) {
            ObjectNode directiveNode = array.addObject();
            directiveNode.put("name", directive.name());
            directiveNode.set("args", Json.mapper().valueToTree(directive.args()));
        }
    }

    private JsonNode execute(JsonNode body) {
        String sdl = Json.requiredText(body, "sdl");
        Map<String, String> scalars = scalarsOf(body);
        String query = Json.requiredText(body, "query");
        Map<String, Object> variables = body.has("variables") && !body.get("variables").isNull()
                ? Json.mapper().convertValue(body.get("variables"), new TypeReference<Map<String, Object>>() { })
                : Map.of();
        String operationName = body.path("operationName").asText(null);
        List<DenyEntry> denyFields = parseDenyFields(body.get("denyFields"));

        try {
            Document document = new Parser().parseDocument(query);
            requireUnambiguousOperation(document, operationName);
        } catch (InvalidSyntaxException e) {
            // Not this endpoint's problem to report: graphql-java's own engine parses the query
            // again below and produces the correct spec-compliant response for a syntax error.
        }

        GraphQLSchema schema = buildSchema(sdl, scalars);
        if (!denyFields.isEmpty()) {
            schema = applyDenyFields(schema, denyFields);
        }
        GraphQL graphQL = GraphQL.newGraphQL(schema).build();
        ExecutionInput.Builder input = ExecutionInput.newExecutionInput()
                .query(query)
                .variables(variables);
        if (operationName != null && !operationName.isBlank()) {
            input.operationName(operationName);
        }
        ExecutionResult result = graphQL.execute(input.build());
        return Json.mapper().valueToTree(result.toSpecification());
    }

    /** A usage error of this endpoint's own contract; uncaught wherever it is called from. */
    private static void requireUnambiguousOperation(Document document, String operationName) {
        List<OperationDefinition> operations = document.getDefinitionsOfType(OperationDefinition.class);
        if (operations.size() > 1 && (operationName == null || operationName.isBlank())) {
            throw new IllegalArgumentException(
                    "operationName is required when the document defines more than one operation");
        }
    }

    private static OperationDefinition selectOperation(Document document, String operationName) {
        List<OperationDefinition> operations = document.getDefinitionsOfType(OperationDefinition.class);
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("Query contains no operations.");
        }
        if (operationName == null || operationName.isBlank()) {
            return operations.get(0);
        }
        return operations.stream()
                .filter(op -> operationName.equals(op.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown operation: " + operationName));
    }

    private static Map<String, String> scalarsOf(JsonNode body) {
        JsonNode node = body.get("scalars");
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> scalars = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> scalars.put(entry.getKey(), entry.getValue().asText()));
        return scalars;
    }

    /** {@code errorType}/{@code message} are opaque to this sidecar: the caller decides what they say. */
    private record DenyEntry(String typeName, String fieldName, String errorType, String message) {
    }

    private static List<DenyEntry> parseDenyFields(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<DenyEntry> entries = new ArrayList<>();
        for (JsonNode entry : node) {
            entries.add(new DenyEntry(
                    Json.requiredText(entry, "typeName"),
                    Json.requiredText(entry, "fieldName"),
                    Json.requiredText(entry, "errorType"),
                    Json.requiredText(entry, "message")));
        }
        return entries;
    }

    /** Wraps only the denied coordinates' DataFetchers; every other field executes normally. */
    private static GraphQLSchema applyDenyFields(GraphQLSchema schema, List<DenyEntry> denyFields) {
        // FieldCoordinates is just a (typeName, fieldName) pair: denyFields is always
        // schema-derived (it comes from this same schema's own plan output), so there is no need
        // to scan the schema's types/fields to find a coordinate that could not exist here.
        GraphQLCodeRegistry.Builder code = GraphQLCodeRegistry.newCodeRegistry(schema.getCodeRegistry());
        for (DenyEntry entry : denyFields) {
            code.dataFetcher(FieldCoordinates.coordinates(entry.typeName(), entry.fieldName()), deniedDataFetcher(entry));
        }
        GraphQLCodeRegistry registry = code.build();
        return schema.transform(builder -> builder.codeRegistry(registry));
    }

    private static DataFetcher<Object> deniedDataFetcher(DenyEntry deny) {
        return env -> DataFetcherResult.newResult()
                .data(null)
                .error(GraphqlErrorBuilder.newError()
                        .message(deny.message())
                        // Same "classification" key graphql-java's own toSpecification() uses for
                        // its built-in errors, so a caller's error formatter has one code path.
                        .extensions(Map.of("classification", deny.errorType()))
                        .path(env.getExecutionStepInfo().getPath().toList())
                        .build())
                .build();
    }

    private GraphQLSchema buildSchema(String sdl, Map<String, String> scalars) {
        return schemaCache.get(sdl, scalars, () -> compileSchema(sdl, scalars));
    }

    private static GraphQLSchema compileSchema(String sdl, Map<String, String> scalars) {
        TypeDefinitionRegistry registry;
        try {
            // SchemaParser.parse() documents itself as throwing SchemaProblem, not
            // InvalidSyntaxException directly; it catches the parser's own InvalidSyntaxException
            // internally and wraps it into a SchemaProblem carrying an InvalidSyntaxError.
            registry = new SchemaParser().parse(sdl);
        } catch (SchemaProblem e) {
            throw schemaValidationException(issuesFrom(e, "PARSER_ERROR"));
        }

        RuntimeWiring.Builder wiring = RuntimeWiring.newRuntimeWiring();
        registry.scalars().keySet().stream()
                .filter(name -> !BUILTIN_SCALAR_NAMES.contains(name))
                .forEach(name -> wiring.scalar(resolveScalar(name, scalars)));
        registry.getTypes(InterfaceTypeDefinition.class)
                .forEach(type -> wiring.type(type.getName(), builder -> builder.typeResolver(typeResolverFor(type.getName()))));
        registry.getTypes(UnionTypeDefinition.class)
                .forEach(type -> wiring.type(type.getName(), builder -> builder.typeResolver(typeResolverFor(type.getName()))));

        try {
            return new SchemaGenerator().makeExecutableSchema(registry, wiring.build());
        } catch (SchemaProblem e) {
            throw schemaValidationException(issuesFrom(e, "VALIDATION_ERROR"));
        } catch (RuntimeException e) {
            // Covers any other graphql-java unchecked failure this SDL could trigger.
            throw schemaValidationException(List.of(new SchemaValidationException.Issue("VALIDATION_ERROR", safeMessage(e), 0, 0)));
        }
    }

    private static GraphQLScalarType resolveScalar(String name, Map<String, String> scalars) {
        String kind = scalars == null ? null : scalars.get(name);
        if (kind != null) {
            return ScalarKinds.scalarFor(name, kind).orElseGet(() -> passThroughScalar(name));
        }
        return passThroughScalar(name);
    }

    /** An unmapped custom scalar still lets schema generation succeed. */
    private static GraphQLScalarType passThroughScalar(String name) {
        return GraphQLScalarType.newScalar()
                .name(name)
                .coercing(new Coercing<Object, Object>() {
                    @Override
                    public Object serialize(Object input) {
                        return input;
                    }

                    @Override
                    public Object parseValue(Object input) {
                        return input;
                    }

                    @Override
                    public Object parseLiteral(Object input) {
                        return input;
                    }
                })
                .build();
    }

    /**
     * Reads {@code __typename} off the resolved value when a resolver callback supplied one;
     * otherwise falls back to the first candidate concrete type, the placeholder behavior for a
     * value that always resolves to null. The same implementation serves both cases, so the
     * compiled schema (and {@link SchemaCache} key) never needs to fork on whether a resolver
     * callback is in play.
     */
    private static TypeResolver typeResolverFor(String typeName) {
        return env -> {
            GraphQLSchema schema = env.getSchema();
            String hint = typeNameHint(env.getObject());
            if (hint != null && schema.getType(hint) instanceof GraphQLObjectType hinted) {
                return hinted;
            }
            List<GraphQLObjectType> candidates = candidatesOf(schema.getType(typeName), schema);
            return candidates.isEmpty() ? null : candidates.get(0);
        };
    }

    private static String typeNameHint(Object source) {
        if (source instanceof Map<?, ?> map && map.get("__typename") instanceof String typename) {
            return typename;
        }
        return null;
    }

    private static List<GraphQLObjectType> candidatesOf(GraphQLType type, GraphQLSchema schema) {
        if (type instanceof GraphQLInterfaceType interfaceType) {
            return schema.getImplementations(interfaceType);
        }
        if (type instanceof GraphQLUnionType unionType) {
            return unionType.getTypes().stream()
                    .filter(GraphQLObjectType.class::isInstance)
                    .map(GraphQLObjectType.class::cast)
                    .toList();
        }
        return List.of();
    }

    private static List<SchemaValidationException.Issue> issuesFrom(SchemaProblem problem, String category) {
        List<SchemaValidationException.Issue> issues = new ArrayList<>();
        for (GraphQLError error : problem.getErrors()) {
            List<SourceLocation> locations = error.getLocations();
            SourceLocation first = locations == null || locations.isEmpty() ? null : locations.get(0);
            issues.add(new SchemaValidationException.Issue(category, error.getMessage(), lineOf(first), columnOf(first)));
        }
        return issues;
    }

    private static int lineOf(SourceLocation location) {
        return location == null ? 0 : location.getLine();
    }

    private static int columnOf(SourceLocation location) {
        return location == null ? 0 : location.getColumn();
    }

    private static SchemaValidationException schemaValidationException(List<SchemaValidationException.Issue> issues) {
        String message = issues.isEmpty() ? "Invalid GraphQL schema."
                : "Invalid GraphQL schema: " + issues.get(0).message();
        return new SchemaValidationException(message, issues);
    }

    private static String safeMessage(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
