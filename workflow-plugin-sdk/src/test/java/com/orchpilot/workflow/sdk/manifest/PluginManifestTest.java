package com.orchpilot.workflow.sdk.manifest;

import com.orchpilot.workflow.sdk.plugin.PluginType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The static manifest the plugin server reads without loading any code.
 *
 * <p>Two properties matter here. It must report <em>every</em> problem, because the author fixing a manifest
 * should not discover the rules one rejected upload at a time. And it must never throw, because a registry that
 * answers 500 to a malformed file is telling the author the server is broken.
 */
class PluginManifestTest {

    private static final String VALID = """
            {
              "pluginId": "sendgrid",
              "name": "SendGrid Plugin",
              "version": "1.2.0",
              "description": "Send emails using SendGrid",
              "vendor": "MyCompany",
              "mainClass": "com.example.sendgrid.SendGridPlugin",
              "sdkVersion": "1.0.0",
              "javaVersion": "17",
              "pluginType": "NODE",
              "nodes": [
                {
                  "nodeType": "SENDGRID_EMAIL",
                  "displayName": "Send Email",
                  "category": "Communication",
                  "description": "Send email using SendGrid",
                  "icon": "email",
                  "configurationSchema": {
                    "type": "object",
                    "properties": { "to": { "type": "string" } }
                  }
                }
              ],
              "dependencies": [
                { "groupId": "com.sendgrid", "artifactId": "sendgrid-java", "version": "4.9.3" }
              ],
              "permissions": { "network": ["api.sendgrid.com"] }
            }
            """;

    @Test
    @DisplayName("reads the whole manifest")
    void readsEverything() {
        PluginManifest manifest = PluginManifest.parse(VALID);

        assertTrue(manifest.isValid(), () -> "unexpected problems: " + manifest.validate());
        assertEquals("sendgrid", manifest.pluginId());
        assertEquals("1.2.0", manifest.version());
        assertEquals("sendgrid:1.2.0", manifest.coordinate());
        assertEquals("com.example.sendgrid.SendGridPlugin", manifest.mainClass());
        assertEquals(PluginType.NODE, manifest.pluginType());

        assertEquals(1, manifest.nodes().size());
        PluginManifest.ManifestNode node = manifest.nodes().get(0);
        assertEquals("SENDGRID_EMAIL", node.nodeType());
        assertEquals("Send Email", node.label());
        assertEquals("Communication", node.category());
        assertEquals("object", node.configurationSchema().get("type"));

        assertEquals("com.sendgrid:sendgrid-java:4.9.3", manifest.dependencies().get(0).coordinate());
        assertEquals("bundled", manifest.dependencies().get(0).scope(), "scope defaults to bundled");
        assertTrue(manifest.requestedPermissions().containsKey("network"),
                "requested permissions are recorded, and granted separately by an administrator");
    }

    @Test
    @DisplayName("falls back to the id when no name is given")
    void nameFallsBackToId() {
        PluginManifest manifest = PluginManifest.parse(
                "{\"pluginId\":\"slack\",\"version\":\"1.0.0\",\"mainClass\":\"a.B\",\"sdkVersion\":\"1.0.0\","
                        + "\"nodes\":[{\"nodeType\":\"SLACK_MESSAGE\"}]}");

        assertEquals("slack", manifest.name());
        assertEquals("SLACK_MESSAGE", manifest.nodes().get(0).label(), "label falls back to the node type");
    }

    @Test
    @DisplayName("reports every missing required field at once")
    void reportsEveryProblem() {
        List<String> problems = PluginManifest.parse("{}").validate();

        assertTrue(problems.stream().anyMatch(p -> p.contains("pluginId is required")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("version is required")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("mainClass is required")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("sdkVersion is required")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("must declare at least one node")));
    }

    @Test
    @DisplayName("refuses a plugin id containing its version, and says what to use instead")
    void refusesVersionInsideId() {
        List<String> problems = PluginManifest.parse(
                "{\"pluginId\":\"sendgrid-1.2.0\",\"version\":\"1.2.0\","
                        + "\"mainClass\":\"a.B\",\"sdkVersion\":\"1.0.0\","
                        + "\"nodes\":[{\"nodeType\":\"X\"}]}").validate();

        assertTrue(problems.stream().anyMatch(p -> p.contains("must not contain the version")
                        && p.contains("pluginId 'sendgrid'")),
                () -> "the message must name the id to use: " + problems);
    }

    @Test
    @DisplayName("refuses an id or node type whose shape would collide downstream")
    void refusesBadShapes() {
        List<String> problems = PluginManifest.parse(
                "{\"pluginId\":\"Send Grid!\",\"version\":\"1.2\",\"mainClass\":\"notAClass\","
                        + "\"sdkVersion\":\"1.0.0\",\"nodes\":[{\"nodeType\":\"sendgrid email\"}]}").validate();

        assertTrue(problems.stream().anyMatch(p -> p.contains("lower-case letters")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("not a semantic version")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("not a fully qualified Java class name")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("must be upper-case letters")));
    }

    @Test
    @DisplayName("refuses a duplicated node type")
    void refusesDuplicateNodeType() {
        List<String> problems = PluginManifest.parse(
                "{\"pluginId\":\"x\",\"version\":\"1.0.0\",\"mainClass\":\"a.B\",\"sdkVersion\":\"1.0.0\","
                        + "\"nodes\":[{\"nodeType\":\"A\"},{\"nodeType\":\"A\"}]}").validate();

        assertTrue(problems.stream().anyMatch(p -> p.contains("declared more than once")));
    }

    @Test
    @DisplayName("returns a problem rather than throwing on a file that is not JSON")
    void survivesGarbage() {
        for (String garbage : List.of("not json at all", "[1,2,3]", "", "{\"nodes\": 7}")) {
            PluginManifest manifest = PluginManifest.parse(garbage);
            assertFalse(manifest.isValid(), "should reject '" + garbage + "'");
            assertFalse(manifest.validate().isEmpty(), "and should say why");
        }
    }

    @Test
    @DisplayName("names an unknown plugin type instead of guessing")
    void reportsUnknownType() {
        List<String> problems = PluginManifest.parse(
                "{\"pluginId\":\"x\",\"version\":\"1.0.0\",\"mainClass\":\"a.B\",\"sdkVersion\":\"1.0.0\","
                        + "\"pluginType\":\"WIDGET\",\"nodes\":[{\"nodeType\":\"A\"}]}").validate();

        assertTrue(problems.stream().anyMatch(p -> p.contains("pluginType 'WIDGET'")));
    }
}
