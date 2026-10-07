package com.agentgate.approval.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentgate.approval.domain.ApprovalStatus;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class RuntimeClientTest {

    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> token = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile int responseStatus = 202;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            token.set(exchange.getRequestHeaders().getFirst("X-Runtime-Token"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private RuntimeClient client() {
        return new RuntimeClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(), "secret");
    }

    @Test
    void sendsResumeWithTokenAndDecision() {
        boolean delivered = client().resume("exec-1", 7L, ApprovalStatus.APPROVED);

        assertThat(delivered).isTrue();
        assertThat(path.get()).isEqualTo("/runtime/executions/exec-1/resume");
        assertThat(token.get()).isEqualTo("secret");
        assertThat(body.get()).contains("\"approvalId\":7").contains("\"decision\":\"APPROVED\"");
    }

    @Test
    void conflictCountsAsDelivered() {
        responseStatus = 409;

        assertThat(client().resume("exec-1", 7L, ApprovalStatus.REJECTED)).isTrue();
    }

    @Test
    void serverErrorShouldBeRetried() {
        responseStatus = 500;

        assertThat(client().resume("exec-1", 7L, ApprovalStatus.APPROVED)).isFalse();
    }

    @Test
    void unreachableRuntimeShouldBeRetried() {
        RuntimeClient unreachable = new RuntimeClient(RestClient.builder(), "http://127.0.0.1:1", "secret");

        assertThat(unreachable.resume("exec-1", 7L, ApprovalStatus.APPROVED)).isFalse();
    }

    @Test
    void disabledWithoutBaseUrl() {
        assertThat(new RuntimeClient(RestClient.builder(), "", "").isEnabled()).isFalse();
    }

    @Test
    void baseUrlWithoutTokenIsRejected() {
        assertThatThrownBy(() -> new RuntimeClient(RestClient.builder(), "http://runtime:8000", ""))
                .isInstanceOf(IllegalStateException.class);
    }
}
