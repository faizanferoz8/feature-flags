package dev.flags.server.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errors leave as RFC 9457 problem documents. Security failures are not handled here:
 * they belong to the filter chain, which answers 401 and 403 itself.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ProblemDetail handle(ApiException e) {
        return ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handle(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request is not valid.");
        problem.setProperty("fields", fields);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handle(HttpMessageNotReadableException e) {
        // The flag model validates itself as it is built, so a rule with no conditions or
        // a rollout of 140% surfaces here, with a message worth passing on.
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
            if (cause instanceof IllegalArgumentException) {
                return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, cause.getMessage());
            }
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request body could not be read.");
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail handle(ObjectOptimisticLockingFailureException e) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "Someone else changed this at the same moment. Reload and try again.");
    }
}
