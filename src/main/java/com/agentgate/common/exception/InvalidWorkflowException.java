package com.agentgate.common.exception;

import com.agentgate.runtime.WorkflowValidation;
import java.util.List;
import lombok.Getter;

@Getter
public class InvalidWorkflowException extends RuntimeException {

    private final List<WorkflowValidation.Issue> errors;

    public InvalidWorkflowException(String message, List<WorkflowValidation.Issue> errors) {
        super(message);
        this.errors = List.copyOf(errors);
    }
}
