package com.agentgate.approval.notification;

import com.agentgate.approval.domain.ApprovalRequest;
import com.agentgate.approval.dto.ApprovalResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

/**
 * Posts new approval requests to a chat or HTTP webhook. Disabled when
 * {@code agentgate.notifications.webhook.url} is blank.
 * <ul>
 *   <li>{@code SLACK}: {@code {"text": ...}}, understood by Slack, Mattermost and Dooray incoming webhooks</li>
 *   <li>{@code JSON}: the approval itself; signed with HMAC-SHA256 in {@code X-AgentGate-Signature} when a secret is set</li>
 * </ul>
 */
@Component
public class ApprovalWebhook {

    public enum Format { SLACK, JSON }

    static final String SIGNATURE_HEADER = "X-AgentGate-Signature";

    private static final Logger log = LoggerFactory.getLogger(ApprovalWebhook.class);

    private final RestClient restClient;
    private final String url;
    private final Format format;
    private final String secret;
    private final String publicUrl;
    private final ObjectMapper objectMapper;

    @Autowired
    public ApprovalWebhook(@Value("${agentgate.notifications.webhook.url:}") String url,
                           @Value("${agentgate.notifications.webhook.format:SLACK}") String format,
                           @Value("${agentgate.notifications.webhook.secret:}") String secret,
                           @Value("${agentgate.public-url:}") String publicUrl,
                           ObjectMapper objectMapper) {
        this(RestClient.builder(), url, format, secret, publicUrl, objectMapper);
    }

    ApprovalWebhook(RestClient.Builder builder, String url, String format, String secret, String publicUrl,
                    ObjectMapper objectMapper) {
        this.url = url.isBlank() ? null : url;
        this.format = Format.valueOf(format.trim().toUpperCase(Locale.ROOT));
        this.secret = secret;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = builder.requestFactory(requestFactory).build();
    }

    public boolean isEnabled() {
        return url != null;
    }

    /** @return true when the webhook accepted the notification; false when it should be retried */
    public boolean send(ApprovalRequest approval) {
        String body = body(approval);
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON);
            if (format == Format.JSON && !secret.isBlank()) {
                request.header(SIGNATURE_HEADER, "sha256=" + sign(body));
            }
            request.body(body).retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            log.warn("Failed to notify approvers of approval {}; will retry", approval.getId(), e);
            return false;
        }
    }

    String body(ApprovalRequest approval) {
        if (format == Format.SLACK) {
            return objectMapper.writeValueAsString(Map.of("text", text(approval)));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "approval.requested");
        payload.put("approval", ApprovalResponse.from(approval));
        payload.put("url", link(approval));
        return objectMapper.writeValueAsString(payload);
    }

    private String text(ApprovalRequest approval) {
        StringBuilder text = new StringBuilder()
                .append("[AgentGate] 승인 요청 #").append(approval.getId())
                .append(" (").append(approval.getRiskLevel()).append(")\n")
                .append("Agent ").append(approval.getAgentId()).append(": ").append(approval.getAction());
        if (approval.getTarget() != null) {
            text.append(" → ").append(approval.getTarget());
        }
        if (approval.getDelegatedBy() != null) {
            text.append("\n위임 경로: ").append(approval.getDelegatedBy().replace(">", " → "))
                    .append(" → ").append(approval.getAgentId());
        }
        if (!approval.getLabels().isEmpty()) {
            text.append("\n라벨: ").append(String.join(", ", approval.getLabels()));
        }
        if (approval.getReason() != null) {
            text.append("\n사유: ").append(approval.getReason());
        }
        String link = link(approval);
        if (link != null) {
            text.append('\n').append(link);
        }
        return text.toString();
    }

    private String link(ApprovalRequest approval) {
        if (publicUrl.isBlank()) {
            return null;
        }
        return approval.getExecutionId() != null
                ? publicUrl + "/executions/" + approval.getExecutionId()
                : publicUrl + "/approvals";
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
