package com.agentgate.observability;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What an agent step the runtime reported cost: the model call behind a turn, and the tool it ran.
 * Read from a step's output ({@code {"agent": {...}}}); fields the step did not have are null.
 */
public record AgentStep(
        String agent,
        String llmModel,
        Long llmLatencyMs,
        Long llmQueueWaitMs,
        Long inputTokens,
        Long outputTokens,
        String toolName,
        String toolStatus,
        Long toolDurationMs
) {
    static final AgentStep NONE =
            new AgentStep(null, null, null, null, null, null, null, null, null);

    public boolean hasLlmCall() {
        return llmLatencyMs != null;
    }

    public boolean hasToolRun() {
        return toolDurationMs != null;
    }

    /** NONE for outputs that are not agent steps, or that the runtime cut off. */
    public static AgentStep parse(String output, ObjectMapper objectMapper) {
        if (output == null || !output.contains("\"agent\"")) {
            return NONE;
        }
        JsonNode trace;
        try {
            trace = objectMapper.readTree(output).get("agent");
        } catch (JacksonException e) {
            return NONE;
        }
        if (trace == null || !trace.isObject()) {
            return NONE;
        }
        String agent = text(trace, "agent");
        String model = null;
        Long latency = null;
        Long queueWait = null;
        Long input = null;
        Long outputTokens = null;
        JsonNode llm = trace.get("llm");
        if (llm != null && llm.isObject()) {
            model = text(llm, "model");
            latency = Math.max(llm.path("latency_ms").asLong(0), 0);
            if (llm.hasNonNull("queue_wait_ms")) {
                queueWait = Math.max(llm.path("queue_wait_ms").asLong(0), 0);
            }
            JsonNode tokens = trace.get("tokens");
            if (tokens != null && tokens.isObject()) {
                input = number(tokens, "input");
                outputTokens = number(tokens, "output");
            }
        }
        String tool = null;
        String toolStatus = null;
        Long toolDuration = null;
        if ("result".equals(text(trace, "kind")) && trace.hasNonNull("duration_ms")) {
            tool = toolName(text(trace, "tool"));
            toolStatus = text(trace, "status");
            toolDuration = Math.max(trace.path("duration_ms").asLong(0), 0);
        }
        return new AgentStep(
                agent, model, latency, queueWait, input, outputTokens, tool, toolStatus, toolDuration);
    }

    /** "notes/save_note" from "helper:notes/save_note": the node prefix is not part of the tool. */
    static String toolName(String recorded) {
        if (recorded == null) {
            return null;
        }
        String name = recorded.substring(recorded.indexOf(':') + 1);
        return name.isBlank() ? null : name;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString(null);
    }

    private static Long number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? null : value.asLong();
    }
}
