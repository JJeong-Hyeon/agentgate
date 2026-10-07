package com.agentgate.execution.service;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes execution updates to SSE subscribers. Subscribers live in this instance's memory, so with several
 * AgentGate instances a subscriber only sees events received by its own instance.
 */
@Component
public class ExecutionStreamBroadcaster {

    private static final long TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

    private final Map<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String executionId, Object snapshot) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        List<SseEmitter> emitters = subscribers.computeIfAbsent(executionId, id -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);
        Runnable remove = () -> emitters.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        send(emitter, "snapshot", snapshot);
        return emitter;
    }

    public void publish(String executionId, String eventName, Object data) {
        for (SseEmitter emitter : subscribers.getOrDefault(executionId, List.of())) {
            send(emitter, eventName, data);
        }
    }

    public void complete(String executionId) {
        List<SseEmitter> emitters = subscribers.remove(executionId);
        if (emitters != null) {
            emitters.forEach(SseEmitter::complete);
        }
    }

    private static void send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }
}
