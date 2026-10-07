package com.agentgate.runtime;

import com.agentgate.approval.domain.ApprovalStatus;
import java.time.Duration;
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
    private final boolean enabled;

    @Autowired
    public RuntimeClient(@Value("${agentgate.runtime.base-url:}") String baseUrl,
                         @Value("${agentgate.runtime.token:}") String token) {
        this(RestClient.builder(), baseUrl, token);
    }

    RuntimeClient(RestClient.Builder builder, String baseUrl, String token) {
        this.enabled = !baseUrl.isBlank();
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
    }

    public boolean isEnabled() {
        return enabled;
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
}
