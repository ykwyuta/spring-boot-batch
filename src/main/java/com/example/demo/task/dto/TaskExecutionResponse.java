package com.example.demo.task.dto;

import java.time.LocalDateTime;

/**
 * {@code POST /internal/tasks/execute} の正常時レスポンスボディに対応するDTO。
 *
 * @param status      {@code "SUCCESS"} 固定。
 * @param taskName    実行されたタスク名（リクエストの値をそのまま返却）。
 * @param message     Serviceの処理結果メッセージ。
 * @param executedAt  実行完了時刻。
 */
public record TaskExecutionResponse(
        String status,
        String taskName,
        String message,
        LocalDateTime executedAt) {
}
