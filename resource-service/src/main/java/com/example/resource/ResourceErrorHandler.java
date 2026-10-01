package com.example.resource;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

@RestControllerAdvice
public class ResourceErrorHandler {
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class, MissingServletRequestParameterException.class})
    ResponseEntity<Map<String, String>> badRequest(Exception ex) {
        String message = ex instanceof IllegalArgumentException ? ex.getMessage() : "Invalid request";
        return ResponseEntity.badRequest().body(Map.of("errorMessage", message, "errorCode", "400"));
    }
    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("errorMessage", ex.getMessage(), "errorCode", "404"));
    }
    @ExceptionHandler(RestClientException.class)
    ResponseEntity<Map<String, String>> songFailure(RestClientException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("errorMessage", "Song Service request failed", "errorCode", "500"));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> serverError(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("errorMessage", "Internal server error", "errorCode", "500"));
    }
}
