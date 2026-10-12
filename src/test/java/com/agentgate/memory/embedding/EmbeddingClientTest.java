package com.agentgate.memory.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class EmbeddingClientTest {

    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private EmbeddingClient client(String apiKey) {
        return new EmbeddingClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
                "bge-m3", apiKey);
    }

    @Test
    void disabledWithoutUrlOrModel() {
        assertThat(new EmbeddingClient(RestClient.builder(), "", "bge-m3", "").isEnabled()).isFalse();
        assertThat(new EmbeddingClient(RestClient.builder(), "http://x/v1", "", "").isEnabled()).isFalse();
        assertThatThrownBy(() -> new EmbeddingClient(RestClient.builder(), "", "", "").embed("x"))
                .isInstanceOf(EmbeddingUnavailableException.class);
    }

    @Test
    void returnsVectorsInInputOrder() {
        responseBody = """
                {"data":[{"index":1,"embedding":[0.5,0.25]},{"index":0,"embedding":[1,0]}]}""";

        List<float[]> vectors = client("secret").embed(List.of("first", "second"));

        assertThat(path.get()).isEqualTo("/v1/embeddings");
        assertThat(body.get()).contains("\"model\":\"bge-m3\"", "\"input\":[\"first\",\"second\"]");
        assertThat(authorization.get()).isEqualTo("Bearer secret");
        assertThat(vectors.get(0)).containsExactly(1f, 0f);
        assertThat(vectors.get(1)).containsExactly(0.5f, 0.25f);
    }

    @Test
    void failsOnErrorsAndIncompleteResponses() {
        responseStatus = 503;
        assertThatThrownBy(() -> client("").embed("x")).isInstanceOf(EmbeddingUnavailableException.class);

        responseStatus = 200;
        responseBody = "{\"data\":[]}";
        assertThatThrownBy(() -> client("").embed("x")).isInstanceOf(EmbeddingUnavailableException.class);
        assertThat(authorization.get()).isNull();
    }
}
