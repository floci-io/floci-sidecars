package io.floci.sidecar.graphql.scalars;

import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScalarKindsTest {

    @Test
    void kinds_listsAllSeventeen() {
        assertThat(ScalarKinds.kinds(), hasItems("json-string", "date-time", "date", "time", "epoch-seconds",
                "email", "url", "phone", "ip-address", "boolean", "long", "integer", "short", "float",
                "big-decimal", "big-integer", "base64"));
        assertThat(ScalarKinds.kinds().size(), is(17));
    }

    @Test
    void unmappedKind_returnsEmpty() {
        assertThat(ScalarKinds.scalarFor("Whatever", "not-a-kind"), is(Optional.empty()));
    }

    @Test
    void errorMessages_nameTheCallersDeclaredScalarNotTheKind() {
        Coercing<?, ?> coercing = scalar("MyDateTime", "date-time");

        CoercingParseValueException error = assertThrows(CoercingParseValueException.class,
                () -> coercing.parseValue("not-a-date"));
        assertThat(error.getMessage(), containsString("MyDateTime"));
    }

    @Test
    void dateTime_parseValue() {
        Coercing<Object, Object> coercing = scalar("AWSDateTime", "date-time");

        assertThat(coercing.parseValue("2026-06-04T12:00:00Z"), is("2026-06-04T12:00:00Z"));
        assertThrows(CoercingParseValueException.class, () -> coercing.parseValue("not-a-date"));
    }

    @Test
    void dateTime_parseLiteral_throwsCoercingParseLiteralExceptionNotParseValue() {
        // graphql-java only catches CoercingParseLiteralException while validating a literal
        // query argument; parseLiteral() delegating straight to parseValue() would let
        // CoercingParseValueException escape uncaught, turning a bad literal into an unhandled
        // server error rather than a GraphQL ValidationError. Fixed via ScalarKinds.asLiteral().
        Coercing<Object, Object> coercing = scalar("AWSDateTime", "date-time");

        assertThat(coercing.parseLiteral(new StringValue("2026-06-04T12:00:00Z")), is("2026-06-04T12:00:00Z"));
        assertThrows(CoercingParseLiteralException.class,
                () -> coercing.parseLiteral(new StringValue("not-a-date")));
    }

    @Test
    void shortKind_parseLiteral_outOfRange_throwsCoercingParseLiteralExceptionNotParseValue() {
        // Same fix, exercised through the numeric (IntValue) delegation path rather than string.
        Coercing<Object, Object> coercing = scalar("AWSShort", "short");

        assertThrows(CoercingParseLiteralException.class,
                () -> coercing.parseLiteral(new IntValue(BigInteger.valueOf(99999))));
    }

    @Test
    void jsonString_parseValue() {
        Coercing<Object, Object> coercing = scalar("AWSJSON", "json-string");

        assertThat(coercing.parseValue("{\"key\": \"value\"}"), is("{\"key\": \"value\"}"));
        assertThrows(CoercingParseValueException.class, () -> coercing.parseValue("not json"));
    }

    @Test
    void email_parseValue() {
        Coercing<Object, Object> coercing = scalar("AWSEmail", "email");

        assertThat(coercing.parseValue("user@example.com"), is("user@example.com"));
        assertThrows(CoercingParseValueException.class, () -> coercing.parseValue("not-an-email"));
    }

    @Test
    void epochSeconds_range() {
        Coercing<Object, Object> coercing = scalar("AWSTimestamp", "epoch-seconds");

        assertThat(coercing.parseValue(1700000000L), is(1700000000L));
        assertThrows(CoercingParseValueException.class, () -> coercing.parseValue(-1L));
        assertThrows(CoercingParseValueException.class, () -> coercing.parseValue(40000000000L));
    }

    @Test
    void epochSeconds_parseValue_withString() {
        Coercing<Object, Object> coercing = scalar("AWSTimestamp", "epoch-seconds");

        assertThat(coercing.parseValue("1700000000"), is(1700000000L));
        assertThrows(Exception.class, () -> coercing.parseValue("not-a-number"));
    }

    @Test
    void url_parseValue() {
        Coercing<Object, Object> coercing = scalar("AWSURL", "url");

        assertThat(coercing.parseValue("https://example.com"), is("https://example.com"));
        assertThrows(CoercingParseValueException.class, () -> coercing.parseValue("not a url"));
    }

    @Test
    void phone_parseValue() {
        Coercing<Object, Object> coercing = scalar("AWSPhone", "phone");

        assertThat(coercing.parseValue("+1234567890"), is("+1234567890"));
        assertThrows(CoercingParseValueException.class, () -> coercing.parseValue("12345"));
    }

    @Test
    void shortKind_range() {
        Coercing<Object, Object> coercing = scalar("AWSShort", "short");

        assertThat(coercing.parseValue(32767), is(32767));
        assertThrows(CoercingParseValueException.class, () -> coercing.parseValue(40000));
        assertThrows(CoercingParseValueException.class, () -> coercing.parseValue(-32769));
    }

    @Test
    void serialize_nullSafety() {
        assertThat(scalar("AWSTimestamp", "epoch-seconds").serialize(null), is(nullValue()));
        assertThat(scalar("AWSLong", "long").serialize(null), is(nullValue()));
        assertThat(scalar("AWSInteger", "integer").serialize(null), is(nullValue()));
        assertThat(scalar("AWSFloat", "float").serialize(null), is(nullValue()));
        assertThat(scalar("AWSBoolean", "boolean").serialize(null), is(nullValue()));
    }

    @Test
    void ipAddress_validIps() {
        Coercing<Object, Object> coercing = scalar("AWSIPAddress", "ip-address");

        assertThat(coercing.parseValue("192.168.1.1"), is("192.168.1.1"));
        assertThat(coercing.parseValue("255.255.255.255"), is("255.255.255.255"));
        assertThat(coercing.parseValue("0.0.0.0"), is("0.0.0.0"));
        assertThat(coercing.parseValue("2001:0db8:85a3:0000:0000:8a2e:0370:7334"),
                is("2001:0db8:85a3:0000:0000:8a2e:0370:7334"));
        assertThat(coercing.parseValue("::1"), is("::1"));
    }

    @Test
    void ipAddress_invalidIps() {
        Coercing<Object, Object> coercing = scalar("AWSIPAddress", "ip-address");

        assertThrows(CoercingParseValueException.class, () -> coercing.parseValue("999.999.999.999"));
        assertThrows(CoercingParseValueException.class, () -> coercing.parseValue("not-an-ip"));
        assertThrows(CoercingParseValueException.class, () -> coercing.parseValue("localhost"));
        assertThrows(CoercingParseValueException.class, () -> coercing.parseValue("google.com"));
    }

    @Test
    void boolean_rejectsNonBoolean() {
        Coercing<Object, Object> coercing = scalar("AWSBoolean", "boolean");

        assertThat(coercing.parseValue(true), is(true));
        assertThat(coercing.parseValue(false), is(false));
        assertThrows(CoercingParseValueException.class, () -> coercing.parseValue("true"));
        assertThrows(CoercingParseValueException.class, () -> coercing.parseValue("xyz"));
        assertThrows(CoercingParseValueException.class, () -> coercing.parseValue(1));
        assertThrows(CoercingParseValueException.class, () -> coercing.parseValue(0));
    }

    @Test
    void boolean_serialize_rejectsNonBoolean() {
        Coercing<Object, Object> coercing = scalar("AWSBoolean", "boolean");

        assertThat(coercing.serialize(true), is(true));
        assertThat(coercing.serialize(false), is(false));
        assertThat(coercing.serialize(null), is(nullValue()));
        assertThrows(CoercingSerializeException.class, () -> coercing.serialize("true"));
        assertThrows(CoercingSerializeException.class, () -> coercing.serialize(1));
    }

    @SuppressWarnings("unchecked")
    private static Coercing<Object, Object> scalar(String name, String kind) {
        Optional<GraphQLScalarType> type = ScalarKinds.scalarFor(name, kind);
        assertTrue(type.isPresent(), () -> "No factory registered for kind: " + kind);
        assertThat(type.get().getName(), is(name));
        return (Coercing<Object, Object>) type.get().getCoercing();
    }
}
