package io.floci.sidecar.cedar;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.AuthorizationRequest;
import com.cedarpolicy.model.AuthorizationResponse;
import com.cedarpolicy.model.AuthorizationSuccessResponse;
import com.cedarpolicy.model.Context;
import com.cedarpolicy.model.ValidationRequest;
import com.cedarpolicy.model.ValidationResponse;
import com.cedarpolicy.model.entity.Entities;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.model.exception.AuthException;
import com.cedarpolicy.model.policy.LinkValue;
import com.cedarpolicy.model.policy.Policy;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.policy.TemplateLink;
import com.cedarpolicy.model.schema.Schema;
import com.cedarpolicy.value.CedarList;
import com.cedarpolicy.value.CedarMap;
import com.cedarpolicy.value.DateTime;
import com.cedarpolicy.value.Decimal;
import com.cedarpolicy.value.Duration;
import com.cedarpolicy.value.EntityTypeName;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.IpAddress;
import com.cedarpolicy.value.PrimBool;
import com.cedarpolicy.value.PrimLong;
import com.cedarpolicy.value.PrimString;
import com.cedarpolicy.value.Value;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.sidecar.core.Json;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stateless HTTP boundary around Cedar Java 4.x: entity-type and schema validation, policy
 * parsing and validation, and authorization. Every request carries the policies, templates,
 * entities and context it needs; nothing is kept between calls.
 */
@Path("/v1")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class CedarResource {

    private static final Logger LOG = Logger.getLogger(CedarResource.class);

    private final BasicAuthorizationEngine engine = new BasicAuthorizationEngine();

    /**
     * Loads the Rust runtime at startup rather than on the first request, so a missing or
     * unloadable library fails the process instead of answering {@code /health} with ok.
     */
    void onStart(@Observes StartupEvent event) {
        EntityTypeName.parse("Sidecar::Probe").orElseThrow(() ->
                new IllegalStateException("Cedar runtime probe failed"));
        LOG.info("Cedar runtime loaded");
    }

    @POST
    @Path("/entity-type/validate")
    public JsonNode validateEntityType(String raw) throws Exception {
        return validateEntityType(Json.body(raw));
    }

    @POST
    @Path("/schema/validate")
    public JsonNode validateSchema(String raw) throws Exception {
        return validateSchema(Json.body(raw));
    }

    @POST
    @Path("/policy/parse")
    public JsonNode parsePolicy(String raw) throws Exception {
        return parsePolicy(Json.body(raw));
    }

    @POST
    @Path("/policy/validate")
    public JsonNode validatePolicy(String raw) throws Exception {
        return validatePolicy(Json.body(raw));
    }

    @POST
    @Path("/authorize")
    public JsonNode authorize(String raw) throws Exception {
        return authorize(Json.body(raw));
    }

    private static JsonNode validateEntityType(JsonNode body) throws Exception {
        String value = Json.requiredText(body, "entityType");
        if (EntityTypeName.parse(value).isEmpty()) {
            throw new IllegalArgumentException("Invalid Cedar entity type: " + value);
        }
        return Json.object().put("valid", true);
    }

    private static JsonNode validateSchema(JsonNode body) throws Exception {
        String schema = Json.requiredText(body, "schema");
        Schema.parse(Schema.JsonOrCedar.Json, schema);
        return Json.object().put("valid", true);
    }

    private static JsonNode parsePolicy(JsonNode body) throws Exception {
        String statement = Json.requiredText(body, "statement");
        boolean template = body.path("template").asBoolean(false);
        Policy policy = template
                ? Policy.parsePolicyTemplate(statement)
                : Policy.parseStaticPolicy(statement);
        JsonNode ast;
        if (template) {
            PolicySet set = new PolicySet(Set.of(), Set.of(policy));
            JsonNode templates = Json.mapper().readTree(set.toJson()).path("templates");
            if (!templates.isObject() || templates.isEmpty()) {
                throw new IllegalArgumentException("Cedar template AST is empty");
            }
            ast = templates.elements().next();
        } else {
            ast = Json.mapper().readTree(policy.toJson());
        }
        ObjectNode response = Json.object();
        response.put("effect", policy.effect().name().equalsIgnoreCase("PERMIT") ? "Permit" : "Forbid");
        response.set("ast", ast);
        return response;
    }

    private JsonNode validatePolicy(JsonNode body) throws Exception {
        Schema schema = Schema.parse(Schema.JsonOrCedar.Json, Json.requiredText(body, "schema"));
        String statement = Json.requiredText(body, "statement");
        boolean template = body.path("template").asBoolean(false);
        Policy policy = template
                ? Policy.parsePolicyTemplate(statement)
                : Policy.parseStaticPolicy(statement);
        PolicySet set = template ? new PolicySet(Set.of(), Set.of(policy)) : new PolicySet(Set.of(policy));
        ValidationResponse result = engine.validate(new ValidationRequest(schema, set));
        if (!result.validationPassed()) {
            throw new IllegalArgumentException("The Cedar policy failed STRICT schema validation: " + result);
        }
        return Json.object().put("valid", true);
    }

    private JsonNode authorize(JsonNode body) throws Exception {
        JsonNode request = Json.requiredObject(body, "request");
        EntityUID principal = euid(request.get("principal"), "principal");
        EntityUID action = actionEuid(request.get("action"));
        EntityUID resource = euid(request.get("resource"), "resource");
        Entities entities = entities(request.get("entities"));
        Context context = context(request.get("context"));
        PolicySet policies = policySet(body.path("policies"), body.path("templates"));

        AuthorizationResponse authorizationResponse = engine.isAuthorized(new AuthorizationRequest(principal, action, resource, context), policies, entities);
        AuthorizationSuccessResponse response = authorizationResponse.success.orElseThrow(() ->
                new IllegalArgumentException("Cedar authorization failed."));
        ObjectNode out = Json.object();
        out.put("decision", response.isAllowed() ? "ALLOW" : "DENY");
        ArrayNode reasons = out.putArray("determiningPolicyIds");
        response.getReason().stream().sorted().forEach(reasons::add);
        ArrayNode errors = out.putArray("errors");
        response.getErrors().stream().map(Object::toString).forEach(errors::add);
        return out;
    }

    private static PolicySet policySet(JsonNode storedPolicies, JsonNode templates) {
        Set<Policy> staticPolicies = new LinkedHashSet<>();
        Set<Policy> cedarTemplates = new LinkedHashSet<>();
        List<TemplateLink> links = new ArrayList<>();
        if (templates.isObject()) {
            templates.fields().forEachRemaining(entry -> {
                JsonNode template = entry.getValue();
                cedarTemplates.add(new Policy(Json.requiredText(template, "statement"), entry.getKey()));
            });
        }
        if (storedPolicies.isArray()) {
            for (JsonNode policy : storedPolicies) {
                String policyType = Json.requiredText(policy, "policyType");
                String policyId = Json.requiredText(policy, "policyId");
                if ("STATIC".equals(policyType)) {
                    staticPolicies.add(new Policy(Json.requiredText(policy, "statement"), policyId));
                    continue;
                }
                List<LinkValue> values = new ArrayList<>();
                if (!policy.path("principal").isMissingNode() && !policy.path("principal").isNull()) {
                    values.add(new LinkValue("?principal", euid(policy.get("principal"), "principal")));
                }
                if (!policy.path("resource").isMissingNode() && !policy.path("resource").isNull()) {
                    values.add(new LinkValue("?resource", euid(policy.get("resource"), "resource")));
                }
                links.add(new TemplateLink(Json.requiredText(policy, "policyTemplateId"), policyId, values));
            }
        }
        return new PolicySet(staticPolicies, cedarTemplates, links);
    }

    private static Entities entities(JsonNode definition) throws Exception {
        if (definition == null || definition.isNull()) {
            return new Entities();
        }
        if (!definition.isObject() || definition.size() != 1) {
            throw new IllegalArgumentException("entities must contain exactly one union member.");
        }
        JsonNode raw;
        if (definition.has("cedarJson")) {
            if (!definition.get("cedarJson").isTextual()) {
                throw new IllegalArgumentException("entities.cedarJson must be a string.");
            }
            raw = Json.mapper().readTree(definition.get("cedarJson").asText());
        } else if (definition.has("entityList")) {
            if (!definition.get("entityList").isArray()) {
                throw new IllegalArgumentException("entities.entityList must be an array.");
            }
            raw = toCedarEntities((ArrayNode) definition.get("entityList"));
        } else {
            throw new IllegalArgumentException("entities must contain cedarJson or entityList.");
        }
        return entitiesFromCedarJson(raw);
    }

    private static Entities entitiesFromCedarJson(JsonNode raw) {
        if (!raw.isArray()) {
            throw new IllegalArgumentException("entities.cedarJson must encode an array.");
        }
        Set<Entity> result = new LinkedHashSet<>();
        for (JsonNode node : raw) {
            if (!node.isObject()) {
                throw new IllegalArgumentException("Each Cedar entity must be an object.");
            }
            result.add(new Entity(euid(node.get("uid"), "uid"), cedarValueMap(node.get("attrs"), "attrs"),
                    cedarParents(node.get("parents")), cedarValueMap(node.get("tags"), "tags")));
        }
        return new Entities(result);
    }

    private static ArrayNode toCedarEntities(ArrayNode input) {
        ArrayNode output = Json.mapper().createArrayNode();
        Map<String, ObjectNode> lastByUid = new LinkedHashMap<>();
        for (JsonNode entity : input) {
            JsonNode identifier = Json.requiredObject(entity, "identifier");
            ObjectNode cedar = Json.object();
            cedar.set("uid", cedarEntity(identifier));
            ObjectNode attrs = cedar.putObject("attrs");
            JsonNode attributes = entity.get("attributes");
            if (attributes != null && !attributes.isNull()) {
                attributes.fields().forEachRemaining(entry -> attrs.set(entry.getKey(), cedarAttribute(entry.getValue())));
            }
            ArrayNode parents = cedar.putArray("parents");
            JsonNode parentInput = entity.get("parents");
            if (parentInput != null && !parentInput.isNull()) {
                parentInput.forEach(parent -> parents.add(cedarEntity(parent)));
            }
            ObjectNode tags = cedar.putObject("tags");
            JsonNode tagInput = entity.get("tags");
            if (tagInput != null && !tagInput.isNull()) {
                tagInput.fields().forEachRemaining(entry -> tags.set(entry.getKey(), cedarAttribute(entry.getValue())));
            }
            lastByUid.put(Json.requiredText(identifier, "entityType") + "\u0000" + Json.requiredText(identifier, "entityId"), cedar);
        }
        lastByUid.values().forEach(output::add);
        return output;
    }

    private static Map<String, Value> cedarValueMap(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("Entity " + field + " must be an object.");
        }
        Map<String, Value> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> values.put(entry.getKey(), valueFromCedarJson(entry.getValue())));
        return values;
    }

    private static Set<EntityUID> cedarParents(JsonNode node) {
        if (node == null || node.isNull()) {
            return Set.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("Entity parents must be an array.");
        }
        Set<EntityUID> parents = new LinkedHashSet<>();
        node.forEach(parent -> parents.add(euid(parent, "parent")));
        return parents;
    }

    private static JsonNode cedarAttribute(JsonNode union) {
        if (union == null || !union.isObject() || union.size() != 1) {
            throw new IllegalArgumentException("Attribute values must contain exactly one union member.");
        }
        Map.Entry<String, JsonNode> member = union.fields().next();
        return switch (member.getKey()) {
            case "boolean" -> Json.mapper().getNodeFactory().booleanNode(member.getValue().asBoolean());
            case "long" -> Json.mapper().getNodeFactory().numberNode(member.getValue().asLong());
            case "string" -> Json.mapper().getNodeFactory().textNode(member.getValue().asText());
            case "entityIdentifier" -> explicitEntity(member.getValue());
            case "ipaddr" -> extension("ip", member.getValue().asText());
            case "decimal" -> extension("decimal", member.getValue().asText());
            case "datetime" -> extension("datetime", member.getValue().asText());
            case "duration" -> extension("duration", member.getValue().asText());
            case "set" -> {
                ArrayNode set = Json.mapper().createArrayNode();
                member.getValue().forEach(value -> set.add(cedarAttribute(value)));
                yield set;
            }
            case "record" -> {
                ObjectNode record = Json.object();
                member.getValue().fields().forEachRemaining(entry -> record.set(entry.getKey(), cedarAttribute(entry.getValue())));
                yield record;
            }
            default -> throw new IllegalArgumentException("Unsupported attribute union member: " + member.getKey());
        };
    }

    private static Context context(JsonNode definition) throws Exception {
        if (definition == null || definition.isNull()) {
            return new Context();
        }
        if (!definition.isObject() || definition.size() != 1) {
            throw new IllegalArgumentException("context must contain exactly one union member.");
        }
        if (definition.has("cedarJson")) {
            JsonNode raw = Json.mapper().readTree(Json.requiredText(definition, "cedarJson"));
            if (!raw.isObject()) {
                throw new IllegalArgumentException("context.cedarJson must encode an object.");
            }
            return new Context(valueMapFromCedarJson(raw));
        }
        if (definition.has("contextMap")) {
            JsonNode raw = definition.get("contextMap");
            Map<String, Value> values = new LinkedHashMap<>();
            raw.fields().forEachRemaining(entry -> values.put(entry.getKey(), valueFromUnion(entry.getValue())));
            return new Context(values);
        }
        throw new IllegalArgumentException("context must contain cedarJson or contextMap.");
    }

    private static Map<String, Value> valueMapFromCedarJson(JsonNode object) {
        Map<String, Value> result = new LinkedHashMap<>();
        object.fields().forEachRemaining(entry -> result.put(entry.getKey(), valueFromCedarJson(entry.getValue())));
        return result;
    }

    private static Value valueFromCedarJson(JsonNode value) {
        if (value.isBoolean()) {
            return new PrimBool(value.asBoolean());
        }
        if (value.isIntegralNumber()) {
            return new PrimLong(value.asLong());
        }
        if (value.isTextual()) {
            return new PrimString(value.asText());
        }
        if (value.isArray()) {
            List<Value> values = new ArrayList<>();
            value.forEach(item -> values.add(valueFromCedarJson(item)));
            return new CedarList(values);
        }
        if (value.isObject()) {
            if (value.has("__entity")) {
                return euid(value.get("__entity"), "__entity");
            }
            if (value.has("__extn")) {
                JsonNode ext = value.get("__extn");
                return extensionValue(ext.path("fn").asText(), ext.path("arg").asText());
            }
            return new CedarMap(valueMapFromCedarJson(value));
        }
        throw new IllegalArgumentException("Unsupported Cedar JSON value.");
    }

    private static Value valueFromUnion(JsonNode union) {
        if (union == null || !union.isObject() || union.size() != 1) {
            throw new IllegalArgumentException("Context attribute values must contain exactly one union member.");
        }
        Map.Entry<String, JsonNode> member = union.fields().next();
        return switch (member.getKey()) {
            case "boolean" -> new PrimBool(member.getValue().asBoolean());
            case "long" -> new PrimLong(member.getValue().asLong());
            case "string" -> new PrimString(member.getValue().asText());
            case "entityIdentifier" -> euid(member.getValue(), "entityIdentifier");
            case "ipaddr" -> new IpAddress(member.getValue().asText());
            case "decimal" -> new Decimal(member.getValue().asText());
            case "datetime" -> new DateTime(member.getValue().asText());
            case "duration" -> new Duration(member.getValue().asText());
            case "set" -> {
                List<Value> list = new ArrayList<>();
                member.getValue().forEach(item -> list.add(valueFromUnion(item)));
                yield new CedarList(list);
            }
            case "record" -> {
                Map<String, Value> map = new LinkedHashMap<>();
                member.getValue().fields().forEachRemaining(entry -> map.put(entry.getKey(), valueFromUnion(entry.getValue())));
                yield new CedarMap(map);
            }
            default -> throw new IllegalArgumentException("Unsupported attribute union member: " + member.getKey());
        };
    }

    private static Value extensionValue(String function, String arg) {
        return switch (function) {
            case "ip" -> new IpAddress(arg);
            case "decimal" -> new Decimal(arg);
            case "datetime" -> new DateTime(arg);
            case "duration" -> new Duration(arg);
            default -> throw new IllegalArgumentException("Unsupported Cedar extension: " + function);
        };
    }

    private static EntityUID actionEuid(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("action is required.");
        }
        return euid(Json.requiredText(node, "actionType"), Json.requiredText(node, "actionId"));
    }

    private static EntityUID euid(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        String type = node.has("entityType") ? Json.requiredText(node, "entityType") : Json.requiredText(node, "type");
        String id = node.has("entityId") ? Json.requiredText(node, "entityId") : Json.requiredText(node, "id");
        return euid(type, id);
    }

    private static EntityUID euid(String type, String id) {
        EntityTypeName entityType = EntityTypeName.parse(type)
                .orElseThrow(() -> new IllegalArgumentException("Invalid Cedar entity type: " + type));
        return entityType.of(id);
    }

    private static ObjectNode cedarEntity(JsonNode identifier) {
        ObjectNode node = Json.object();
        node.put("type", identifier.has("entityType") ? Json.requiredText(identifier, "entityType") : Json.requiredText(identifier, "type"));
        node.put("id", identifier.has("entityId") ? Json.requiredText(identifier, "entityId") : Json.requiredText(identifier, "id"));
        return node;
    }

    private static ObjectNode explicitEntity(JsonNode identifier) {
        ObjectNode out = Json.object();
        out.set("__entity", cedarEntity(identifier));
        return out;
    }

    private static ObjectNode extension(String fn, String arg) {
        ObjectNode root = Json.object();
        ObjectNode ext = root.putObject("__extn");
        ext.put("fn", fn);
        ext.put("arg", arg);
        return root;
    }
}
