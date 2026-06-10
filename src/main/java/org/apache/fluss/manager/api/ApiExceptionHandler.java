package org.apache.fluss.manager.api;

import java.util.Map;
import java.util.concurrent.CompletionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> handle(Exception exception) {
        Throwable cause = unwrap(exception);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(
                        Map.of(
                                "error", cause.getClass().getSimpleName(),
                                "message", cause.getMessage() == null ? cause.toString() : cause.getMessage()));
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof IllegalStateException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
