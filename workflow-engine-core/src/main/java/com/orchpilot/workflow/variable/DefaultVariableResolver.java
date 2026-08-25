package com.orchpilot.workflow.variable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Placeholder resolution for {@code ${path}} references.
 *
 * <p>Behaviour worth knowing:
 *
 * <ul>
 *   <li><b>Type preservation.</b> {@code "${amount}"} resolves to the underlying number, not its text.
 *       Without this, {@code timeout: "${timeoutMs}"} would arrive at a plugin as a string and every
 *       plugin author would write the same parsing boilerplate.</li>
 *   <li><b>Unresolved placeholders stay literal.</b> A missing variable leaves {@code ${foo}} in place
 *       and logs at debug. Substituting an empty string would silently send email to nobody; leaving
 *       the marker makes the mistake visible in the plugin execution record.</li>
 *   <li><b>Escaping.</b> {@code $${notAVariable}} renders as the literal {@code ${notAVariable}}, so
 *       a body containing shell or template syntax can pass through untouched.</li>
 *   <li><b>No recursion into resolved values.</b> A variable whose value itself contains
 *       {@code ${...}} is not re-expanded, which stops an input payload from being used to reach
 *       variables the author never referenced.</li>
 * </ul>
 *
 * <p>Stateless and thread-safe.
 */
@Component
public class DefaultVariableResolver implements VariableResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultVariableResolver.class);

    private static final int MAX_DEPTH = 32;

    @Override
    public Object resolve(Object value, VariableStore store) {
        return resolveValue(value, store, 0);
    }

    @Override
    public String resolveText(String template, VariableStore store) {
        if (template == null) {
            return null;
        }
        Object resolved = resolveString(template, store, null, null, SecretLookup.NONE);
        return resolved == null ? null : String.valueOf(resolved);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveConfiguration(Map<String, Object> configuration, VariableStore store) {
        Object resolved = resolveValue(configuration, store, 0);
        if (resolved instanceof Map) {
            return (Map<String, Object>) resolved;
        }
        return new LinkedHashMap<>();
    }

    @Override
    public Resolution resolveConfigurationReporting(Map<String, Object> configuration, VariableStore store) {
        return resolveConfigurationReporting(configuration, store, SecretLookup.NONE);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Resolution resolveConfigurationReporting(Map<String, Object> configuration, VariableStore store,
                                                    SecretLookup secrets) {
        List<UnresolvedReference> unresolved = new ArrayList<>();
        Object resolved = resolveValue(configuration, store, 0, "", unresolved,
                secrets == null ? SecretLookup.NONE : secrets);
        Map<String, Object> map = resolved instanceof Map
                ? (Map<String, Object>) resolved : new LinkedHashMap<>();
        return new Resolution(map, unresolved);
    }

    private Object resolveValue(Object value, VariableStore store, int depth) {
        return resolveValue(value, store, depth, null, null, SecretLookup.NONE);
    }

    /**
     * The single lookup point for every placeholder.
     *
     * <p>A {@code SECRET.} reference consults <em>only</em> the secret store, and every other path consults
     * only the variable store. Keeping both in one method is what guarantees the two can never blur: there is
     * no fall-through from a missing secret to a workflow variable of the same name, and no unqualified path
     * can reach a secret.
     */
    private Optional<Object> lookup(String path, VariableStore store, SecretLookup secrets) {
        String secretName = VariableResolver.secretReference(path);
        if (secretName != null) {
            return secrets.find(secretName).map(value -> (Object) value);
        }
        return store.find(path);
    }

    /**
     * @param field      dotted path to the entry being resolved, or null when nothing is collecting
     * @param unresolved collector for unresolved references, or null to skip reporting
     */
    @SuppressWarnings("unchecked")
    private Object resolveValue(Object value, VariableStore store, int depth, String field,
                               List<UnresolvedReference> unresolved, SecretLookup secrets) {
        if (depth > MAX_DEPTH) {
            log.warn("Stopping variable resolution at depth {}: configuration is nested too deeply", MAX_DEPTH);
            return value;
        }
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return resolveString((String) value, store, field, unresolved, secrets);
        }
        if (value instanceof Map) {
            Map<String, Object> source = (Map<String, Object>) value;
            Map<String, Object> result = new LinkedHashMap<>(Math.max(8, source.size()));
            for (Map.Entry<String, Object> entry : source.entrySet()) {
                // Keys are resolved too: header maps and query parameters legitimately use variables.
                String key = entry.getKey() == null ? null
                        : String.valueOf(resolveString(entry.getKey(), store, field, unresolved, secrets));
                result.put(key, resolveValue(entry.getValue(), store, depth + 1,
                        child(field, String.valueOf(entry.getKey())), unresolved, secrets));
            }
            return result;
        }
        if (value instanceof List) {
            List<Object> source = (List<Object>) value;
            List<Object> result = new ArrayList<>(source.size());
            for (int i = 0; i < source.size(); i++) {
                result.add(resolveValue(source.get(i), store, depth + 1,
                        field == null ? null : field + "[" + i + "]", unresolved, secrets));
            }
            return result;
        }
        return value;
    }

    /** Builds the dotted path for a nested entry; the top level has no prefix. */
    private static String child(String field, String key) {
        if (field == null) {
            return null;
        }
        return field.isEmpty() ? key : field + "." + key;
    }

    private void record(List<UnresolvedReference> unresolved, String field, String path) {
        if (unresolved != null && field != null) {
            unresolved.add(new UnresolvedReference(field, path));
        }
    }

    /**
     * @return the referenced value with its original type when the whole string is a single
     *         placeholder, otherwise the rendered text
     */
    private Object resolveString(String template, VariableStore store, String field,
                                 List<UnresolvedReference> unresolved, SecretLookup secrets) {
        if (template == null || template.indexOf('$') < 0) {
            return template;
        }
        String singlePath = wholeStringPlaceholder(template);
        if (singlePath != null) {
            Optional<Object> found = lookup(singlePath, store, secrets);
            if (found.isPresent()) {
                return found.get();
            }
            log.debug("Unresolved variable '{}' left as a literal placeholder", singlePath);
            record(unresolved, field, singlePath);
            return template;
        }
        return renderText(template, store, field, unresolved, secrets);
    }

    /**
     * @return the path when {@code template} is exactly one placeholder, otherwise {@code null}
     */
    private static String wholeStringPlaceholder(String template) {
        if (!template.startsWith("${") || !template.endsWith("}") || template.length() < 4) {
            return null;
        }
        // Reject "${a}-${b}": the first closing brace must be the last character.
        int firstClose = template.indexOf('}');
        if (firstClose != template.length() - 1) {
            return null;
        }
        String path = template.substring(2, template.length() - 1).trim();
        return path.isEmpty() ? null : path;
    }

    private String renderText(String template, VariableStore store, String field,
                              List<UnresolvedReference> unresolved, SecretLookup secrets) {
        StringBuilder out = new StringBuilder(template.length() + 32);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c != '$') {
                out.append(c);
                i++;
                continue;
            }
            // "$${...}" is an escape for a literal "${...}"
            if (i + 1 < template.length() && template.charAt(i + 1) == '$') {
                out.append('$');
                i += 2;
                continue;
            }
            if (i + 1 >= template.length() || template.charAt(i + 1) != '{') {
                out.append(c);
                i++;
                continue;
            }
            int close = template.indexOf('}', i + 2);
            if (close < 0) {
                out.append(template, i, template.length());
                break;
            }
            String path = template.substring(i + 2, close).trim();
            Optional<Object> found = path.isEmpty() ? Optional.empty() : lookup(path, store, secrets);
            if (found.isPresent()) {
                out.append(stringify(found.get()));
            } else {
                log.debug("Unresolved variable '{}' left as a literal placeholder", path);
                record(unresolved, field, path);
                out.append(template, i, close + 1);
            }
            i = close + 1;
        }
        return out.toString();
    }

    private static String stringify(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
