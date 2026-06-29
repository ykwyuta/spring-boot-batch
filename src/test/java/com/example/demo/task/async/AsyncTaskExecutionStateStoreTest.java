package com.example.demo.task.async;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AsyncTaskExecutionStateStore} の単体テスト
 * （テスト計画 SS-01〜SS-04、save/find/update/removeの基本動作）。
 */
class AsyncTaskExecutionStateStoreTest {

    private AsyncTaskExecutionStateStore stateStore;

    @BeforeEach
    void setUp() {
        stateStore = new AsyncTaskExecutionStateStore();
    }

    /**
     * SS-01: save済みのtaskIdをfindした場合、Optionalに値が入って返る。
     */
    @Test
    void find_recordExists_returnsPresentOptionalWithSameContent() {
        UUID taskId = UUID.randomUUID();
        AsyncTaskRecord record = AsyncTaskRecord.pending(taskId, "sample-task", List.of(), Instant.now());
        stateStore.save(record);

        Optional<AsyncTaskRecord> found = stateStore.find(taskId);

        assertThat(found).isPresent();
        assertThat(found.get()).isEqualTo(record);
    }

    /**
     * SS-02: saveしていないtaskIdをfindした場合、空のOptionalが返る。
     */
    @Test
    void find_recordDoesNotExist_returnsEmptyOptional() {
        Optional<AsyncTaskRecord> found = stateStore.find(UUID.randomUUID());

        assertThat(found).isEmpty();
    }

    /**
     * SS-03: 既存レコードにUnaryOperatorを適用し、更新後の値を保存して返す。
     */
    @Test
    void update_existingRecord_appliesUpdaterAndPersistsResult() {
        UUID taskId = UUID.randomUUID();
        stateStore.save(AsyncTaskRecord.pending(taskId, "sample-task", List.of(), Instant.now()));

        AsyncTaskRecord updated = stateStore.update(taskId, record -> record.withRunning(Instant.now()));

        assertThat(updated.status()).isEqualTo(AsyncTaskStatus.RUNNING);
        assertThat(stateStore.find(taskId).orElseThrow().status()).isEqualTo(AsyncTaskStatus.RUNNING);
    }

    /**
     * SS-04: 登録済みレコードを削除すると、以後のfindが空になる。
     */
    @Test
    void remove_existingRecord_makesSubsequentFindEmpty() {
        UUID taskId = UUID.randomUUID();
        stateStore.save(AsyncTaskRecord.pending(taskId, "sample-task", List.of(), Instant.now()));

        stateStore.remove(taskId);

        assertThat(stateStore.find(taskId)).isEmpty();
    }
}
