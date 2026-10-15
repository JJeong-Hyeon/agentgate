package com.agentgate.approval.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentgate.approval.domain.ApprovalPayload;
import com.agentgate.approval.domain.ApprovalRequest;
import com.agentgate.risk.RiskLevel;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ApprovalWebhookTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> signature = new AtomicReference<>();
    private volatile int responseStatus = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            signature.set(exchange.getRequestHeaders().getFirst(ApprovalWebhook.SIGNATURE_HEADER));
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ApprovalWebhook webhook(String format, String secret, String publicUrl) {
        return new ApprovalWebhook(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort() + "/hook",
                format, secret, publicUrl, mapper);
    }

    private static ApprovalRequest approval() {
        ApprovalRequest approval = new ApprovalRequest("mail-agent", "SEND_EMAIL", "ceo@example.com", List.of("PII"),
                RiskLevel.HIGH, "exec-1", "고객 명단 발송", "lead");
        ReflectionTestUtils.setField(approval, "id", 12L);
        return approval;
    }

    @Test
    void disabledWithoutUrl() {
        assertThat(new ApprovalWebhook(RestClient.builder(), "", "SLACK", "", "", mapper).isEnabled()).isFalse();
    }

    @Test
    void slackFormatSendsReadableTextWithConsoleLink() {
        boolean delivered = webhook("slack", "", "https://agentgate.example.com/").send(approval());

        assertThat(delivered).isTrue();
        String text = mapper.readTree(body.get()).get("text").asString();
        assertThat(text).contains("승인 요청 #12 (HIGH)", "mail-agent: SEND_EMAIL → ceo@example.com",
                "위임 경로: lead → mail-agent", "라벨: PII", "사유: 고객 명단 발송",
                "https://agentgate.example.com/executions/exec-1");
        assertThat(signature.get()).isNull();
    }

    @Test
    void jsonFormatSendsApprovalSignedWithSecret() throws Exception {
        boolean delivered = webhook("JSON", "s3cret", "").send(approval());

        assertThat(delivered).isTrue();
        JsonNode payload = mapper.readTree(body.get());
        assertThat(payload.get("event").asString()).isEqualTo("approval.requested");
        assertThat(payload.get("approval").get("id").asLong()).isEqualTo(12L);
        assertThat(payload.get("approval").get("status").asString()).isEqualTo("PENDING");
        assertThat(payload.get("url").isNull()).isTrue();

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("s3cret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal(body.get().getBytes(StandardCharsets.UTF_8)));
        assertThat(signature.get()).isEqualTo("sha256=" + expected);
    }

    @Test
    void secretArgumentsInTheReasonNeverLeaveInEitherFormat() throws Exception {
        ApprovalRequest leaky = new ApprovalRequest("mail-agent", "SEND_EMAIL", null, List.of(), RiskLevel.HIGH,
                "exec-1", "Agent 'mail-agent' wants to call mail/send with {\"to\": \"kim\", \"api_key\": \"sk-123\"}",
                null, new ApprovalPayload("h", "{\"token\": \"t-456\"}"));
        ReflectionTestUtils.setField(leaky, "id", 13L);

        webhook("SLACK", "", "").send(leaky);
        String slack = mapper.readTree(body.get()).get("text").asString();
        webhook("JSON", "", "").send(leaky);
        String json = body.get();

        assertThat(slack).contains("\"to\": \"kim\"", "\"api_key\": \"***\"").doesNotContain("sk-123");
        assertThat(json).contains("***").doesNotContain("sk-123").doesNotContain("t-456");
    }

    @Test
    void failedDeliveryIsReportedForRetry() {
        responseStatus = 500;

        assertThat(webhook("SLACK", "", "").send(approval())).isFalse();
    }
}
