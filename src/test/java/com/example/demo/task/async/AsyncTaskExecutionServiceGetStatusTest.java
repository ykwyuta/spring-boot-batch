package com.example.demo.task.async;

import com.example.demo.task.TaskExecutionService;
import com.example.demo.task.exception.AsyncTaskNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AsyncTaskExecutionService#getStatus(UUID)} の単体テスト
 * （テスト計画 GS-01〜GS-02、設計書「9」分岐 C3〜C4）。
 */
class AsyncTaskExecutionServiceGetStatusTest {

    private AsyncTaskExecutionStateStore stateStore;
    private AsyncTaskExecutionService service;

    @BeforeEach
    void setUp() {
        stateStore = new AsyncTaskExecutionStateStore();
        AsyncTaskExecutionService self = Mockito.mock(AsyncTaskExecutionService.class);
        service = new AsyncTaskExecutionService(Mockito.mock(TaskExecutionService.class), stateStore, self);
    }

    /**
     * GS-01: 状態ストアに該当taskIdのレコードが存在する場合、AsyncTaskRecordを返す（C3）。
     */
    @Test
    void getStatus_recordExists_returnsRecord() {
        UUID taskId = UUID.randomUUID();
        AsyncTaskRecord record = AsyncTaskRecord.pending(taskId, "sample-task", List.of(), Instant.now());
        stateStore.save(record);

        AsyncTaskRecord found = service.getStatus(taskId);

        assertThat(found).isEqualTo(record);
    }

    /**
     * GS-02: 状態ストアに該当taskIdのレコードが存在しない場合、AsyncTaskNotFoundExceptionをスローする（C4）。
     */
    @Test
    void getStatus_recordDoesNotExist_throwsAsyncTaskNotFoundException() {
        UUID taskId = UUID.randomUUID();

        assertThatThrownBy(() -> service.getStatus(taskId))
                .isInstanceOf(AsyncTaskNotFoundException.class);
    }
}
