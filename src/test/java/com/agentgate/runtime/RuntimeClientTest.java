package com.agentgate.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentgate.approval.domain.ApprovalStatus;
import com.agentgate.common.exception.InvalidWorkflowException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class RuntimeClientTest {

    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> token = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile int responseStatus = 202;
    private volatile String responseBody = null;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            token.set(exchange.getRequestHeaders().getFirst("X-Runtime-Token"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (responseBody == null) {
                exchange.sendResponseHeaders(responseStatus, -1);
            } else {
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
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

    @Test
    void validateWorkflowParsesRuntimeIssues() {
        responseStatus = 200;
        responseBody = """
                {"valid":false,"errors":[{"path":"nodes","message":"Not reachable from START","node_id":"a"}]}
                """;

        WorkflowValidation result = client().validateWorkflow(new JsonMapper().readTree("{\"nodes\":[]}"));

        assertThat(path.get()).isEqualTo("/runtime/workflows/validate");
        assertThat(body.get()).isEqualTo("{\"nodes\":[]}");
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).containsExactly(new WorkflowValidation.Issue("nodes", "Not reachable from START", "a"));
    }

    @Test
    void validateWorkflowFailsWhenRuntimeIsDown() {
        responseStatus = 500;

        assertThatThrownBy(() -> client().validateWorkflow(new JsonMapper().readTree("{}")))
                .isInstanceOf(RuntimeUnavailableException.class);
    }

    @Test
    void validateWorkflowFailsWhenRuntimeIsNotConfigured() {
        RuntimeClient disabled = new RuntimeClient(RestClient.builder(), "", "");

        assertThatThrownBy(() -> disabled.validateWorkflow(new JsonMapper().readTree("{}")))
                .isInstanceOf(RuntimeUnavailableException.class);
    }

    @Test
    void startExecutionRunsInBackgroundOnRuntime() {
        responseStatus = 202;

        client().startExecution("exec-1", "hello", new JsonMapper().readTree("{\"nodes\":[]}"));

        assertThat(path.get()).isEqualTo("/runtime/executions");
        assertThat(body.get()).contains("\"executionId\":\"exec-1\"").contains("\"background\":true")
                .contains("\"workflow\":{\"nodes\":[]}");
    }

    @Test
    void startExecutionRejectedWorkflowIsInvalid() {
        responseStatus = 422;
        responseBody = "{\"detail\":\"'ok': APPROVAL nodes are not supported yet\"}";

        assertThatThrownBy(() -> client().startExecution("e", "t", new JsonMapper().readTree("{}")))
                .isInstanceOf(InvalidWorkflowException.class)
                .hasMessageContaining("APPROVAL");
    }

    @Test
    void acceptsOnlyTheConfiguredToken() {
        assertThat(client().acceptsToken("secret")).isTrue();
        assertThat(client().acceptsToken("wrong")).isFalse();
        assertThat(client().acceptsToken(null)).isFalse();
        assertThat(new RuntimeClient(RestClient.builder(), "", "").acceptsToken("")).isFalse();
    }
}
