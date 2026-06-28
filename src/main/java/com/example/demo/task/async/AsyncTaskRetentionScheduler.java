package com.example.demo.task.async;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 完了済み（{@code SUCCEEDED}/{@code FAILED}）タスクのレコードをTTL経過後に自動削除する
 * 定期処理（設計書「7」）。
 */
@Component
public class AsyncTaskRetentionScheduler {

    private final AsyncTaskExecutionStateStore stateStore;
    private final AsyncTaskProperties properties;

    public AsyncTaskRetentionScheduler(AsyncTaskExecutionStateStore stateStore, AsyncTaskProperties properties) {
        this.stateStore = stateStore;
        this.properties = properties;
    }

    @Scheduled(fixedRate = 60000)
    public void removeExpiredTasks() {
        Duration retention = Duration.ofMinutes(properties.retentionMinutes());
        stateStore.removeIfOlderThan(Instant.now().minus(retention));
    }
}
