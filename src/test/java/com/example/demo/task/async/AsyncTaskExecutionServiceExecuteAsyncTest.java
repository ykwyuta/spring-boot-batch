package com.example.demo.task.async;

import com.example.demo.task.TaskExecutionResult;
import com.example.demo.task.TaskExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AsyncTaskExecutionService#executeAsync(UUID, String, Map, List)} の単体テスト
 * （テスト計画 AE-01〜AE-07、設計書「9」分岐 B1〜B6）。
 *
 * <p>{@code @Async}によるスレッドプール経由ではなく、メソッドを直接同期的に呼び出すことで
 * 状態ストアへの反映を検証する（テスト計画セクション3の方針）。AE-08は単体テストの粒度を超えるため
 * 結合シナリオ（IG-02）で検証する。</p>
 */
class AsyncTaskExecutionServiceExecuteAsyncTest {

    private AsyncTaskExecutionStateStore stateStore;

    @BeforeEach
    void setUp() {
        stateStore = new AsyncTaskExecutionStateStore();
    }

    /**
     * AE-01: executeAsync呼び出し直後、TaskExecutionService.execute呼び出し前に状態ストアが
     * RUNNINGに更新される（T2）。
     */
    @Test
    void executeAsync_beforeDelegatingToTaskExecutionService_statusIsRunning() {
        UUID taskId = UUID.randomUUID();
        stateStore.save(AsyncTaskRecord.pending(taskId, "sample-task", List.of(), Instant.now()));
        TaskExecutionService taskExecutionService = Mockito.mock(TaskExecutionService.class);
        AsyncTaskStatus[] statusDuringExecute = new AsyncTaskStatus[1];
        Mockito.when(taskExecutionService.execute(Mockito.any(), Mockito.any())).thenAnswer(invocation -> {
            statusDuringExecute[0] = stateStore.find(taskId).orElseThrow().status();
            return new TaskExecutionResult("task executed successfully");
        });
        AsyncTaskExecutionService service = newService(taskExecutionService);

        service.executeAsync(taskId, "sample-task", Map.of(), List.of());

        assertThat(statusDuringExecute[0]).isEqualTo(AsyncTaskStatus.RUNNING);
    }

    /**
     * AE-02: TaskExecutionService.executeが正常終了する場合、SUCCEEDEDに遷移する（B1）。
     */
    @Test
    void executeAsync_taskSucceeds_transitionsToSucceeded() {
        UUID taskId = UUID.randomUUID();
        stateStore.save(AsyncTaskRecord.pending(taskId, "sample-task", List.of(), Instant.now()));
        AsyncTaskExecutionService service = newServiceWithRealTaskExecutionService();

        service.executeAsync(taskId, "sample-task", Map.of(), List.of());

        AsyncTaskRecord record = stateStore.find(taskId).orElseThrow();
        assertThat(record.status()).isEqualTo(AsyncTaskStatus.SUCCEEDED);
        assertThat(record.message()).isEqualTo("task executed successfully");
    }

    /**
     * AE-03: TaskExecutionService.executeがTASK_NOT_FOUNDをスローする場合、FAILEDに遷移する（B2）。
     */
    @Test
    void executeAsync_unknownTaskName_transitionsToFailedWithTaskNotFound() {
        UUID taskId = UUID.randomUUID();
        stateStore.save(AsyncTaskRecord.pending(taskId, "unknown-task", List.of(), Instant.now()));
        AsyncTaskExecutionService service = newServiceWithRealTaskExecutionService();

        service.executeAsync(taskId, "unknown-task", Map.of(), List.of());

        AsyncTaskRecord record = stateStore.find(taskId).orElseThrow();
        assertThat(record.status()).isEqualTo(AsyncTaskStatus.FAILED);
        assertThat(record.errorCode()).isEqualTo("TASK_NOT_FOUND");
    }

    /**
     * AE-04: TaskExecutionService.executeがTASK_EXECUTION_FAILEDをスローする場合、FAILEDに遷移する（B3）。
     */
    @Test
    void executeAsync_businessFailure_transitionsToFailedWithTaskExecutionFailed() {
        UUID taskId = UUID.randomUUID();
        stateStore.save(AsyncTaskRecord.pending(
                taskId, TaskExecutionService.TASK_NAME_BUSINESS_FAILURE, List.of(), Instant.now()));
        AsyncTaskExecutionService service = newServiceWithRealTaskExecutionService();

        service.executeAsync(taskId, TaskExecutionService.TASK_NAME_BUSINESS_FAILURE, Map.of(), List.of());

        AsyncTaskRecord record = stateStore.find(taskId).orElseThrow();
        assertThat(record.status()).isEqualTo(AsyncTaskStatus.FAILED);
        assertThat(record.errorCode()).isEqualTo("TASK_EXECUTION_FAILED");
        assertThat(record.message()).contains("precondition not satisfied");
    }

    /**
     * AE-05: TaskExecutionService.executeが想定外の実行時例外をスローする場合、ログ出力後FAILEDに
     * 遷移する（B4）。executeAsync自体は例外をスローせず正常にreturnする（呼び出し元に伝播しない）
     * ことも合わせて確認する。
     */
    @Test
    void executeAsync_unexpectedException_transitionsToFailedWithInternalErrorAndDoesNotPropagate() {
        UUID taskId = UUID.randomUUID();
        stateStore.save(AsyncTaskRecord.pending(
                taskId, TaskExecutionService.TASK_NAME_UNEXPECTED_ERROR, List.of(), Instant.now()));
        AsyncTaskExecutionService service = newServiceWithRealTaskExecutionService();

        service.executeAsync(taskId, TaskExecutionService.TASK_NAME_UNEXPECTED_ERROR, Map.of(), List.of());

        AsyncTaskRecord record = stateStore.find(taskId).orElseThrow();
        assertThat(record.status()).isEqualTo(AsyncTaskStatus.FAILED);
        assertThat(record.errorCode()).isEqualTo("INTERNAL_ERROR");
    }

    /**
     * AE-06: taskName=sample-taskの場合、outputFilePathsに1件のパスが設定される（B5）。
     */
    @Test
    void executeAsync_sampleTask_setsSingleOutputFilePath() {
        UUID taskId = UUID.randomUUID();
        stateStore.save(AsyncTaskRecord.pending(taskId, "sample-task", List.of(), Instant.now()));
        AsyncTaskExecutionService service = newServiceWithRealTaskExecutionService();

        service.executeAsync(taskId, "sample-task", Map.of(), List.of());

        AsyncTaskRecord record = stateStore.find(taskId).orElseThrow();
        assertThat(record.outputFilePaths()).containsExactly("/tmp/async-tasks/" + taskId + "/result.txt");
    }

    /**
     * AE-07: 処理が失敗したケース（B2〜B4）の場合、outputFilePathsは空リストのままである（B6）。
     */
    @Test
    void executeAsync_businessFailure_outputFilePathsStaysEmpty() {
        UUID taskId = UUID.randomUUID();
        stateStore.save(AsyncTaskRecord.pending(
                taskId, TaskExecutionService.TASK_NAME_BUSINESS_FAILURE, List.of(), Instant.now()));
        AsyncTaskExecutionService service = newServiceWithRealTaskExecutionService();

        service.executeAsync(taskId, TaskExecutionService.TASK_NAME_BUSINESS_FAILURE, Map.of(), List.of());

        AsyncTaskRecord record = stateStore.find(taskId).orElseThrow();
        assertThat(record.outputFilePaths()).isEmpty();
    }

    private AsyncTaskExecutionService newServiceWithRealTaskExecutionService() {
        return newService(new TaskExecutionService());
    }

    private AsyncTaskExecutionService newService(TaskExecutionService taskExecutionService) {
        AsyncTaskExecutionService self = Mockito.mock(AsyncTaskExecutionService.class);
        return new AsyncTaskExecutionService(taskExecutionService, stateStore, self);
    }
}
