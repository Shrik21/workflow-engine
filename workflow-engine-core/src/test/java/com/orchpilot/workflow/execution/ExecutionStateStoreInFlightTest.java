package com.orchpilot.workflow.execution;

import com.mongodb.client.MongoCollection;
import com.orchpilot.workflow.model.WorkflowExecution;
import com.orchpilot.workflow.repository.WorkflowExecutionRepository;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The progress markers, and the one property that makes them safe: they must not disturb the document version.
 *
 * <h2>The regression these pin</h2>
 *
 * {@code mongoTemplate.updateFirst(query, update, WorkflowExecution.class)} looks harmless but is not: Spring
 * Data appends {@code $inc: {version: 1}} to any entity-aware update on a {@code @Version} class
 * ({@code QueryOperations.increaseVersionForUpdateIfNecessary}). Because the engine loop marks a node in flight
 * immediately before running it, while holding the document at version N, that bump made the loop's next
 * {@code save()} fail with an optimistic-lock error — the loop stood down and the execution sat in
 * {@code RUNNING} for ever with its next node never run. Writing through the raw collection avoids entity
 * mapping entirely, which is what these tests assert.
 */
class ExecutionStateStoreInFlightTest {

    private final WorkflowExecutionRepository repository = mock(WorkflowExecutionRepository.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    @SuppressWarnings("unchecked")
    private final MongoCollection<Document> collection = mock(MongoCollection.class);
    private final ExecutionStateStore store = new ExecutionStateStore(repository, mongoTemplate);

    private void stubRawCollection() {
        lenient().when(mongoTemplate.getCollectionName(WorkflowExecution.class)).thenReturn("workflow_executions");
        lenient().when(mongoTemplate.getCollection("workflow_executions")).thenReturn(collection);
    }

    @Test
    void writesTheInFlightNodeThroughTheRawCollectionSoTheVersionIsUntouched() {
        stubRawCollection();
        Instant startedAt = Instant.parse("2026-01-01T10:00:00Z");

        store.markNodeInFlight("exec-1", "ai-1", "AI_AGENT", startedAt);

        ArgumentCaptor<Document> update = ArgumentCaptor.forClass(Document.class);
        verify(collection).updateOne(any(Document.class), update.capture());

        Document set = update.getValue().get("$set", Document.class);
        assertThat(set.get("currentNodeId")).isEqualTo("ai-1");
        assertThat(set.get("currentNodeType")).isEqualTo("AI_AGENT");
        assertThat(set.get("currentNodeStartedAt")).isEqualTo(startedAt);

        // The whole point: no $inc, and nothing touching the version field.
        assertThat(update.getValue()).doesNotContainKey("$inc");
        assertThat(set).doesNotContainKey("version");

        // And it must never go through the entity-aware path, which is what silently added the $inc.
        verify(mongoTemplate, never()).updateFirst(any(), any(Update.class), eq(WorkflowExecution.class));
    }

    @Test
    void theHeartbeatIsEquallyVersionSafe() {
        stubRawCollection();

        store.heartbeat("exec-1");

        ArgumentCaptor<Document> update = ArgumentCaptor.forClass(Document.class);
        verify(collection).updateOne(any(Document.class), update.capture());
        assertThat(update.getValue()).doesNotContainKey("$inc");
        assertThat(update.getValue().get("$set", Document.class)).containsKey("heartbeatAt");
        verify(mongoTemplate, never()).updateFirst(any(), any(Update.class), eq(WorkflowExecution.class));
    }

    @Test
    void aStoreFailureDoesNotBreakTheExecution() {
        stubRawCollection();
        doThrow(new RuntimeException("mongo down"))
                .when(collection).updateOne(any(Document.class), any(Document.class));

        // Must not throw: visibility is best-effort, the run carries on.
        store.markNodeInFlight("exec-1", "ai-1", "AI_AGENT", Instant.now());
        store.heartbeat("exec-1");
    }
}
