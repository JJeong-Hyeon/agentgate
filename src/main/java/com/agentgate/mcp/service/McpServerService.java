package com.agentgate.mcp.service;

import com.agentgate.common.exception.DuplicateMcpServerException;
import com.agentgate.common.exception.McpServerNotFoundException;
import com.agentgate.common.security.SecretCipher;
import com.agentgate.mcp.domain.McpServer;
import com.agentgate.mcp.dto.McpServerRequest;
import com.agentgate.mcp.dto.McpServerResponse;
import com.agentgate.mcp.dto.RuntimeMcpServer;
import com.agentgate.mcp.repository.McpServerRepository;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class McpServerService {

    private final McpServerRepository repository;
    private final SecretCipher cipher;
    private final ObjectMapper objectMapper;

    @Transactional
    public McpServerResponse create(McpServerRequest request) {
        if (repository.existsByName(request.name())) {
            throw new DuplicateMcpServerException(request.name());
        }
        McpServer server = new McpServer(request.name(), request.url(), request.description(),
                request.enabledOrDefault(), seal(request.headers()));
        try {
            return toResponse(repository.saveAndFlush(server));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateMcpServerException(request.name());
        }
    }

    @Transactional(readOnly = true)
    public List<McpServerResponse> list() {
        return repository.findAllByOrderByNameAsc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public McpServerResponse get(Long id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public McpServerResponse update(Long id, McpServerRequest request) {
        McpServer server = findOrThrow(id);
        server.update(request.url(), request.description(), request.enabledOrDefault());
        if (request.headers() != null) {
            server.replaceHeaders(seal(request.headers()));
        }
        return toResponse(server);
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(findOrThrow(id));
    }

    /** Enabled servers with decrypted headers, for the runtime. */
    @Transactional(readOnly = true)
    public List<RuntimeMcpServer> forRuntime() {
        return repository.findByEnabledTrueOrderByNameAsc().stream()
                .map(s -> new RuntimeMcpServer(s.getName(), s.getUrl(), open(s.getHeadersEncrypted())))
                .toList();
    }

    private McpServer findOrThrow(Long id) {
        return repository.findById(id).orElseThrow(() -> new McpServerNotFoundException(id));
    }

    private McpServerResponse toResponse(McpServer server) {
        return new McpServerResponse(server.getId(), server.getName(), server.getUrl(), server.getDescription(),
                server.isEnabled(), List.copyOf(open(server.getHeadersEncrypted()).keySet()),
                server.getCreatedAt(), server.getUpdatedAt());
    }

    private String seal(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        return cipher.encrypt(objectMapper.writeValueAsString(new TreeMap<>(headers)));
    }

    private Map<String, String> open(String sealed) {
        if (sealed == null) {
            return Map.of();
        }
        return objectMapper.readValue(cipher.decrypt(sealed), new TypeReference<TreeMap<String, String>>() {
        });
    }
}
