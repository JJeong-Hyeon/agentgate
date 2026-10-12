package com.agentgate.memory.embedding;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Turns text into vectors with an OpenAI-compatible {@code /embeddings} endpoint (e.g. vLLM serving bge-m3).
 * Disabled unless both {@code agentgate.memory.embedding.base-url} and {@code .model} are set.
 */
@Component
public class EmbeddingClient {

    private final RestClient restClient;
    private final String model;
    private final boolean enabled;

    @Autowired
    public EmbeddingClient(@Value("${agentgate.memory.embedding.base-url:}") String baseUrl,
                           @Value("${agentgate.memory.embedding.model:}") String model,
                           @Value("${agentgate.memory.embedding.api-key:}") String apiKey) {
        this(RestClient.builder(), baseUrl, model, apiKey);
    }

    EmbeddingClient(RestClient.Builder builder, String baseUrl, String model, String apiKey) {
        this.enabled = !baseUrl.isBlank() && !model.isBlank();
        this.model = model;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        RestClient.Builder configured = builder
                .baseUrl(enabled ? baseUrl.replaceAll("/+$", "") : "http://embedding.disabled")
                .requestFactory(requestFactory);
        if (!apiKey.isBlank()) {
            configured.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        this.restClient = configured.build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String model() {
        return model;
    }

    /**
     * @return one vector per input, in order
     * @throws EmbeddingUnavailableException when disabled, unreachable or answering something unexpected
     */
    public List<float[]> embed(List<String> inputs) {
        if (!enabled) {
            throw new EmbeddingUnavailableException("Embedding model is not configured");
        }
        JsonNode response;
        try {
            response = restClient.post()
                    .uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model, "input", inputs))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new EmbeddingUnavailableException("Embedding request failed: " + e.getMessage(), e);
        }
        JsonNode data = response == null ? null : response.get("data");
        if (data == null || !data.isArray() || data.size() != inputs.size()) {
            throw new EmbeddingUnavailableException("Embedding response has no vector for every input");
        }
        List<float[]> vectors = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            vectors.add(null);
        }
        for (JsonNode item : data) {
            int index = item.path("index").asInt(-1);
            JsonNode embedding = item.get("embedding");
            if (index < 0 || index >= inputs.size() || embedding == null || !embedding.isArray() || embedding.isEmpty()) {
                throw new EmbeddingUnavailableException("Embedding response has an invalid item");
            }
            float[] vector = new float[embedding.size()];
            for (int j = 0; j < vector.length; j++) {
                vector[j] = (float) embedding.get(j).asDouble();
            }
            vectors.set(index, vector);
        }
        if (vectors.contains(null)) {
            throw new EmbeddingUnavailableException("Embedding response is missing an input");
        }
        return vectors;
    }

    public float[] embed(String input) {
        return embed(List.of(input)).getFirst();
    }
}
