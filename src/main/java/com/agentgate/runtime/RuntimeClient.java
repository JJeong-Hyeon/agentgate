package com.agentgate.runtime;

import com.agentgate.approval.domain.ApprovalStatus;
import com.agentgate.common.exception.InvalidWorkflowException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Calls the Python agent runtime: resuming executions paused for approval and validating workflows.
 * Disabled when {@code agentgate.runtime.base-url} is blank.
 */
@Component
public class RuntimeClient {

    private static final Logger log = LoggerFactory.getLogger(RuntimeClient.class);

    private final RestClient restClient;
    // Listing tools may start stdio MCP servers, which can take a while.
    private final RestClient slowRestClient;
    private final boolean enabled;
    private final String token;

    @Autowired
    public RuntimeClient(@Value("${agentgate.runtime.base-url:}") String baseUrl,
                         @Value("${agentgate.runtime.token:}") String token) {
        this(RestClient.builder(), baseUrl, token);
    }

    RuntimeClient(RestClient.Builder builder, String baseUrl, String token) {
        this.enabled = !baseUrl.isBlank();
        this.token = token;
        if (enabled && token.isBlank()) {
            throw new IllegalStateException("agentgate.runtime.token must be set when agentgate.runtime.base-url is set");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = builder
                .baseUrl(enabled ? baseUrl : "http://runtime.disabled")
                .defaultHeader("X-Runtime-Token", token)
                .requestFactory(requestFactory)
                .build();
        SimpleClientHttpRequestFactory slowRequestFactory = new SimpleClientHttpRequestFactory();
        slowRequestFactory.setConnectTimeout(Duration.ofSeconds(2));
        slowRequestFactory.setReadTimeout(Duration.ofSeconds(35));
        this.slowRestClient = restClient.mutate().requestFactory(slowRequestFactory).build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Whether {@code presented} is the shared token the runtime sends with its calls to AgentGate. */
    public boolean acceptsToken(String presented) {
        return !token.isBlank() && presented != null
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @return true when there is nothing left to deliver (resumed, already resumed, or unknown to the runtime);
     *         false when the call should be retried.
     */
    public boolean resume(String executionId, Long approvalId, ApprovalStatus decision) {
        try {
            restClient.post()
                    .uri("/runtime/executions/{id}/resume", executionId)
                    .body(Map.of("approvalId", approvalId, "decision", decision.name()))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.Conflict | HttpClientErrorException.NotFound e) {
            log.info("Runtime did not resume execution {} for approval {}: {}", executionId, approvalId, e.getStatusCode());
            return true;
        } catch (RestClientException e) {
            log.warn("Failed to resume execution {} for approval {}; will retry", executionId, approvalId, e);
            return false;
        }
    }

    /**
     * Validates a Workflow DSL document with the runtime, which owns the DSL schema.
     *
     * @throws RuntimeUnavailableException when the runtime is disabled or cannot answer
     */
    public WorkflowValidation validateWorkflow(JsonNode dsl) {
        if (!enabled) {
            throw new RuntimeUnavailableException("Agent runtime is not configured");
        }
        try {
            return restClient.post()
                    .uri("/runtime/workflows/validate")
                    .body(dsl)
                    .retrieve()
                    .body(WorkflowValidation.class);
        } catch (RestClientException e) {
            log.warn("Workflow validation failed", e);
            throw new RuntimeUnavailableException("Agent runtime could not validate the workflow");
        }
    }

    /**
     * Starts an execution in the background on the runtime; progress comes back as execution events.
     *
     * @throws InvalidWorkflowException when the runtime cannot run the workflow (e.g. unsupported node)
     * @throws RuntimeUnavailableException when the runtime is disabled or cannot answer
     */
    public void startExecution(String executionId, String task, JsonNode workflow) {
        if (!enabled) {
            throw new RuntimeUnavailableException("Agent runtime is not configured");
        }
        try {
            restClient.post()
                    .uri("/runtime/executions")
                    .body(Map.of("executionId", executionId, "task", task, "workflow", workflow, "background", true))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.UnprocessableContent e) {
            throw new InvalidWorkflowException("Runtime cannot run the workflow: " + e.getResponseBodyAsString(),
                    List.of());
        } catch (RestClientException e) {
            log.warn("Failed to start execution {}", executionId, e);
            throw new RuntimeUnavailableException("Agent runtime could not start the execution");
        }
    }

    /**
     * The MCP servers configured in the runtime and their tools.
     *
     * @param refresh bypass the runtime's short-lived cache
     * @throws RuntimeUnavailableException when the runtime is disabled or cannot answer
     */
    public List<McpServerTools> listTools(boolean refresh) {
        if (!enabled) {
            throw new RuntimeUnavailableException("Agent runtime is not configured");
        }
        try {
            McpServerTools[] servers = slowRestClient.get()
                    .uri(uri -> uri.path("/runtime/tools").queryParam("refresh", refresh).build())
                    .retrieve()
                    .body(McpServerTools[].class);
            return servers == null ? List.of() : List.of(servers);
        } catch (RestClientException e) {
            log.warn("Failed to list runtime tools", e);
            throw new RuntimeUnavailableException("Agent runtime could not list its tools");
        }
    }
}
