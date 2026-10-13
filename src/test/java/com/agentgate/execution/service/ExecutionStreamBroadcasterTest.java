package com.agentgate.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ExecutionStreamBroadcasterTest {

    private final ExecutionStreamBroadcaster broadcaster = new ExecutionStreamBroadcaster();
    private final AtomicInteger state = new AtomicInteger();

    @RestController
    class Stream {
        @GetMapping("/stream")
        SseEmitter stream() {
            return broadcaster.subscribe("exec", () -> {
                int snapshot = state.get();
                // Updates published while the snapshot is being prepared must still reach this subscriber.
                try {
                    Thread.sleep(3);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return snapshot;
            });
        }
    }

    /** A subscriber joining while updates are being applied sees a snapshot and then every later update, in order. */
    @RepeatedTest(10)
    void aSubscriberNeverMissesOrReordersUpdates() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new Stream()).build();
        state.set(0);
        CountDownLatch go = new CountDownLatch(1);
        int updates = 100;
        Thread writer = new Thread(() -> {
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (int i = 1; i <= updates; i++) {
                int value = i;
                broadcaster.sequenced("exec", () -> {
                    state.set(value);
                    broadcaster.publish("exec", "update", value);
                    return value;
                });
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        writer.start();
        go.countDown();

        MvcResult result = mockMvc.perform(get("/stream")).andExpect(request().asyncStarted()).andReturn();
        writer.join();

        List<Integer> received = new ArrayList<>();
        Matcher data = Pattern.compile("data:(\\d+)").matcher(result.getResponse().getContentAsString());
        while (data.find()) {
            received.add(Integer.parseInt(data.group(1)));
        }
        // The snapshot, then every following update with none skipped (a skipped one could be the one that matters).
        assertThat(received).isNotEmpty();
        for (int i = 1; i < received.size(); i++) {
            assertThat(received.get(i)).isEqualTo(received.get(i - 1) + 1);
        }
        assertThat(received.getLast()).isEqualTo(updates);
        broadcaster.complete("exec");
    }
}
