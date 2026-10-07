package com.agentgate.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.agentgate.runtime.RuntimeClient;
import com.agentgate.runtime.WorkflowValidation;
import com.agentgate.workflow.dto.WorkflowCreateRequest;
import com.agentgate.workflow.dto.WorkflowVersionResponse;
import com.agentgate.workflow.repository.WorkflowRepository;
import com.agentgate.workflow.repository.WorkflowVersionRepository;
import com.agentgate.workflow.service.WorkflowService;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
class WorkflowConcurrencyTest {

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowVersionRepository workflowVersionRepository;

    @MockitoBean
    private RuntimeClient runtimeClient;

    @Test
    void concurrentNewVersionsGetDistinctNumbers() throws Exception {
        workflowVersionRepository.deleteAll();
        workflowRepository.deleteAll();
        when(runtimeClient.validateWorkflow(any())).thenReturn(new WorkflowValidation(true, List.of()));
        JsonNode dsl = new JsonMapper().readTree("{\"nodes\":[],\"edges\":[]}");
        workflowService.create(new WorkflowCreateRequest("busy", null, dsl));

        List<Callable<WorkflowVersionResponse>> tasks = IntStream.range(0, 8)
                .<Callable<WorkflowVersionResponse>>mapToObj(i -> () -> workflowService.addVersion("busy", dsl))
                .toList();
        List<Integer> versions;
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            versions = pool.invokeAll(tasks).stream().map(this::versionOf).sorted().toList();
        }

        assertThat(versions).containsExactly(2, 3, 4, 5, 6, 7, 8, 9);
    }

    private int versionOf(Future<WorkflowVersionResponse> future) {
        try {
            return future.get().version();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
