package com.agentgate.execution.controller;

import com.agentgate.common.exception.InvalidRuntimeTokenException;
import com.agentgate.execution.domain.ExecutionStatus;
import com.agentgate.execution.dto.ExecutionEvent;
import com.agentgate.execution.dto.ExecutionResponse;
import com.agentgate.execution.dto.ExecutionStartRequest;
import com.agentgate.execution.service.ExecutionService;
import com.agentgate.execution.service.ExecutionStreamBroadcaster;
import com.agentgate.runtime.RuntimeClient;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/executions")
@RequiredArgsConstructor
public class ExecutionController {

    private static final Set<ExecutionStatus> FINISHED = Set.of(ExecutionStatus.COMPLETED, ExecutionStatus.STOPPED,
            ExecutionStatus.FAILED);

    private final ExecutionService executionService;
    private final ExecutionStreamBroadcaster broadcaster;
    private final RuntimeClient runtimeClient;

    @PostMapping
    public ResponseEntity<ExecutionResponse> start(@Valid @RequestBody ExecutionStartRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(executionService.start(request));
    }

    @GetMapping
    public ResponseEntity<List<ExecutionResponse>> list(@RequestParam(required = false) String workflowId) {
        return ResponseEntity.ok(executionService.list(workflowId));
    }

    @GetMapping("/{executionId}")
    public ResponseEntity<ExecutionResponse> get(@PathVariable String executionId) {
        return ResponseEntity.ok(executionService.get(executionId));
    }

    /** Live updates: a {@code snapshot} event with the full execution, then one {@code update} per runtime event. */
    @GetMapping(path = "/{executionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String executionId) {
        ExecutionResponse snapshot = executionService.get(executionId);
        SseEmitter emitter = broadcaster.subscribe(executionId, snapshot);
        if (FINISHED.contains(snapshot.status())) {
            emitter.complete();
        }
        return emitter;
    }

    /** Progress events from the agent runtime, authenticated with the shared runtime token. */
    @PostMapping("/{executionId}/events")
    public ResponseEntity<Void> event(@PathVariable String executionId,
                                      @RequestHeader(name = "X-Runtime-Token", required = false) String token,
                                      @Valid @RequestBody ExecutionEvent event) {
        if (!runtimeClient.acceptsToken(token)) {
            throw new InvalidRuntimeTokenException();
        }
        ExecutionResponse updated = executionService.apply(executionId, event);
        broadcaster.publish(executionId, "update", Map.of("event", event, "execution", updated));
        if (FINISHED.contains(updated.status())) {
            broadcaster.complete(executionId);
        }
        return ResponseEntity.accepted().build();
    }
}
