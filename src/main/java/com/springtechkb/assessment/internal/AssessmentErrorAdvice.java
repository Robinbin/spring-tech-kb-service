package com.springtechkb.assessment.internal;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Publishes intentional assessment errors without exposing unexpected exception details. */
@RestControllerAdvice(assignableTypes = AssessmentController.class)
class AssessmentErrorAdvice {

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail handle(ResponseStatusException exception) {
        return ProblemDetail.forStatusAndDetail(exception.getStatusCode(),
                exception.getReason() == null ? "Assessment request could not be completed" : exception.getReason());
    }
}
