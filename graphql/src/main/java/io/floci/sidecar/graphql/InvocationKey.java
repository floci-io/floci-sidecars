package io.floci.sidecar.graphql;

import java.util.List;
import java.util.Map;

/**
 * One field the resolver callback is asked to resolve. Its execution path is always unique
 * within a single request, so two keys are equal only when they describe the exact same field
 * instance, which is what {@link org.dataloader.DataLoader} needs to dedupe correctly.
 */
record InvocationKey(String typeName, String fieldName, Map<String, Object> arguments, Object source,
                      List<Object> path, Map<String, Object> variables, List<String> selectionSetList) {
}
