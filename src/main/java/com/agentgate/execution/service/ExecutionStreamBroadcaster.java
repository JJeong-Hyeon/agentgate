package com.agentgate.execution.service;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes execution updates to SSE subscribers. Subscribers live in this instance's memory, so with several
 * AgentGate instances a subscriber only sees events received by its own instance.
 */
@Component
public class ExecutionStreamBroadcaster {

    private static final long TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

    private static final int STRIPES = 64;

    private final Map<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    // Per execution (striped by hash): reading a snapshot and subscribing must not interleave with applying an
    // event and publishing it, or a subscriber misses the event or gets an older snapshot after a newer update.
    private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

    public ExecutionStreamBroadcaster() {
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /** Runs {@code action} (typically: apply an event, then publish it) exclusive with {@link #subscribe}. */
    public <T> T sequenced(String executionId, Supplier<T> action) {
        ReentrantLock lock = lockFor(executionId);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** Subscribes with the snapshot read under the lock, so no update falls between the snapshot and the stream. */
    public SseEmitter subscribe(String executionId, Supplier<?> snapshot) {
        return sequenced(executionId, () -> {
            Object current = snapshot.get();
            SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
            List<SseEmitter> emitters = subscribers.computeIfAbsent(executionId, id -> new CopyOnWriteArrayList<>());
            emitters.add(emitter);
            Runnable remove = () -> emitters.remove(emitter);
            emitter.onCompletion(remove);
            emitter.onTimeout(remove);
            emitter.onError(e -> remove.run());
            send(emitter, "snapshot", current);
            return emitter;
        });
    }

    private ReentrantLock lockFor(String executionId) {
        return locks[Math.floorMod(executionId.hashCode(), STRIPES)];
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
