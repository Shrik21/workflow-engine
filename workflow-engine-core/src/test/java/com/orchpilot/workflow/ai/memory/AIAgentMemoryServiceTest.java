package com.orchpilot.workflow.ai.memory;

import com.orchpilot.workflow.ai.model.AIMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The execution-scoped memory store: it addresses a thread by execution id + key, keeps only user/assistant
 * turns, stays bounded, and never fails the caller when persistence hiccups.
 */
class AIAgentMemoryServiceTest {

    private final AIAgentMemoryRepository repository = mock(AIAgentMemoryRepository.class);
    private final AIAgentMemoryService service = new AIAgentMemoryService(repository);

    @Test
    void loadsPriorTurnsInOrder() {
        AIAgentMemory memory = new AIAgentMemory();
        memory.setTurns(List.of(new AIAgentMemory.Turn("USER", "hi"),
                new AIAgentMemory.Turn("ASSISTANT", "hello")));
        when(repository.findById("exec-1:chat")).thenReturn(Optional.of(memory));

        List<AIMessage> messages = service.load("exec-1", "chat");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo(AIMessage.Role.USER);
        assertThat(messages.get(1).role()).isEqualTo(AIMessage.Role.ASSISTANT);
        assertThat(messages.get(1).content()).isEqualTo("hello");
    }

    @Test
    void appendKeepsMemoryBounded() {
        AIAgentMemory memory = new AIAgentMemory();
        // Start already at the cap, so appending must drop the oldest to stay bounded.
        for (int i = 0; i < AIAgentMemoryService.MAX_TURNS; i++) {
            memory.getTurns().add(new AIAgentMemory.Turn("USER", "old-" + i));
        }
        when(repository.findById(anyString())).thenReturn(Optional.of(memory));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.append("exec-1", "chat", "newest question", "newest answer");

        ArgumentCaptor<AIAgentMemory> saved = ArgumentCaptor.forClass(AIAgentMemory.class);
        verify(repository).save(saved.capture());
        List<AIAgentMemory.Turn> turns = saved.getValue().getTurns();
        assertThat(turns).hasSize(AIAgentMemoryService.MAX_TURNS);
        assertThat(turns.get(turns.size() - 1).getContent()).isEqualTo("newest answer");
        assertThat(turns.get(turns.size() - 2).getContent()).isEqualTo("newest question");
        assertThat(turns.get(0).getContent()).isNotEqualTo("old-0");
    }

    @Test
    void aLoadFailureDegradesToEmptyRatherThanThrowing() {
        when(repository.findById(anyString())).thenThrow(new RuntimeException("mongo down"));

        assertThat(service.load("exec-1", "chat")).isEmpty();
    }
}
