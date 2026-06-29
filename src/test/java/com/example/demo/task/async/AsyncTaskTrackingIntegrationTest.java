package com.example.demo.task.async;

import com.example.demo.task.TaskExecutionService;
import com.example.demo.task.dto.AsyncTaskExecutionAcceptedResponse;
import com.example.demo.task.dto.AsyncTaskExecutionRequest;
import com.example.demo.task.dto.AsyncTaskStatusResponse;
import com.example.demo.task.dto.TaskExecutionRequest;
import com.example.demo.task.exception.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同期API・非同期APIをまたいだ結合シナリオの単体テスト
 * （テスト計画 IG-01〜IG-04、設計書「9」分岐 A0a/C0a, B3, A12/B1/B5/C7, A10/A11/C2/C4）。
 *
 * <p>{@code @SpringBootTest(webEnvironment = RANDOM_PORT)} でアプリケーション全体（実際の
 * {@code LocalhostOnlyInterceptor}・実際の非同期スレッドプール）を起動し、自動構成された
 * {@link TestRestTemplate}（{@code localhost}宛のため{@code LocalhostOnlyInterceptor}を
 * 自然に通過する）経由でHTTPリクエストを送る（テスト計画セクション8の方針）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AsyncTaskTrackingIntegrationTest {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(20);

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * IG-01: LocalhostOnlyInterceptorが既存設定により新規エンドポイントにも自動適用され、
     * ローカルホストからのアクセスを許可する（A0a/C0a）。
     */
    @Test
    void newEndpoints_accessedFromLocalhost_neverReturnForbidden() {
        ResponseEntity<AsyncTaskExecutionAcceptedResponse> acceptResponse = postExecuteAsync("sample-task", List.of());
        assertThat(acceptResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<ErrorResponse> statusResponse = getStatusExpectingError(UUID.randomUUID());
        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * IG-02（AE-08実体）: 同一の業務的失敗（taskName=task-business-failure）に対し、同期APIは422、
     * 非同期APIは完了後200 OK＋status=FAILEDを返す（422は一度も出現しない）（B3、設計書「8.3」）。
     */
    @Test
    void businessFailure_syncReturns422ButAsyncNeverReturns422() {
        HttpEntity<TaskExecutionRequest> syncRequest =
                jsonEntity(new TaskExecutionRequest(TaskExecutionService.TASK_NAME_BUSINESS_FAILURE, Map.of()));
        ResponseEntity<ErrorResponse> syncResponse =
                restTemplate.postForEntity("/internal/tasks/execute", syncRequest, ErrorResponse.class);
        assertThat(syncResponse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(syncResponse.getBody()).isNotNull();
        assertThat(syncResponse.getBody().errorCode()).isEqualTo("TASK_EXECUTION_FAILED");

        ResponseEntity<AsyncTaskExecutionAcceptedResponse> acceptResponse =
                postExecuteAsync(TaskExecutionService.TASK_NAME_BUSINESS_FAILURE, List.of());
        assertThat(acceptResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID taskId = acceptResponse.getBody().taskId();

        AsyncTaskStatusResponse finalStatus = pollUntilTerminal(taskId);

        assertThat(finalStatus.status()).isEqualTo("FAILED");
        assertThat(finalStatus.message()).contains("precondition not satisfied");
    }

    /**
     * IG-03: 正常系タスク（sample-task）を非同期APIで実行し、完了までポーリングして結果を取得できる
     * （A12, B1, B5, C7）。
     */
    @Test
    void sampleTask_endToEnd_pollsUntilSucceeded() {
        ResponseEntity<AsyncTaskExecutionAcceptedResponse> acceptResponse = postExecuteAsync("sample-task", List.of());
        assertThat(acceptResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(acceptResponse.getBody()).isNotNull();
        assertThat(acceptResponse.getBody().status()).isEqualTo("PENDING");
        UUID taskId = acceptResponse.getBody().taskId();

        AsyncTaskStatusResponse finalStatus = pollUntilTerminal(taskId);

        assertThat(finalStatus.status()).isEqualTo("SUCCEEDED");
        assertThat(finalStatus.outputFilePaths()).hasSize(1);
    }

    /**
     * IG-04（参考・任意）: 入力ファイル不存在エラー、および未知のtaskIdに対する404応答をE2Eで再確認する
     * （A10/A11 + C2/C4）。
     */
    @Test
    void inputFileMissingAndUnknownTaskId_bothReturnNotFound() {
        String missingPath = "/no/such/file-" + UUID.randomUUID();
        ResponseEntity<ErrorResponse> acceptResponse = postExecuteAsyncExpectingError("sample-task", List.of(missingPath));
        assertThat(acceptResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(acceptResponse.getBody()).isNotNull();
        assertThat(acceptResponse.getBody().errorCode()).isEqualTo("INPUT_FILE_NOT_FOUND");

        ResponseEntity<ErrorResponse> statusResponse = getStatusExpectingError(UUID.randomUUID());
        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusResponse.getBody()).isNotNull();
        assertThat(statusResponse.getBody().errorCode()).isEqualTo("TASK_NOT_FOUND");
    }

    private AsyncTaskStatusResponse pollUntilTerminal(UUID taskId) {
        Instant deadline = Instant.now().plus(POLL_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            ResponseEntity<AsyncTaskStatusResponse> response =
                    restTemplate.getForEntity("/internal/tasks/{taskId}", AsyncTaskStatusResponse.class, taskId);
            assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            AsyncTaskStatusResponse body = response.getBody();
            if (body != null && ("SUCCEEDED".equals(body.status()) || "FAILED".equals(body.status()))) {
                return body;
            }
            sleep();
        }
        throw new AssertionError("task " + taskId + " did not reach a terminal state within " + POLL_TIMEOUT);
    }

    private void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while polling", ex);
        }
    }

    private ResponseEntity<AsyncTaskExecutionAcceptedResponse> postExecuteAsync(
            String taskName, List<String> inputFilePaths) {
        HttpEntity<AsyncTaskExecutionRequest> request =
                jsonEntity(new AsyncTaskExecutionRequest(taskName, Map.of(), inputFilePaths));
        return restTemplate.postForEntity(
                "/internal/tasks/execute-async", request, AsyncTaskExecutionAcceptedResponse.class);
    }

    private ResponseEntity<ErrorResponse> postExecuteAsyncExpectingError(String taskName, List<String> inputFilePaths) {
        HttpEntity<AsyncTaskExecutionRequest> request =
                jsonEntity(new AsyncTaskExecutionRequest(taskName, Map.of(), inputFilePaths));
        return restTemplate.postForEntity("/internal/tasks/execute-async", request, ErrorResponse.class);
    }

    private ResponseEntity<ErrorResponse> getStatusExpectingError(UUID taskId) {
        return restTemplate.getForEntity("/internal/tasks/{taskId}", ErrorResponse.class, taskId);
    }

    private <T> HttpEntity<T> jsonEntity(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
