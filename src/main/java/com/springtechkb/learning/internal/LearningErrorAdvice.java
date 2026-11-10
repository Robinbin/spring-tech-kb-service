package com.springtechkb.learning.internal;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Publishes intentional learning errors without exposing unexpected exception details. */
@RestControllerAdvice(assignableTypes = LearningController.class)
class LearningErrorAdvice {

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail handle(ResponseStatusException exception) {
        return ProblemDetail.forStatusAndDetail(exception.getStatusCode(),
                exception.getReason() == null ? "Learning request could not be completed" : exception.getReason());
    }
}
