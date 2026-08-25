package com.orchpilot.workflow.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authoring, validation, publishing and execution over HTTP against a real MongoDB.
 */
class WorkflowLifecycleIT extends AbstractMongoIntegrationTest {

    private static final String APPROVAL_WORKFLOW = """
            {
              "name": "Employee Approval",
              "description": "Form, decision, end",
              "variables": { "threshold": 10000 },
              "nodes": [
                { "id": "start-1", "type": "START", "name": "Start",
                  "inputMapping": { "amount": "${input.amount}" } },
                { "id": "form-1", "type": "FORM", "name": "Approval Form", "formId": "employeeApproval",
                  "inputMapping": { "amount": "${amount}" },
                  "outputMapping": { "approved": "workflow.approved", "comments": "workflow.comments" } },
                { "id": "decision-1", "type": "DECISION", "name": "Approval Decision",
                  "conditions": [
                    { "branch": "approved", "expression": "approved == true" },
                    { "branch": "rejected", "expression": "approved == false" }
                  ],
                  "defaultBranch": "rejected" },
                { "id": "end-approved", "type": "END", "name": "Approved",
                  "configuration": { "resultStatus": "APPROVED",
                    "outputs": { "approved": "${workflow.approved}", "comments": "${workflow.comments}" } } },
                { "id": "end-rejected", "type": "END", "name": "Rejected",
                  "configuration": { "resultStatus": "REJECTED" } }
              ],
              "connections": [
                { "source": "start-1", "target": "form-1" },
                { "source": "form-1", "target": "decision-1" },
                { "source": "decision-1", "sourcePort": "approved", "target": "end-approved" },
                { "source": "decision-1", "sourcePort": "rejected", "target": "end-rejected" }
              ]
            }
            """;

    @BeforeEach
    void cleanUp() {
        clearCollections("workflows", "workflow_versions", "workflow_executions", "workflow_execution_logs");
    }

    private String createWorkflow(String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor", "integration-test")
                        .content(body).with(asAdmin()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        String id = com.orchpilot.workflow.sdk.json.Json
                .parseObject(result.getResponse().getContentAsString()).get("id").toString();
        assertNotNull(id);
        return id;
    }

    @Test
    @DisplayName("a workflow can be created, published and executed with the form supplied up front")
    void createPublishAndExecuteSynchronously() throws Exception {
        String id = createWorkflow(APPROVAL_WORKFLOW);

        mockMvc.perform(post("/api/workflows/{id}/publish", id).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(post("/api/workflows/{id}/execute", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "input": { "amount": 15000 },
                                  "formData": { "approved": true, "comments": "looks fine" } }
                                """).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.output.resultStatus").value("APPROVED"))
                .andExpect(jsonPath("$.output.comments").value("looks fine"))
                .andExpect(jsonPath("$.currentNodeId").value("end-approved"));
    }

    @Test
    @DisplayName("without form data the execution parks, and submitting the form resumes it to completion")
    void parksAtTheFormAndResumesOnSubmission() throws Exception {
        String id = createWorkflow(APPROVAL_WORKFLOW);
        mockMvc.perform(post("/api/workflows/{id}/publish", id).with(asAdmin())).andExpect(status().isOk());

        MvcResult started = mockMvc.perform(post("/api/workflows/{id}/execute", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"input\": { \"amount\": 15000 } }").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.pendingSignal.formId").value("employeeApproval"))
                .andExpect(jsonPath("$.pendingSignal.payload.prefill.amount").value(15000))
                .andReturn();

        String executionId = com.orchpilot.workflow.sdk.json.Json
                .parseObject(started.getResponse().getContentAsString()).get("executionId").toString();

        mockMvc.perform(get("/api/executions/{id}/pending", executionId).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value("form-1"));

        mockMvc.perform(post("/api/executions/{id}/form", executionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"data\": { \"approved\": false, \"comments\": \"not this time\" } }").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.output.resultStatus").value("REJECTED"))
                .andExpect(jsonPath("$.currentNodeId").value("end-rejected"));

        mockMvc.perform(get("/api/executions/{id}/logs", executionId).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].message", containsString("started")));
    }

    @Test
    @DisplayName("an unpublished workflow cannot be executed")
    void draftCannotBeExecuted() throws Exception {
        String id = createWorkflow(APPROVAL_WORKFLOW);

        mockMvc.perform(post("/api/workflows/{id}/execute", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}").with(asAdmin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    @DisplayName("publishing reports every structural problem at once")
    void publishReportsAllValidationProblems() throws Exception {
        String id = createWorkflow("""
                {
                  "name": "Broken",
                  "nodes": [
                    { "id": "start-1", "type": "START" },
                    { "id": "start-2", "type": "START" },
                    { "id": "orphan", "type": "DECISION",
                      "conditions": [ { "branch": "a", "expression": "T(java.lang.Runtime).getRuntime()" } ] }
                  ],
                  "connections": [ { "source": "start-1", "target": "nowhere" } ]
                }
                """);

        mockMvc.perform(post("/api/workflows/{id}/publish", id).with(asAdmin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WORKFLOW_INVALID"))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    @DisplayName("an asynchronous execution returns immediately and completes in the background")
    void asynchronousExecutionCompletes() throws Exception {
        String id = createWorkflow(APPROVAL_WORKFLOW);
        mockMvc.perform(post("/api/workflows/{id}/publish", id).with(asAdmin())).andExpect(status().isOk());

        MvcResult accepted = mockMvc.perform(post("/api/workflows/{id}/execute?async=true", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "input": { "amount": 15000 },
                                  "formData": { "approved": true, "comments": "async" } }
                                """).with(asAdmin()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionId").exists())
                .andReturn();

        String executionId = com.orchpilot.workflow.sdk.json.Json
                .parseObject(accepted.getResponse().getContentAsString()).get("executionId").toString();

        awaitStatus(executionId, "COMPLETED");
    }

    @Test
    @DisplayName("an idempotency key makes starting an execution repeatable")
    void idempotentStartReturnsTheSameExecution() throws Exception {
        String id = createWorkflow(APPROVAL_WORKFLOW);
        mockMvc.perform(post("/api/workflows/{id}/publish", id).with(asAdmin())).andExpect(status().isOk());

        String body = """
                { "input": { "amount": 1 },
                  "formData": { "approved": true },
                  "idempotencyKey": "order-4711" }
                """;

        MvcResult first = mockMvc.perform(post("/api/workflows/{id}/execute", id)
                        .contentType(MediaType.APPLICATION_JSON).content(body).with(asAdmin()))
                .andExpect(status().isOk()).andReturn();
        MvcResult second = mockMvc.perform(post("/api/workflows/{id}/execute", id)
                        .contentType(MediaType.APPLICATION_JSON).content(body).with(asAdmin()))
                .andExpect(status().isOk()).andReturn();

        Object firstId = com.orchpilot.workflow.sdk.json.Json
                .parseObject(first.getResponse().getContentAsString()).get("executionId");
        Object secondId = com.orchpilot.workflow.sdk.json.Json
                .parseObject(second.getResponse().getContentAsString()).get("executionId");
        org.junit.jupiter.api.Assertions.assertEquals(firstId, secondId,
                "a retried start must not create a second execution");
    }

    @Test
    @DisplayName("editing a published workflow returns it to draft while the published version stays executable")
    void editingReturnsToDraftWithoutBreakingThePublishedVersion() throws Exception {
        String id = createWorkflow(APPROVAL_WORKFLOW);
        mockMvc.perform(post("/api/workflows/{id}/publish", id).with(asAdmin())).andExpect(status().isOk());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/workflows/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(APPROVAL_WORKFLOW.replace("Employee Approval", "Employee Approval v2")).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.publishedVersion").value(1));

        mockMvc.perform(post("/api/workflows/{id}/execute", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"input\": {}, \"formData\": { \"approved\": true } }").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowVersion").value(1));
    }

    @Test
    @DisplayName("the node catalogue lists the four built-in types")
    void nodeCatalogueListsBuiltIns() throws Exception {
        mockMvc.perform(get("/api/nodes").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.nodeType=='START')].source").value(hasSize(1)))
                .andExpect(jsonPath("$[?(@.nodeType=='FORM')]").exists())
                .andExpect(jsonPath("$[?(@.nodeType=='DECISION')]").exists())
                .andExpect(jsonPath("$[?(@.nodeType=='END')]").exists());
    }

    /**
     * Polls until an execution reaches the expected status. Asynchronous work has no completion hook to await, and
     * sleeping a fixed interval either makes the test slow or makes it flaky.
     */
    private void awaitStatus(String executionId, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            MvcResult result = mockMvc.perform(get("/api/executions/{id}", executionId).with(asAdmin()))
                    .andExpect(status().isOk()).andReturn();
            last = String.valueOf(com.orchpilot.workflow.sdk.json.Json
                    .parseObject(result.getResponse().getContentAsString()).get("status"));
            if (expected.equals(last)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Execution " + executionId + " never reached " + expected
                + "; last status was " + last);
    }
}
