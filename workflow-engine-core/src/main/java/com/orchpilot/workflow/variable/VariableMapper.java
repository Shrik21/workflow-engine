package com.orchpilot.workflow.variable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Applies a node's declared input and output mappings.
 *
 * <p>The two directions are deliberately asymmetric, matching how workflow authors think:
 *
 * <ul>
 *   <li><b>Input mapping</b> is {@code inputName -> expression}. The value is a template resolved
 *       against the current variables: {@code {"employeeId": "${employeeId}"}}.</li>
 *   <li><b>Output mapping</b> is {@code outputName -> variablePath}. The node's output is copied to a
 *       variable: {@code {"approved": "workflow.approved"}}.</li>
 * </ul>
 *
 * <p>Without explicit mappings a node's outputs are still readable as
 * {@code ${node.<nodeId>.<output>}}; mapping is how an author promotes a value to a stable name that
 * later nodes can depend on without knowing which node produced it.
 */
@Component
public class VariableMapper {

    private static final Logger log = LoggerFactory.getLogger(VariableMapper.class);

    private final VariableResolver resolver;

    public VariableMapper(VariableResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * @param inputMapping input name to template, may be {@code null}
     * @param store        variables to resolve against
     * @return resolved inputs, never {@code null}
     */
    public Map<String, Object> applyInputMapping(Map<String, String> inputMapping, VariableStore store) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        if (inputMapping == null || inputMapping.isEmpty()) {
            return inputs;
        }
        inputMapping.forEach((name, template) -> inputs.put(name, resolver.resolve(template, store)));
        return inputs;
    }

    /**
     * Copies node outputs into variables.
     *
     * <p>An output named in the mapping but absent from the result is skipped and logged rather than
     * written as null: a decision node that never ran should not leave a null {@code approved} flag
     * that a later expression silently reads as false.
     *
     * @param outputMapping output name to destination variable path, may be {@code null}
     * @param outputs       the node's outputs
     * @param store         store to write into
     * @return number of variables written
     */
    public int applyOutputMapping(Map<String, String> outputMapping, Map<String, Object> outputs,
                                 VariableStore store) {
        if (outputMapping == null || outputMapping.isEmpty() || outputs == null) {
            return 0;
        }
        int written = 0;
        for (Map.Entry<String, String> entry : outputMapping.entrySet()) {
            String outputName = entry.getKey();
            String destination = entry.getValue();
            if (destination == null || destination.isBlank()) {
                continue;
            }
            Optional<Object> value = readOutput(outputs, outputName);
            if (value.isEmpty()) {
                log.debug("Output mapping skipped: node produced no output named '{}'", outputName);
                continue;
            }
            try {
                store.set(stripPlaceholder(destination), value.get());
                written++;
            } catch (IllegalArgumentException ex) {
                log.warn("Output mapping '{}' -> '{}' rejected: {}", outputName, destination, ex.getMessage());
            }
        }
        return written;
    }

    private static Optional<Object> readOutput(Map<String, Object> outputs, String outputName) {
        if (outputName == null) {
            return Optional.empty();
        }
        if (outputs.containsKey(outputName)) {
            return Optional.ofNullable(outputs.get(outputName));
        }
        // Allow dotted access into a structured output, e.g. "response.status".
        return com.orchpilot.workflow.utility.MapPaths.find(outputs, outputName);
    }

    /**
     * Accepts both {@code workflow.approved} and {@code ${workflow.approved}} as a destination, because
     * authors write the second form out of habit and rejecting it teaches nothing useful.
     */
    private static String stripPlaceholder(String destination) {
        String trimmed = destination.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            return trimmed.substring(2, trimmed.length() - 1).trim();
        }
        return trimmed;
    }
}
