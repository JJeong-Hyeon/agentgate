package com.agentgate.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.agentgate.agent.domain.Agent;
import com.agentgate.agent.dto.AgentCreateRequest;
import com.agentgate.agent.dto.AgentCreateResponse;
import com.agentgate.agent.repository.AgentRepository;
import com.agentgate.common.exception.AgentNotFoundException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentManagementServiceTest {

    @Mock
    private AgentRepository agentRepository;

    private AgentManagementService service;

    @BeforeEach
    void setUp() {
        service = new AgentManagementService(agentRepository);
    }

    @Test
    void createGeneratesApiKeyAndReturnsItOnlyOnce() {
        when(agentRepository.save(any(Agent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentCreateResponse response = service.create(new AgentCreateRequest("new-agent", "New Agent"));

        assertThat(response.agentId()).isEqualTo("new-agent");
        assertThat(response.apiKey()).isNotBlank();
    }

    @Test
    void listReturnsAllAgents() {
        when(agentRepository.findAll()).thenReturn(List.of(new Agent("mail-agent", "Mail Agent", "hash")));

        assertThat(service.list()).hasSize(1);
    }

    @Test
    void getThrowsWhenAgentMissing() {
        when(agentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(99L)).isInstanceOf(AgentNotFoundException.class);
    }
}
