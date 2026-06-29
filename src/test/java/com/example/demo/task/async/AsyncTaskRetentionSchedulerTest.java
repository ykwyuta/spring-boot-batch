package com.example.demo.task.async;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AsyncTaskExecutionStateStore#removeIfOlderThan(Instant)} の削除対象判定ロジックの単体テスト
 * （テスト計画 RT-01〜RT-04、設計書「9」分岐 D1〜D3）。
 *
 * <p>{@link AsyncTaskRetentionScheduler}が{@code @Scheduled}で1分間隔に呼び出す
 * {@code removeIfOlderThan}は、判定ロジックそのものが{@code AsyncTaskExecutionStateStore}に
 * 閉じているため、タイマー起動を待たずメソッドを直接呼び出して検証する
 * （テスト計画セクション7の方針）。</p>
 */
class AsyncTaskRetentionSchedulerTest {

    private static final Instant THRESHOLD = Instant.parse("2026-06-29T00:00:00Z");

    /**
     * RT-01: statusがSUCCEEDED/FAILEDかつupdatedAtがTTLを超過している場合、レコードを削除する（D1）。
     */
    @Test
    void removeIfOlderThan_completedAndOlderThanThreshold_removesRecords() {
        AsyncTaskExecutionStateStore stateStore = new AsyncTaskExecutionStateStore();
        UUID succeededId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        Instant olderThanThreshold = THRESHOLD.minusSeconds(60);
        stateStore.save(AsyncTaskRecord.pending(succeededId, "sample-task", List.of(), olderThanThreshold)
                .withSucceeded(List.of(), "task executed successfully", olderThanThreshold));
        stateStore.save(AsyncTaskRecord.pending(failedId, "task-business-failure", List.of(), olderThanThreshold)
                .withFailed("TASK_EXECUTION_FAILED", "precondition not satisfied", olderThanThreshold));

        stateStore.removeIfOlderThan(THRESHOLD);

        assertThat(stateStore.find(succeededId)).isEmpty();
        assertThat(stateStore.find(failedId)).isEmpty();
    }

    /**
     * RT-02: statusがPENDING/RUNNINGの場合、updatedAtがTTLを超過していても削除しない（D2）。
     */
    @Test
    void removeIfOlderThan_pendingOrRunningEvenIfOld_doesNotRemoveRecords() {
        AsyncTaskExecutionStateStore stateStore = new AsyncTaskExecutionStateStore();
        UUID pendingId = UUID.randomUUID();
        UUID runningId = UUID.randomUUID();
        Instant olderThanThreshold = THRESHOLD.minusSeconds(60);
        stateStore.save(AsyncTaskRecord.pending(pendingId, "sample-task", List.of(), olderThanThreshold));
        stateStore.save(AsyncTaskRecord.pending(runningId, "sample-task", List.of(), olderThanThreshold)
                .withRunning(olderThanThreshold));

        stateStore.removeIfOlderThan(THRESHOLD);

        assertThat(stateStore.find(pendingId)).isPresent();
        assertThat(stateStore.find(runningId)).isPresent();
    }

    /**
     * RT-03: statusがSUCCEEDED/FAILEDだがupdatedAtがTTL未満（しきい値より新しい）の場合、削除しない（D3）。
     */
    @Test
    void removeIfOlderThan_completedButNewerThanThreshold_doesNotRemoveRecord() {
        AsyncTaskExecutionStateStore stateStore = new AsyncTaskExecutionStateStore();
        UUID succeededId = UUID.randomUUID();
        Instant newerThanThreshold = THRESHOLD.plusSeconds(60);
        stateStore.save(AsyncTaskRecord.pending(succeededId, "sample-task", List.of(), newerThanThreshold)
                .withSucceeded(List.of(), "task executed successfully", newerThanThreshold));

        stateStore.removeIfOlderThan(THRESHOLD);

        assertThat(stateStore.find(succeededId)).isPresent();
    }

    /**
     * RT-04（境界値）: updatedAtがしきい値とちょうど等しい場合、削除されない
     * （removeIfOlderThanの実装はisBefore(threshold)で判定するため、等しい場合は
     * 「しきい値より古い」に該当しない）。
     */
    @Test
    void removeIfOlderThan_updatedAtEqualsThreshold_doesNotRemoveRecord() {
        AsyncTaskExecutionStateStore stateStore = new AsyncTaskExecutionStateStore();
        UUID succeededId = UUID.randomUUID();
        stateStore.save(AsyncTaskRecord.pending(succeededId, "sample-task", List.of(), THRESHOLD)
                .withSucceeded(List.of(), "task executed successfully", THRESHOLD));

        stateStore.removeIfOlderThan(THRESHOLD);

        assertThat(stateStore.find(succeededId)).isPresent();
    }
}
