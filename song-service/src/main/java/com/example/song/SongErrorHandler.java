package com.example.song;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class SongErrorHandler {
    @ExceptionHandler(SongValidationException.class)
    ResponseEntity<Map<String, Object>> validation(SongValidationException ex) {
        return ResponseEntity.badRequest().body(Map.of("errorMessage", "Validation error", "details", ex.details, "errorCode", "400"));
    }
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class, MissingServletRequestParameterException.class})
    ResponseEntity<Map<String, String>> badRequest(Exception ex) {
        String message = ex instanceof IllegalArgumentException ? ex.getMessage() : "Invalid request";
        return ResponseEntity.badRequest().body(Map.of("errorMessage", message, "errorCode", "400"));
    }
    @ExceptionHandler(SongNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(SongNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("errorMessage", ex.getMessage(), "errorCode", "404"));
    }
    @ExceptionHandler(SongConflictException.class)
    ResponseEntity<Map<String, String>> conflict(SongConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("errorMessage", ex.getMessage(), "errorCode", "409"));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> serverError(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("errorMessage", "Internal server error", "errorCode", "500"));
    }
}
