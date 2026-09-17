package io.floci.sidecar.graphql.scalars;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.language.BooleanValue;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Generic named coercion kinds a caller's custom scalar can be mapped onto, so this sidecar never
 * carries emulator or cloud vocabulary in a scalar name or an error message. A caller declares its
 * own scalar names in the SDL and maps each one onto a kind here; the error messages this produces
 * name the caller's declared scalar, not the kind.
 */
public final class ScalarKinds {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+[1-9]\\d{1,14}$");
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$");
    private static final Pattern IPV6_PATTERN = Pattern.compile("^[0-9a-fA-F:]+$");

    private static final Map<String, Function<String, GraphQLScalarType>> FACTORIES = Map.ofEntries(
            Map.entry("json-string", ScalarKinds::jsonString),
            Map.entry("date-time", ScalarKinds::dateTime),
            Map.entry("date", ScalarKinds::date),
            Map.entry("time", ScalarKinds::time),
            Map.entry("epoch-seconds", ScalarKinds::epochSeconds),
            Map.entry("email", ScalarKinds::email),
            Map.entry("url", ScalarKinds::url),
            Map.entry("phone", ScalarKinds::phone),
            Map.entry("ip-address", ScalarKinds::ipAddress),
            Map.entry("boolean", ScalarKinds::booleanKind),
            Map.entry("long", ScalarKinds::longKind),
            Map.entry("integer", ScalarKinds::integer),
            Map.entry("short", ScalarKinds::shortKind),
            Map.entry("float", ScalarKinds::floatKind),
            Map.entry("big-decimal", ScalarKinds::bigDecimal),
            Map.entry("big-integer", ScalarKinds::bigInteger),
            Map.entry("base64", ScalarKinds::base64));

    private ScalarKinds() {
    }

    /** The kind names a caller's {@code scalars} mapping may use. */
    public static Set<String> kinds() {
        return FACTORIES.keySet();
    }

    /** A {@link GraphQLScalarType} named {@code name} with {@code kind}'s coercion, if the kind is known. */
    public static Optional<GraphQLScalarType> scalarFor(String name, String kind) {
        Function<String, GraphQLScalarType> factory = FACTORIES.get(kind);
        return factory == null ? Optional.empty() : Optional.of(factory.apply(name));
    }

    private static GraphQLScalarType jsonString(String name) {
        return GraphQLScalarType.newScalar()
                .name(name)
                .description("A JSON string")
                .coercing(new Coercing<String, String>() {
                    @Override
                    public String serialize(Object dataFetcherResult) {
                        if (dataFetcherResult == null) {
                            return null;
                        }
                        if (dataFetcherResult instanceof String s) {
                            return s;
                        }
                        try {
                            return MAPPER.writeValueAsString(dataFetcherResult);
                        } catch (JsonProcessingException e) {
                            throw new CoercingSerializeException("Cannot serialize to JSON: " + e.getMessage());
                        }
                    }

                    @Override
                    public String parseValue(Object input) {
                        String str = input.toString();
                        try {
                            MAPPER.readTree(str);
                        } catch (Exception e) {
                            throw new CoercingParseValueException("Invalid " + name + ": " + str);
                        }
                        return str;
                    }

                    @Override
                    public String parseLiteral(Object input) {
                        if (!(input instanceof StringValue sv)) {
                            return null;
                        }
                        return asLiteral(() -> parseValue(sv.getValue()));
                    }
                })
                .build();
    }

    private static GraphQLScalarType dateTime(String name) {
        return GraphQLScalarType.newScalar()
                .name(name)
                .description("An ISO-8601 datetime string")
                .coercing(new Coercing<String, String>() {
                    @Override
                    public String serialize(Object dataFetcherResult) {
                        return dataFetcherResult != null ? dataFetcherResult.toString() : null;
                    }

                    @Override
                    public String parseValue(Object input) {
                        String str = input.toString();
                        try {
                            Instant.parse(str);
                        } catch (DateTimeParseException e) {
                            throw new CoercingParseValueException("Invalid " + name + ": " + str);
                        }
                        return str;
                    }

                    @Override
                    public String parseLiteral(Object input) {
                        if (!(input instanceof StringValue sv)) {
                            return null;
                        }
                        return asLiteral(() -> parseValue(sv.getValue()));
                    }
                })
                .build();
    }

    private static GraphQLScalarType date(String name) {
        return GraphQLScalarType.newScalar()
                .name(name)
                .description("An ISO-8601 date string (yyyy-MM-dd)")
                .coercing(new Coercing<String, String>() {
                    @Override
                    public String serialize(Object dataFetcherResult) {
                        return dataFetcherResult != null ? dataFetcherResult.toString() : null;
                    }

                    @Override
                    public String parseValue(Object input) {
                        String str = input.toString();
                        try {
                            LocalDate.parse(str, DateTimeFormatter.ISO_LOCAL_DATE);
                        } catch (DateTimeParseException e) {
                            throw new CoercingParseValueException("Invalid " + name + ": " + str);
                        }
                        return str;
                    }

                    @Override
                    public String parseLiteral(Object input) {
                        if (!(input instanceof StringValue sv)) {
                            return null;
                        }
                        return asLiteral(() -> parseValue(sv.getValue()));
                    }
                })
                .build();
    }

    private static GraphQLScalarType time(String name) {
        return GraphQLScalarType.newScalar()
                .name(name)
                .description("An ISO-8601 time string (HH:mm:ss)")
                .coercing(new Coercing<String, String>() {
                    @Override
                    public String serialize(Object dataFetcherResult) {
                        return dataFetcherResult != null ? dataFetcherResult.toString() : null;
                    }

                    @Override
                    public String parseValue(Object input) {
                        String str = input.toString();
                        try {
                            LocalTime.parse(str, DateTimeFormatter.ISO_LOCAL_TIME);
                        } catch (DateTimeParseException e) {
                            throw new CoercingParseValueException("Invalid " + name + ": " + str);
                        }
                        return str;
                    }

                    @Override
                    public String parseLiteral(Object input) {
                        if (!(input instanceof StringValue sv)) {
                            return null;
                        }
                        return asLiteral(() -> parseValue(sv.getValue()));
                    }
                })
                .build();
    }

    private static GraphQLScalarType epochSeconds(String name) {
        return GraphQLScalarType.newScalar()
                .name(name)
                .description("Unix epoch seconds (0 to 32503680000)")
                .coercing(new Coercing<Long, Long>() {
                    @Override
                    public Long serialize(Object dataFetcherResult) {
                        if (dataFetcherResult == null) {
                            return null;
                        }
                        if (dataFetcherResult instanceof Number n) {
                            return n.longValue();
                        }
                        return Long.parseLong(dataFetcherResult.toString());
                    }

                    @Override
                    public Long parseValue(Object input) {
                        long val;
                        if (input instanceof Number n) {
                            val = n.longValue();
                        } else {
                            val = Long.parseLong(input.toString());
                        }
                        if (val < 0 || val > 32503680000L) {
                            throw new CoercingParseValueException(name + " out of range: " + val);
                        }
                        return val;
                    }

                    @Override
                    public Long parseLiteral(Object input) {
                        if (input instanceof IntValue iv) {
                            return asLiteral(() -> parseValue(iv.getValue().longValue()));
                        }
                        throw new CoercingParseLiteralException(name + " must be an integer");
                    }
                })
                .build();
    }

    private static GraphQLScalarType email(String name) {
        return GraphQLScalarType.newScalar()
                .name(name)
                .description("An RFC 5322 email address")
                .coercing(new Coercing<String, String>() {
                    @Override
                    public String serialize(Object dataFetcherResult) {
                        return dataFetcherResult != null ? dataFetcherResult.toString() : null;
                    }

                    @Override
                    public String parseValue(Object input) {
                        String str = input.toString();
                        if (!EMAIL_PATTERN.matcher(str).matches()) {
                            throw new CoercingParseValueException("Invalid " + name + ": " + str);
                        }
                        return str;
                    }

                    @Override
                    public String parseLiteral(Object input) {
                        if (!(input instanceof StringValue sv)) {
                            return null;
                        }
                        return asLiteral(() -> parseValue(sv.getValue()));
                    }
                })
                .build();
    }

    private static GraphQLScalarType url(String name) {
        return GraphQLScalarType.newScalar()
                .name(name)
                .description("A valid URL")
                .coercing(new Coercing<String, String>() {
                    @Override
                    public String serialize(Object dataFetcherResult) {
                        return dataFetcherResult != null ? dataFetcherResult.toString() : null;
                    }

                    @Override
                    public String parseValue(Object input) {
                        String str = input.toString();
                        try {
                            URI.create(str).toURL();
                        } catch (Exception e) {
                            throw new CoercingParseValueException("Invalid " + name + ": " + str);
                        }
                        return str;
                    }

                    @Override
                    public String parseLiteral(Object input) {
                        if (!(input instanceof StringValue sv)) {
                            return null;
                        }
                        return asLiteral(() -> parseValue(sv.getValue()));
                    }
                })
                .build();
    }

    private static GraphQLScalarType phone(String name) {
        return GraphQLScalarType.newScalar()
                .name(name)
                .description("An E.164 phone number")
                .coercing(new Coercing<String, String>() {
                    @Override
                    public String serialize(Object dataFetcherResult) {
                        return dataFetcherResult != null ? dataFetcherResult.toString() : null;
                    }

                    @Override
                    public String parseValue(Object input) {
                        String str = input.toString();
                        if (!PHONE_PATTERN.matcher(str).matches()) {
                            throw new CoercingParseValueException("Invalid " + name + ": " + str);
                        }
                        return str;
                    }

                    @Override
                    public String parseLiteral(Object input) {
                        if (!(input instanceof StringValue sv)) {
                            return null;
                        }
                        return asLiteral(() -> parseValue(sv.getValue()));
                    }
                })
                .build();
    }

    private static GraphQLScalarType ipAddress(String name) {
        return GraphQLScalarType.newScalar()
                .name(name)
                .description("An IPv4 or IPv6 address")
                .coercing(new Coercing<String, String>() {
                    @Override
                    public String serialize(Object dataFetcherResult) {
                        return dataFetcherResult != null ? dataFetcherResult.toString() : null;
                    }

                    @Override
                    public String parseValue(Object input) {
                        String str = input.toString();
                        if (!IPV4_PATTERN.matcher(str).matches() && !IPV6_PATTERN.matcher(str).matches()) {
                            throw new CoercingParseValueException("Invalid " + name + ": " + str);
                        }
                        return str;
                    }

                    @Override
                    public String parseLiteral(Object input) {
                        if (!(input instanceof StringValue sv)) {
                            return null;
                        }
                        return asLiteral(() -> parseValue(sv.getValue()));
                    }
                })
                .build();
    }

    private static GraphQLScalarType booleanKind(String name) {
        return GraphQLScalarType.newScalar()
                .name(name)
                .description("A boolean value")
                .coercing(new Coercing<Boolean, Boolean>() {
                    @Override
                    public Boolean serialize(Object dataFetcherResult) {
                        if (dataFetcherResult == null) {
                            return null;
                        }
                        if (dataFetcherResult instanceof Boolean b) {
                            return b;
                        }
                        throw new CoercingSerializeException(
                                name + " cannot serialize non-boolean value: " + dataFetcherResult.getClass().getSimpleName());
                    }

                    @Override
                    public Boolean parseValue(Object input) {
                        if (input instanceof Boolean b) {
                            return b;
                        }
                        throw new CoercingParseValueException(name + " cannot parse non-boolean value: " + input);
                    }

                    @Override
                    public Boolean parseLiteral(Object input) {
                        if (input instanceof BooleanValue bv) {
                            return bv.isValue();
                        }
                        throw new CoercingParseLiteralException(name + " must be a boolean literal");
                    }
                })
                .build();
    }

    private static GraphQLScalarType longKind(String name) {
        return GraphQLScalarType.newScalar()
                .name(name)
                .description("A 64-bit signed integer")
                .coercing(new Coercing<Long, Long>() {
                    @Override
                    public Long serialize(Object dataFetcherResult) {
                        if (dataFetcherResult == null) {
                            return null;
                        }
                        if (dataFetcherResult instanceof Number n) {
                            return n.longValue();
                        }
                        return Long.parseLong(dataFetcherResult.toString());
                    }

                    @Override
                    public Long parseValue(Object input) {
                        if (input instanceof Number n) {
                            return n.longValue();
                        }
                        return Long.parseLong(input.toString());
                    }

                    @Override
                    public Long parseLiteral(Object input) {
                        if (input instanceof IntValue iv) {
                            return iv.getValue().longValue();
                        }
                        throw new CoercingParseLiteralException(name + " must be an integer");
                    }
                })
                .build();
    }

    private static GraphQLScalarType integer(String name) {
        return GraphQLScalarType.newScalar()
                .name(name)
                .description("A 32-bit signed integer")
                .coercing(new Coercing<Integer, Integer>() {
                    @Override
                    public Integer serialize(Object dataFetcherResult) {
                        if (dataFetcherResult == null) {
                            return null;
                        }
                        if (dataFetcherResult instanceof Number n) {
                            return n.intValue();
                        }
                        return Integer.parseInt(dataFetcherResult.toString());
                    }

                    @Override
                    public Integer parseValue(Object input) {
                        if (input instanceof Number n) {
                            return n.intValue();
                        }
                        return Integer.parseInt(input.toString());
                    }

                    @Override
                    public Integer parseLiteral(Object input) {
                        if (input instanceof IntValue iv) {
                            return iv.getValue().intValue();
                        }
                        throw new CoercingParseLiteralException(name + " must be an integer");
                    }
                })
                .build();
    }

    private static GraphQLScalarType shortKind(String name) {
        return GraphQLScalarType.newScalar()
                .name(name)
                .description("A 16-bit signed integer (-32768 to 32767)")
                .coercing(new Coercing<Integer, Integer>() {
                    @Override
                    public Integer serialize(Object dataFetcherResult) {
                        if (dataFetcherResult instanceof Number n) {
                            return n.intValue();
                        }
                        return Integer.parseInt(dataFetcherResult.toString());
                    }

                    @Override
                    public Integer parseValue(Object input) {
                        int val;
                        if (input instanceof Number n) {
                            val = n.intValue();
                        } else {
                            val = Integer.parseInt(input.toString());
                        }
                        if (val < -32768 || val > 32767) {
                            throw new CoercingParseValueException(name + " out of range: " + val);
                        }
                        return val;
                    }

                    @Override
                    public Integer parseLiteral(Object input) {
                        if (input instanceof IntValue iv) {
                            return asLiteral(() -> parseValue(iv.getValue().intValue()));
                        }
                        throw new CoercingParseLiteralException(name + " must be an integer");
                    }
                })
                .build();
    }

    private static GraphQLScalarType floatKind(String name) {
        return GraphQLScalarType.newScalar()
                .name(name)
                .description("An IEEE 754 double-precision float")
                .coercing(new Coercing<Double, Double>() {
                    @Override
                    public Double serialize(Object dataFetcherResult) {
                        if (dataFetcherResult == null) {
                            return null;
                        }
                        if (dataFetcherResult instanceof Number n) {
                            return n.doubleValue();
                        }
                        return Double.parseDouble(dataFetcherResult.toString());
                    }

                    @Override
                    public Double parseValue(Object input) {
                        if (input instanceof Number n) {
                            return n.doubleValue();
                        }
                        return Double.parseDouble(input.toString());
                    }

                    @Override
                    public Double parseLiteral(Object input) {
                        if (input instanceof FloatValue fv) {
                            return fv.getValue().doubleValue();
                        }
                        if (input instanceof IntValue iv) {
                            return iv.getValue().doubleValue();
                        }
                        throw new CoercingParseLiteralException(name + " must be a number");
                    }
                })
                .build();
    }

    private static GraphQLScalarType bigDecimal(String name) {
        return GraphQLScalarType.newScalar()
                .name(name)
                .description("An arbitrary-precision decimal number")
                .coercing(new Coercing<String, String>() {
                    @Override
                    public String serialize(Object dataFetcherResult) {
                        return dataFetcherResult != null ? dataFetcherResult.toString() : null;
                    }

                    @Override
                    public String parseValue(Object input) {
                        String str = input.toString();
                        try {
                            new BigDecimal(str);
                        } catch (NumberFormatException e) {
                            throw new CoercingParseValueException("Invalid " + name + ": " + str);
                        }
                        return str;
                    }

                    @Override
                    public String parseLiteral(Object input) {
                        if (input instanceof StringValue sv) {
                            return asLiteral(() -> parseValue(sv.getValue()));
                        }
                        if (input instanceof FloatValue fv) {
                            return fv.getValue().toString();
                        }
                        if (input instanceof IntValue iv) {
                            return iv.getValue().toString();
                        }
                        return null;
                    }
                })
                .build();
    }

    private static GraphQLScalarType bigInteger(String name) {
        return GraphQLScalarType.newScalar()
                .name(name)
                .description("An arbitrary-precision integer")
                .coercing(new Coercing<String, String>() {
                    @Override
                    public String serialize(Object dataFetcherResult) {
                        return dataFetcherResult != null ? dataFetcherResult.toString() : null;
                    }

                    @Override
                    public String parseValue(Object input) {
                        String str = input.toString();
                        try {
                            new BigInteger(str);
                        } catch (NumberFormatException e) {
                            throw new CoercingParseValueException("Invalid " + name + ": " + str);
                        }
                        return str;
                    }

                    @Override
                    public String parseLiteral(Object input) {
                        if (input instanceof IntValue iv) {
                            return iv.getValue().toString();
                        }
                        if (input instanceof StringValue sv) {
                            return asLiteral(() -> parseValue(sv.getValue()));
                        }
                        return null;
                    }
                })
                .build();
    }

    private static GraphQLScalarType base64(String name) {
        return GraphQLScalarType.newScalar()
                .name(name)
                .description("A base64-encoded byte array")
                .coercing(new Coercing<String, String>() {
                    @Override
                    public String serialize(Object dataFetcherResult) {
                        return dataFetcherResult != null ? dataFetcherResult.toString() : null;
                    }

                    @Override
                    public String parseValue(Object input) {
                        String str = input.toString();
                        try {
                            Base64.getDecoder().decode(str);
                        } catch (IllegalArgumentException e) {
                            throw new CoercingParseValueException("Invalid " + name + ": not valid base64");
                        }
                        return str;
                    }

                    @Override
                    public String parseLiteral(Object input) {
                        if (!(input instanceof StringValue sv)) {
                            return null;
                        }
                        return asLiteral(() -> parseValue(sv.getValue()));
                    }
                })
                .build();
    }

    /**
     * graphql-java only catches {@link CoercingParseLiteralException} while validating a literal
     * argument value; several kinds above delegate to parseValue(), which throws {@link
     * CoercingParseValueException} instead. Uncaught, that escapes the whole request as an
     * unhandled exception (an HTTP 500) rather than becoming a spec-correct ValidationError.
     * This wraps the delegation so a bad literal fails the same way a bad variable value does.
     */
    private static <T> T asLiteral(Supplier<T> parseValueCall) {
        try {
            return parseValueCall.get();
        } catch (CoercingParseValueException e) {
            throw new CoercingParseLiteralException(e.getMessage());
        }
    }
}
