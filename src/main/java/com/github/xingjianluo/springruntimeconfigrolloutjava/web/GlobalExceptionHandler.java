package com.github.xingjianluo.springruntimeconfigrolloutjava.web;

import com.github.xingjianluo.springruntimeconfigrolloutjava.exception.ConfigNotFoundException;
import com.github.xingjianluo.springruntimeconfigrolloutjava.exception.ConfigRejectedException;
import com.github.xingjianluo.springruntimeconfigrolloutjava.service.ConfigManager;
import com.github.xingjianluo.springruntimeconfigrolloutjava.web.dto.RejectionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 把领域异常翻译成带可区分原因码的 HTTP 响应。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final ConfigManager manager;

    public GlobalExceptionHandler(ConfigManager manager) {
        this.manager = manager;
    }

    @ExceptionHandler(ConfigRejectedException.class)
    public ResponseEntity<RejectionResponse> rejected(ConfigRejectedException ex) {
        HttpStatus status = switch (ex.getReason()) {
            case VERSION_CONFLICT -> HttpStatus.CONFLICT;
            case VERSION_NOT_FOUND, UNKNOWN_KEY, UNKNOWN_SOURCE -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(new RejectionResponse(
                true, ex.getReason().name(), ex.getMessage(), manager.currentSnapshot().version()));
    }

    @ExceptionHandler(ConfigNotFoundException.class)
    public ResponseEntity<RejectionResponse> notFound(ConfigNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new RejectionResponse(
                true, ex.getReason().name(), ex.getMessage(), manager.currentSnapshot().version()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<RejectionResponse> unreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(new RejectionResponse(
                true, "ILLEGAL_PAYLOAD", "请求体无法解析: " + ex.getMostSpecificCause().getMessage(),
                manager.currentSnapshot().version()));
    }
}
