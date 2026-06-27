package com.example.demo.task;

import com.example.demo.task.dto.TaskExecutionRequest;
import com.example.demo.task.dto.TaskExecutionResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;

/**
 * {@code POST /internal/tasks/execute} を公開するWeb層。
 *
 * <p>業務ロジックは持たず、リクエストの受付・{@link TaskExecutionService} の呼び出し・
 * レスポンスDTOへの変換のみを担う（設計書「2.1」）。</p>
 */
@RestController
public class TaskExecutionController {

    private final TaskExecutionService taskExecutionService;

    public TaskExecutionController(TaskExecutionService taskExecutionService) {
        this.taskExecutionService = taskExecutionService;
    }

    @PostMapping("/internal/tasks/execute")
    public ResponseEntity<TaskExecutionResponse> execute(@Valid @RequestBody TaskExecutionRequest request) {
        Map<String, String> parameters = request.parameters() != null
                ? request.parameters()
                : Collections.emptyMap();

        TaskExecutionResult result = taskExecutionService.execute(request.taskName(), parameters);

        TaskExecutionResponse response = new TaskExecutionResponse(
                "SUCCESS", request.taskName(), result.message(), LocalDateTime.now());
        return ResponseEntity.ok(response);
    }
}
