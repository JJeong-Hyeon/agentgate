package com.agentgate.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AgentStepTest {

    private static AgentStep parse(String output) {
        return AgentStep.parse(output, JsonMapper.builder().build());
    }

    @Test
    void readsTheModelCallOfATurn() {
        AgentStep step = parse("""
                {"agent":{"kind":"answer","agent":"a","llm":{"model":"qwen3","latency_ms":1800},
                 "tokens":{"input":503,"output":43}}}""");

        assertThat(step).isEqualTo(new AgentStep("a", "qwen3", 1800L, null, 503L, 43L, null, null, null));
        assertThat(step.hasLlmCall()).isTrue();
        assertThat(step.hasToolRun()).isFalse();
    }

    @Test
    void readsTheQueueWaitWhenTheRuntimeReportsIt() {
        AgentStep step = parse("""
                {"agent":{"kind":"answer","agent":"a","llm":{"model":"qwen3","latency_ms":1800,"queue_wait_ms":250},
                 "tokens":{"input":503,"output":43}}}""");

        assertThat(step).isEqualTo(new AgentStep("a", "qwen3", 1800L, 250L, 503L, 43L, null, null, null));
    }

    @Test
    void readsATimedToolRunWithoutTheNodePrefix() {
        AgentStep step = parse("""
                {"agent":{"kind":"result","agent":"a","tool":"helper:memory/save","status":"EXECUTED","duration_ms":5}}""");

        assertThat(step.toolName()).isEqualTo("memory/save");
        assertThat(step.toolStatus()).isEqualTo("EXECUTED");
        assertThat(step.toolDurationMs()).isEqualTo(5L);
        assertThat(step.hasLlmCall()).isFalse();
    }

    @Test
    void anythingElseIsNone() {
        assertThat(parse(null)).isEqualTo(AgentStep.NONE);
        assertThat(parse("{\"answer\":\"hi\"}")).isEqualTo(AgentStep.NONE);
        assertThat(parse("{\"agent\":{\"kind\":\"start\",\"prompt\":\"cut")).isEqualTo(AgentStep.NONE);
        // A result without timing (older runtimes) is not a tool run.
        assertThat(parse("{\"agent\":{\"kind\":\"result\",\"tool\":\"x:y\",\"status\":\"EXECUTED\"}}").hasToolRun())
                .isFalse();
    }
}
