package com.example.demo.task.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * {@code /internal/**} 配下で発生する例外を統一的なエラーレスポンスに変換する。
 *
 * <p>{@code LocalhostOnlyInterceptor} による403応答はController到達前に直接書き込まれるため
 * 本クラスを経由しない（設計書「6.1」）。それ以外のすべての例外をここに集約する。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("ERROR", "BAD_REQUEST", "malformed JSON request", Instant.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + " " + fieldError.getDefaultMessage())
                .orElse("validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("ERROR", "VALIDATION_ERROR", message, Instant.now()));
    }

    @ExceptionHandler(TaskExecutionException.class)
    public ResponseEntity<ErrorResponse> handleTaskExecutionException(TaskExecutionException ex) {
        HttpStatus status = switch (ex.getErrorCode()) {
            case TASK_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case TASK_EXECUTION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status)
                .body(new ErrorResponse("ERROR", ex.getErrorCode().name(), ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception ex) {
        logger.error("unexpected error occurred while executing task", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("ERROR", "INTERNAL_ERROR", "unexpected error occurred", Instant.now()));
    }
}
