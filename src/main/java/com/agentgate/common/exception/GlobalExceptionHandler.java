package com.agentgate.common.exception;

import com.agentgate.common.response.ErrorResponse;
import com.agentgate.runtime.RuntimeUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AgentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAgentNotFound(AgentNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateUserException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateUser(DuplicateUserException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "USER_ALREADY_EXISTS", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidUserChangeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidUserChange(InvalidUserChangeException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_USER_CHANGE", ex.getMessage(), request);
    }

    @ExceptionHandler(McpServerNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleMcpServerNotFound(McpServerNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "MCP_SERVER_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateMcpServerException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateMcpServer(DuplicateMcpServerException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "MCP_SERVER_ALREADY_EXISTS", ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateAgentException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateAgent(DuplicateAgentException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "AGENT_ALREADY_EXISTS", ex.getMessage(), request);
    }

    @ExceptionHandler(AgentDefinitionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAgentDefinitionNotFound(AgentDefinitionNotFoundException ex,
                                                                       HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "AGENT_DEFINITION_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidAgentDefinitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidAgentDefinition(InvalidAgentDefinitionException ex,
                                                                      HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage(), request);
    }

    @ExceptionHandler(PolicyNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePolicyNotFound(PolicyNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "POLICY_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(ApprovalNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleApprovalNotFound(ApprovalNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "APPROVAL_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(AuditLogNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAuditLogNotFound(AuditLogNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "AUDIT_LOG_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalApprovalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalApprovalState(IllegalApprovalStateException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "INVALID_APPROVAL_STATE", ex.getMessage(), request);
    }

    @ExceptionHandler(WorkflowNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWorkflowNotFound(WorkflowNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "WORKFLOW_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateWorkflowException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateWorkflow(DuplicateWorkflowException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "WORKFLOW_ALREADY_EXISTS", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidWorkflowException.class)
    public ResponseEntity<ErrorResponse> handleInvalidWorkflow(InvalidWorkflowException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.UNPROCESSABLE_CONTENT;
        return ResponseEntity.status(status).body(new ErrorResponse(Instant.now(), status.value(),
                status.getReasonPhrase(), "INVALID_WORKFLOW", ex.getMessage(), request.getRequestURI(), ex.getErrors()));
    }

    @ExceptionHandler(ExecutionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleExecutionNotFound(ExecutionNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidRuntimeTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRuntimeToken(InvalidRuntimeTokenException ex,
                                                                   HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "INVALID_RUNTIME_TOKEN", ex.getMessage(), request);
    }

    @ExceptionHandler(RuntimeUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleRuntimeUnavailable(RuntimeUnavailableException ex, HttpServletRequest request) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "RUNTIME_UNAVAILABLE", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidApiKeyException.class)
    public ResponseEntity<ErrorResponse> handleInvalidApiKey(InvalidApiKeyException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "INVALID_API_KEY", ex.getMessage(), request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "INVALID_API_KEY", "Missing or invalid API key", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedRequest(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Malformed request body", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> "%s: %s".formatted(error.getField(), error.getDefaultMessage()))
                .collect(Collectors.joining(", "));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error handling request {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error occurred", request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                code,
                message,
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(body);
    }
}
