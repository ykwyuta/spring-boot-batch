package com.example.demo.task.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code POST /internal/tasks/execute-async} の正常時（{@code 202 Accepted}）レスポンスボディに
 * 対応するDTO（設計書「5.2」）。
 *
 * @param taskId     トラッキングキー。以後のステータス確認・結果取得APIで使用する。
 * @param status     {@code "PENDING"} 固定（受付直後の状態）。
 * @param acceptedAt リクエスト受理時刻。
 */
public record AsyncTaskExecutionAcceptedResponse(
        UUID taskId,
        String status,
        Instant acceptedAt) {
}
