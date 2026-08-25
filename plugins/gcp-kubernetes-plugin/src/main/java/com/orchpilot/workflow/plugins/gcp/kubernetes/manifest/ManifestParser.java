package com.orchpilot.workflow.plugins.gcp.kubernetes.manifest;

import com.orchpilot.workflow.plugins.gcp.kubernetes.model.K8sResource;
import com.orchpilot.workflow.sdk.json.Json;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses and validates a Kubernetes manifest before anything is sent to a cluster.
 *
 * <h2>Parsing untrusted YAML safely</h2>
 *
 * A manifest can come from an AI Agent, a form field, or a repository — none of it trusted. YAML is a far larger
 * language than it looks: stock parsers will instantiate arbitrary Java classes from {@code !!} type tags, which is
 * a remote-code-execution primitive, and anchor expansion can be used to blow up memory from a few hundred bytes of
 * input. Three defences are applied here:
 *
 * <ul>
 *   <li>{@link SafeConstructor} — only plain maps, lists and scalars are ever constructed, so no {@code !!} tag can
 *       name a class.</li>
 *   <li>Aliases disabled and code-point limited — closes the billion-laughs expansion.</li>
 *   <li>An explicit kind allow-list — a parsed document that names a kind outside {@link K8sResource} is rejected
 *       rather than forwarded, so the plugin cannot be used as a generic conduit to the cluster API.</li>
 * </ul>
 *
 * <h2>Validation is structural, not schema-complete</h2>
 *
 * This checks the things that make a manifest unusable regardless of cluster: it must be a mapping, it must name a
 * recognised {@code kind}, its {@code apiVersion} must match that kind, and it must have a {@code metadata.name}.
 * Field-level validity is the API server's job — that is what the server-side dry run is for, and it is offered as
 * an option rather than being reimplemented badly here.
 */
public final class ManifestParser {

    /** Enough for a large multi-document manifest, small enough that a runaway input fails fast. */
    private static final int MAX_MANIFEST_CHARS = 512 * 1024;

    private ManifestParser() {
    }

    /** One parsed document plus what the plugin worked out about it. */
    public record Document(K8sResource resource, String name, String namespace, Map<String, Object> body) {
    }

    /** The outcome of validating a manifest: either usable documents, or the reasons it is not. */
    public record Validation(List<Document> documents, List<String> problems) {

        public boolean valid() {
            return problems.isEmpty();
        }
    }

    /**
     * Parses YAML or JSON and validates every document it contains.
     *
     * @param manifest         the raw manifest text; multi-document YAML ({@code ---}) is supported
     * @param defaultNamespace applied to any namespaced document that does not name one
     * @return the documents and any problems; problems being empty is the only signal that it is safe to apply
     */
    public static Validation validate(String manifest, String defaultNamespace) {
        List<String> problems = new ArrayList<>();
        List<Document> documents = new ArrayList<>();

        if (manifest == null || manifest.isBlank()) {
            problems.add("The manifest is empty.");
            return new Validation(documents, problems);
        }
        if (manifest.length() > MAX_MANIFEST_CHARS) {
            problems.add("The manifest is larger than the " + (MAX_MANIFEST_CHARS / 1024) + " KB limit.");
            return new Validation(documents, problems);
        }

        List<Object> parsed;
        try {
            parsed = parseAll(manifest);
        } catch (RuntimeException ex) {
            // The parser's message names the line and column, which is the useful part; it contains no cluster data.
            problems.add("The manifest could not be parsed: " + firstLine(ex.getMessage()));
            return new Validation(documents, problems);
        }

        if (parsed.isEmpty()) {
            problems.add("The manifest contains no documents.");
            return new Validation(documents, problems);
        }

        int index = 0;
        for (Object raw : parsed) {
            index++;
            String where = parsed.size() == 1 ? "The manifest" : "Document " + index;
            if (!(raw instanceof Map<?, ?> map)) {
                problems.add(where + " is not a mapping — a Kubernetes resource must be a YAML/JSON object.");
                continue;
            }
            Map<String, Object> body = toStringKeyed(map);
            String kind = string(body, "kind");
            if (kind == null) {
                problems.add(where + " has no 'kind'.");
                continue;
            }
            K8sResource resource = K8sResource.forKind(kind);
            if (resource == null) {
                problems.add(where + " has kind '" + kind + "', which this plugin does not manage. Supported "
                        + "kinds: " + supportedKinds() + ".");
                continue;
            }
            String apiVersion = string(body, "apiVersion");
            if (apiVersion == null) {
                problems.add(where + " has no 'apiVersion' (expected '" + resource.apiVersion() + "').");
            } else if (!apiVersion.equals(resource.apiVersion())) {
                problems.add(where + " declares apiVersion '" + apiVersion + "' for kind " + resource.kind()
                        + ", but this plugin targets '" + resource.apiVersion() + "'.");
            }

            Map<String, Object> metadata = mapValue(body, "metadata");
            String name = metadata == null ? null : string(metadata, "name");
            if (name == null || name.isBlank()) {
                problems.add(where + " has no 'metadata.name'.");
                continue;
            }

            String namespace = metadata == null ? null : string(metadata, "namespace");
            if (resource.namespaced() && (namespace == null || namespace.isBlank())) {
                namespace = defaultNamespace == null || defaultNamespace.isBlank() ? "default" : defaultNamespace;
                // Write it back so what gets applied is exactly what was validated.
                metadata.put("namespace", namespace);
            }
            documents.add(new Document(resource, name, resource.namespaced() ? namespace : null, body));
        }
        return new Validation(documents, problems);
    }

    /**
     * Parses YAML or JSON into raw documents.
     *
     * <p>JSON is a subset of YAML, so one parser handles both — which is why a manifest pasted as JSON works with
     * no separate code path.
     */
    private static List<Object> parseAll(String manifest) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setProcessComments(false);
        options.setCodePointLimit(MAX_MANIFEST_CHARS);
        options.setMaxAliasesForCollections(0);
        options.setAllowRecursiveKeys(false);

        // The single-argument form takes its loading config from the constructor, so the limits above apply.
        List<Object> documents = new ArrayList<>();
        for (Object document : new Yaml(new SafeConstructor(options)).loadAll(manifest)) {
            if (document != null) {
                documents.add(document);
            }
        }
        return documents;
    }

    /**
     * Rewrites a parsed map with String keys.
     *
     * <p>YAML permits non-string keys ({@code 8080: value} parses as an Integer key), and the JSON writer that
     * eventually serialises this for the API expects strings. Normalising once here avoids a confusing failure at
     * the wire.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toStringKeyed(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                value = toStringKeyed(nested);
            } else if (value instanceof List<?> list) {
                value = normaliseList(list);
            }
            result.put(String.valueOf(entry.getKey()), value);
        }
        return result;
    }

    private static List<Object> normaliseList(List<?> source) {
        List<Object> result = new ArrayList<>(source.size());
        for (Object item : source) {
            if (item instanceof Map<?, ?> map) {
                result.add(toStringKeyed(map));
            } else if (item instanceof List<?> nested) {
                result.add(normaliseList(nested));
            } else {
                result.add(item);
            }
        }
        return result;
    }

    /** @return the manifest as the JSON the Kubernetes API wants */
    public static String toJson(Map<String, Object> document) {
        return Json.write(document);
    }

    private static String supportedKinds() {
        StringBuilder kinds = new StringBuilder();
        for (K8sResource resource : K8sResource.values()) {
            if (!kinds.isEmpty()) {
                kinds.append(", ");
            }
            kinds.append(resource.kind());
        }
        return kinds.toString();
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Map<String, Object> map, String key) {
        return map.get(key) instanceof Map<?, ?> nested ? (Map<String, Object>) nested : null;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "invalid YAML";
        }
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        return line.length() > 300 ? line.substring(0, 300) + "…" : line;
    }
}
