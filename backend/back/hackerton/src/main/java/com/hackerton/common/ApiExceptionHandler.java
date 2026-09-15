package com.hackerton.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<?> handle(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getCode(), "message", e.getMessage()));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> invalidBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", "요청 JSON 형식을 확인하세요."));
    }
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<?> conflict(Exception e) {
        return ResponseEntity.status(409).body(Map.of("code", "RECOMMENDATION_CONFLICT", "message", "동시에 수정된 추천입니다. 다시 조회하세요."));
    }
}

