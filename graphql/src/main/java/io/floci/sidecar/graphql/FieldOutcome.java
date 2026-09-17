package io.floci.sidecar.graphql;

/** What the resolver callback said about one field: either a value, or an error to attach at its path. */
record FieldOutcome(Object data, FieldError error) {

    static FieldOutcome success(Object data) {
        return new FieldOutcome(data, null);
    }

    static FieldOutcome failure(FieldError error) {
        return new FieldOutcome(null, error);
    }

    /** Mirrors the callback's {@code {"message", "type", "data", "info"}} error shape. */
    record FieldError(String message, String type, Object data, Object info) {
    }
}
