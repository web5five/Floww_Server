package com.floww.server;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handled(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code()));
    }
}
