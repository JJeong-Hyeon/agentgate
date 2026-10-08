package com.agentgate.mcp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An MCP server the runtime may call, reached over Streamable HTTP. Its name is how agent
 * definitions and policies refer to it ({@code MCP:<name>:<tool>}), so it never changes.
 */
@Entity
@Table(name = "mcp_servers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class McpServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    @Column(nullable = false, length = 2000)
    private String url;

    @Column(length = 1000)
    private String description;

    private boolean enabled;

    // Encrypted JSON object of header name → value (SecretCipher); null when there are none.
    @Column(length = 8000)
    private String headersEncrypted;

    private Instant createdAt;

    private Instant updatedAt;

    public McpServer(String name, String url, String description, boolean enabled, String headersEncrypted) {
        this.name = name;
        this.url = url;
        this.description = description;
        this.enabled = enabled;
        this.headersEncrypted = headersEncrypted;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void update(String url, String description, boolean enabled) {
        this.url = url;
        this.description = description;
        this.enabled = enabled;
    }

    public void replaceHeaders(String headersEncrypted) {
        this.headersEncrypted = headersEncrypted;
    }
}
