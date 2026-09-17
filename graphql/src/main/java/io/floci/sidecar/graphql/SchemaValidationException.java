package io.floci.sidecar.graphql;

import java.util.List;

/** Carries structured per-problem detail for {@code /v1/schema/validate}'s 400 response. */
public final class SchemaValidationException extends IllegalArgumentException {

    private final List<Issue> issues;

    public SchemaValidationException(String message, List<Issue> issues) {
        super(message);
        this.issues = issues;
    }

    public List<Issue> issues() {
        return issues;
    }

    /** One structured problem in an SDL. */
    public record Issue(String category, String message, int line, int column) {
    }
}
