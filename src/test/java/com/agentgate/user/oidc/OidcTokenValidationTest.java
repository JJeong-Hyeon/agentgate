package com.agentgate.user.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * What Spring Security's ID token check does for the registration AgentGate builds: only a token
 * signed with a key the IdP publishes, for this issuer and this client, and not expired, gets in.
 * Whatever claims such a token carries (the groups) are then trusted, so this is what stops
 * someone from writing "agentgate-admins" into a token themselves.
 */
class OidcTokenValidationTest {

    private static final String CLIENT_ID = "agentgate";

    private HttpServer jwksServer;
    private String issuer;
    private final AtomicReference<JWKSet> published = new AtomicReference<>();
    private RSAKey idpKey;
    private JwtDecoder decoder;

    @BeforeEach
    void startIdp() throws Exception {
        idpKey = new RSAKeyGenerator(2048).keyID("idp-key-1").generate();
        published.set(new JWKSet(idpKey.toPublicJWK()));
        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwksServer.createContext("/certs", exchange -> {
            byte[] body = published.get().toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        jwksServer.start();
        String base = "http://127.0.0.1:" + jwksServer.getAddress().getPort();
        issuer = base + "/realms/test";
        ClientRegistration registration = ClientRegistration.withRegistrationId("oidc")
                .clientId(CLIENT_ID).clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid")
                .authorizationUri(issuer + "/auth").tokenUri(issuer + "/token")
                .jwkSetUri(base + "/certs").issuerUri(issuer)
                .userNameAttributeName("preferred_username")
                .build();
        decoder = new OidcIdTokenDecoderFactory().createDecoder(registration);
    }

    @AfterEach
    void stopIdp() {
        jwksServer.stop(0);
    }

    private JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
                .issuer(issuer).subject("user-1").audience(CLIENT_ID)
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("preferred_username", "hana")
                .claim("groups", List.of("sales"));
    }

    private static String sign(RSAKey key, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(),
                claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test
    void aTokenSignedWithThePublishedKeyIsAccepted() throws Exception {
        Jwt jwt = decoder.decode(sign(idpKey, claims().build()));

        assertThat(jwt.getClaimAsString("preferred_username")).isEqualTo("hana");
        assertThat(jwt.getClaimAsStringList("groups")).containsExactly("sales");
    }

    @Test
    void aTokenSignedByAnyoneElseIsRefusedEvenIfItClaimsToBeAdmin() throws Exception {
        RSAKey attacker = new RSAKeyGenerator(2048).keyID("idp-key-1").generate();  // same key id, other key
        String forged = sign(attacker, claims().claim("groups", List.of("agentgate-admins")).build());

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void changingTheClaimsAfterSigningBreaksTheSignature() throws Exception {
        String[] parts = sign(idpKey, claims().build()).split("\\.");
        String adminClaims = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                claims().claim("groups", List.of("agentgate-admins")).build().toString()
                        .getBytes(StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + adminClaims + "." + parts[2];

        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void anUnsignedTokenIsRefused() {
        String unsigned = new PlainJWT(claims().claim("groups", List.of("agentgate-admins")).build()).serialize();

        assertThatThrownBy(() -> decoder.decode(unsigned)).isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenFromAnotherIssuerOrForAnotherClientOrExpiredIsRefused() throws Exception {
        String otherIssuer = sign(idpKey, claims().issuer("http://evil.example/realms/test").build());
        String otherClient = sign(idpKey, claims().audience("some-other-app").build());
        String expired = sign(idpKey, claims().issueTime(Date.from(Instant.now().minusSeconds(7200)))
                .expirationTime(Date.from(Instant.now().minusSeconds(3600))).build());

        assertThatThrownBy(() -> decoder.decode(otherIssuer)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(otherClient)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(expired)).isInstanceOf(JwtException.class);
    }

    @Test
    void aNewKeyThePublishedKeySetGainsIsPickedUpWithoutARestart() throws Exception {
        RSAKey rotated = new RSAKeyGenerator(2048).keyID("idp-key-2").generate();
        String signedWithNewKey = sign(rotated, claims().build());
        assertThatThrownBy(() -> decoder.decode(signedWithNewKey)).isInstanceOf(JwtException.class);  // not published yet

        published.set(new JWKSet(List.of(idpKey.toPublicJWK(), rotated.toPublicJWK())));

        assertThat(decoder.decode(signedWithNewKey).getClaimAsString("preferred_username")).isEqualTo("hana");
        // and the old key still works while both are published
        assertThat(decoder.decode(sign(idpKey, claims().build())).getSubject()).isEqualTo("user-1");
    }
}
