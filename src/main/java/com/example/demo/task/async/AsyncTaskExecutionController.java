package com.example.demo.task.async;

import com.example.demo.task.dto.AsyncTaskExecutionAcceptedResponse;
import com.example.demo.task.dto.AsyncTaskExecutionRequest;
import com.example.demo.task.dto.AsyncTaskStatusResponse;
import com.example.demo.task.exception.AsyncTaskNotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code POST /internal/tasks/execute-async} と {@code GET /internal/tasks/{taskId}} を
 * 公開するWeb層（設計書「2.1」「5.6」）。
 *
 * <p>業務ロジックは持たず、リクエストの受付・{@link AsyncTaskExecutionService} の呼び出し・
 * レスポンスDTOへの変換のみを担う（既存の {@code TaskExecutionController} と役割分担の方針を
 * 揃える）。</p>
 */
@RestController
public class AsyncTaskExecutionController {

    private final AsyncTaskExecutionService asyncTaskExecutionService;

    public AsyncTaskExecutionController(AsyncTaskExecutionService asyncTaskExecutionService) {
        this.asyncTaskExecutionService = asyncTaskExecutionService;
    }

    @PostMapping("/internal/tasks/execute-async")
    public ResponseEntity<AsyncTaskExecutionAcceptedResponse> executeAsync(
            @Valid @RequestBody AsyncTaskExecutionRequest request) {
        Map<String, String> parameters = request.parameters() != null ? request.parameters() : Map.of();
        UUID taskId = asyncTaskExecutionService.acceptAsyncExecution(
                request.taskName(), parameters, request.inputFilePaths());

        AsyncTaskExecutionAcceptedResponse response = new AsyncTaskExecutionAcceptedResponse(
                taskId, AsyncTaskStatus.PENDING.name(), Instant.now());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/internal/tasks/{taskId}")
    public ResponseEntity<AsyncTaskStatusResponse> getStatus(@PathVariable String taskId) {
        UUID parsedTaskId = parseTaskId(taskId);
        AsyncTaskRecord record = asyncTaskExecutionService.getStatus(parsedTaskId);
        return ResponseEntity.ok(toResponse(record));
    }

    private UUID parseTaskId(String taskId) {
        try {
            return UUID.fromString(taskId);
        } catch (IllegalArgumentException ex) {
            throw new AsyncTaskNotFoundException("async task not found: " + taskId);
        }
    }

    private AsyncTaskStatusResponse toResponse(AsyncTaskRecord record) {
        List<String> outputFilePaths = record.outputFilePaths() != null ? record.outputFilePaths() : List.of();
        return new AsyncTaskStatusResponse(
                record.taskId(),
                record.status().name(),
                record.taskName(),
                record.message(),
                outputFilePaths,
                record.createdAt(),
                record.updatedAt());
    }
}
