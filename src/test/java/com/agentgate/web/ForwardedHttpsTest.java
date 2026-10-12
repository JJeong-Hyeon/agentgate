package com.agentgate.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

// Runs on a real server: forwarded headers are handled by Tomcat, which MockMvc bypasses.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ForwardedHttpsTest {

    @LocalServerPort
    private int port;

    private HttpResponse<Void> health(String forwardedProto) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"));
        if (forwardedProto != null) {
            request.header("X-Forwarded-Proto", forwardedProto);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.discarding());
    }

    @Test
    void requestsForwardedOverHttpsAreSecure() throws Exception {
        assertThat(health("https").headers().firstValue("Strict-Transport-Security")).isPresent();
    }

    @Test
    void plainHttpRequestsGetNoHsts() throws Exception {
        assertThat(health(null).headers().firstValue("Strict-Transport-Security")).isEmpty();
    }
}
