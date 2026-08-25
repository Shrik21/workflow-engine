package com.orchpilot.workflow.integration;

import com.orchpilot.workflow.support.TestJars;
import com.orchpilot.workflow.support.testplugin.EchoPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The platform's central claim, tested over HTTP: upload a JAR to a running engine and a new node type becomes usable in
 * a workflow with no rebuild and no restart.
 *
 * <p>The JAR is assembled at test time from a compiled class, stored in GridFS by the real storage implementation, loaded
 * by the real class loader, and executed through the real engine. Nothing about the plugin path is stubbed.
 */
class PluginLifecycleIT extends AbstractMongoIntegrationTest {

    private static final String ECHO_WORKFLOW = """
            {
              "name": "Echo Workflow",
              "nodes": [
                { "id": "start-1", "type": "START" },
                { "id": "echo-1", "type": "PLUGIN", "name": "Echo",
                  "pluginId": "echo", "pluginVersion": "1.0.0",
                  "configuration": { "message": "hello ${input.who}" },
                  "outputMapping": { "message": "workflow.echoed" } },
                { "id": "end-1", "type": "END",
                  "configuration": { "outputs": { "echoed": "${workflow.echoed}" } } }
              ],
              "connections": [
                { "source": "start-1", "target": "echo-1" },
                { "source": "echo-1", "target": "end-1" }
              ]
            }
            """;

    @BeforeEach
    void cleanUp() throws Exception {
        // Remove any plugin left by a previous test method so each one starts from nothing loaded.
        MvcResult existing = mockMvc.perform(get("/api/plugins").with(asAdmin()))
                .andExpect(status().isOk()).andReturn();
        for (Object item : com.orchpilot.workflow.sdk.json.Json
                .parseArray(existing.getResponse().getContentAsString())) {
            if (item instanceof java.util.Map) {
                Object id = ((java.util.Map<?, ?>) item).get("id");
                mockMvc.perform(delete("/api/plugins/{id}", String.valueOf(id))
                        .with(asAdmin()));
            }
        }
        clearCollections("workflows", "workflow_versions", "workflow_executions", "plugin_executions");
    }

    private MockMultipartFile echoJar() {
        return new MockMultipartFile("file", "echo-plugin-1.0.0.jar", "application/java-archive",
                TestJars.pluginJar(EchoPlugin.class));
    }

    private void uploadEcho() throws Exception {
        mockMvc.perform(multipart("/api/plugins/upload")
                        .file(echoJar())
                        .param("secretScopes", "echo.")
                        .param("activate", "true")
                        .with(asAdmin())
                        .header("X-Actor", "integration-test"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pluginId").value("echo"))
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.loaded").value(true))
                .andExpect(jsonPath("$.nodeTypes[0]").value("ECHO"))
                .andExpect(jsonPath("$.sha256").isNotEmpty());
    }

    private String publishEchoWorkflow() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON).content(ECHO_WORKFLOW).with(asAdmin()))
                .andExpect(status().isCreated()).andReturn();
        String id = String.valueOf(com.orchpilot.workflow.sdk.json.Json
                .parseObject(created.getResponse().getContentAsString()).get("id"));
        mockMvc.perform(post("/api/workflows/{id}/publish", id).with(asAdmin())).andExpect(status().isOk());
        return id;
    }

    @Test
    @DisplayName("uploading a JAR makes its node type appear in the catalogue without a restart")
    void uploadedPluginAppearsInTheNodeCatalogue() throws Exception {
        mockMvc.perform(get("/api/nodes").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.nodeType=='ECHO')]").value(hasSize(0)));

        uploadEcho();

        mockMvc.perform(get("/api/nodes").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.nodeType=='ECHO')].source").value(contains("PLUGIN")))
                // A filter yields an array, so this asserts on the array. Indexing it as `.pluginId[0]` applies
                // the index to the string inside instead, which matches nothing however right the data is.
                .andExpect(jsonPath("$[?(@.nodeType=='ECHO')].pluginId").value(contains("echo")))
                .andExpect(jsonPath("$[?(@.nodeType=='ECHO')].pluginVersion").value(contains("1.0.0")));

        mockMvc.perform(get("/api/nodes/{type}", "ECHO").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("Testing"))
                .andExpect(jsonPath("$.configurationSchema.required[0]").value("message"));
    }

    @Test
    @DisplayName("a workflow using the uploaded node type executes end to end")
    void workflowUsingThePluginExecutes() throws Exception {
        uploadEcho();
        String workflowId = publishEchoWorkflow();

        mockMvc.perform(post("/api/workflows/{id}/execute", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"input\": { \"who\": \"world\" } }").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.output.echoed").value("hello world"));

        mockMvc.perform(get("/api/plugins/{id}/executions", "echo")
                        .with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].pluginId").value("echo"))
                .andExpect(jsonPath("$.content[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.content[0].nodeId").value("echo-1"));
    }

    @Test
    @DisplayName("deactivating a plugin makes workflows that depend on it fail with a clear error")
    void deactivatingBreaksDependentWorkflowsClearly() throws Exception {
        uploadEcho();
        String workflowId = publishEchoWorkflow();

        mockMvc.perform(post("/api/plugins/{id}/deactivate", "echo")
                        .param("version", "1.0.0")
                        .with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.loaded").value(false));

        mockMvc.perform(get("/api/nodes").with(asAdmin()))
                .andExpect(jsonPath("$[?(@.nodeType=='ECHO')]").value(hasSize(0)));

        mockMvc.perform(post("/api/workflows/{id}/execute", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"input\": { \"who\": \"world\" } }").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.code").value("PLUGIN_NOT_AVAILABLE"));
    }

    @Test
    @DisplayName("reactivating restores the node type and the workflow works again")
    void reactivatingRestoresTheNodeType() throws Exception {
        uploadEcho();
        String workflowId = publishEchoWorkflow();
        mockMvc.perform(post("/api/plugins/{id}/deactivate", "echo").param("version", "1.0.0")
                .with(asAdmin())).andExpect(status().isOk());

        mockMvc.perform(post("/api/plugins/{id}/activate", "echo").param("version", "1.0.0")
                        .with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loaded").value(true));

        mockMvc.perform(post("/api/workflows/{id}/execute", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"input\": { \"who\": \"again\" } }").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output.echoed").value("hello again"));
    }

    @Test
    @DisplayName("reloading a plugin keeps it usable")
    void reloadKeepsThePluginUsable() throws Exception {
        uploadEcho();
        String workflowId = publishEchoWorkflow();

        mockMvc.perform(post("/api/plugins/{id}/reload", "echo").param("version", "1.0.0")
                        .with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loaded").value(true));

        mockMvc.perform(post("/api/workflows/{id}/execute", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"input\": { \"who\": \"reloaded\" } }").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output.echoed").value("hello reloaded"));
    }

    @Test
    @DisplayName("publishing a workflow that references a missing plugin version is rejected")
    void workflowReferencingMissingPluginIsRejected() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ECHO_WORKFLOW.replace("\"1.0.0\"", "\"9.9.9\"")).with(asAdmin()))
                .andExpect(status().isCreated()).andReturn();
        String id = String.valueOf(com.orchpilot.workflow.sdk.json.Json
                .parseObject(created.getResponse().getContentAsString()).get("id"));

        mockMvc.perform(post("/api/workflows/{id}/publish", id).with(asAdmin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WORKFLOW_INVALID"))
                .andExpect(jsonPath("$.details[0]", containsString("9.9.9")));
    }

    @Test
    @DisplayName("plugin endpoints refuse an anonymous caller, and an authenticated one without the permission")
    void pluginEndpointsAreAuthorised() throws Exception {
        // Anonymous: refused before any authorisation decision is reached.
        mockMvc.perform(get("/api/plugins")).andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/api/plugins/upload").file(echoJar()))
                .andExpect(status().isUnauthorized());

        // Authenticated, but a USER holds no PLUGIN_* permission. Uploading a plugin runs third-party code in
        // this JVM, which is the most privileged thing the platform does, so this must be a 403 and not a 200.
        mockMvc.perform(get("/api/plugins").with(as("integration-user", com.orchpilot.workflow.auth.model.Role.USER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/plugins/upload").file(echoJar())
                        .with(as("integration-user", com.orchpilot.workflow.auth.model.Role.USER)))
                .andExpect(status().isForbidden());

        // The same user can still see workflows, which is a different permission entirely.
        mockMvc.perform(get("/api/workflows")
                        .with(as("integration-user", com.orchpilot.workflow.auth.model.Role.USER)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a file that is not a JAR is rejected before anything is loaded")
    void nonJarIsRejected() throws Exception {
        mockMvc.perform(multipart("/api/plugins/upload")
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain",
                                "not a jar".getBytes()))
                        .with(asAdmin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PLUGIN_INVALID"));
    }

    @Test
    @DisplayName("uploading the same version twice is rejected")
    void duplicateVersionIsRejected() throws Exception {
        uploadEcho();

        mockMvc.perform(multipart("/api/plugins/upload")
                        .file(echoJar())
                        .with(asAdmin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("already installed")));
    }

    @Test
    @DisplayName("deleting a plugin removes it and its stored JAR")
    void deleteRemovesThePlugin() throws Exception {
        uploadEcho();

        mockMvc.perform(delete("/api/plugins/{id}", "echo").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1));

        mockMvc.perform(get("/api/plugins/{id}", "echo").with(asAdmin()))
                .andExpect(status().isNotFound());
        // GridFS must not be left holding the binary.
        org.junit.jupiter.api.Assertions.assertEquals(0,
                mongoTemplate.getCollection("plugin_jars.files").countDocuments());
    }

    @Test
    @DisplayName("a secret can be stored and its value is never returned")
    void secretsAreWriteOnly() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/secrets/{name}", "echo.token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(asAdmin())
                        .content("{ \"value\": \"super-secret\", \"description\": \"test\" }"))
                .andExpect(status().isNoContent());

        MvcResult listed = mockMvc.perform(get("/api/secrets").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("echo.token"))
                .andReturn();

        org.junit.jupiter.api.Assertions.assertFalse(
                listed.getResponse().getContentAsString().contains("super-secret"),
                "no endpoint may return a secret value");
    }
}
