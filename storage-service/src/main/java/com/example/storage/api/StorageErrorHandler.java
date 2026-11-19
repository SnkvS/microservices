package com.example.storage.api;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestControllerAdvice
public class StorageErrorHandler {
    @ExceptionHandler({IllegalArgumentException.class,DataIntegrityViolationException.class,HttpMessageNotReadableException.class,MissingServletRequestParameterException.class})
    ResponseEntity<Map<String,String>> invalid(Exception failure) {
        String message=failure instanceof IllegalArgumentException ? failure.getMessage() : "Invalid request";
        return ResponseEntity.badRequest().body(Map.of("errorMessage",message,"errorCode","400"));
    }
    @ExceptionHandler(Exception.class) ResponseEntity<Map<String,String>> unexpected(Exception failure) {
        return ResponseEntity.internalServerError().body(Map.of("errorMessage","Internal server error","errorCode","500"));
    }
}
