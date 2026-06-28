package com.example.demo.task.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /internal/tasks/{taskId}} のレスポンスボディに対応するDTO（設計書「5.5」）。
 *
 * <p>ステータス確認・結果取得の両エンドポイントで共用する（本機能では1本のエンドポイントに統合
 * 済み）。未完了時（{@code PENDING}/{@code RUNNING}）は{@code message}が{@code null}、
 * {@code outputFilePaths}が空配列になる。{@code errorCode}はこのレスポンスには含めない方針
 * （設計書「5.5」「8.2」）。</p>
 *
 * @param taskId          リクエストされたトラッキングキー（そのまま返す）。
 * @param status          {@code PENDING}/{@code RUNNING}/{@code SUCCEEDED}/{@code FAILED} のいずれか。
 * @param taskName        実行された（実行中の）タスク名。
 * @param message         完了時のみ値を持つ。未完了時は{@code null}。
 * @param outputFilePaths {@code SUCCEEDED} 時のみ値を持つ。未完了・{@code FAILED} 時は空配列。
 * @param createdAt       非同期実行リクエストを受理した時刻。
 * @param updatedAt        状態が最後に更新された時刻。
 */
public record AsyncTaskStatusResponse(
        UUID taskId,
        String status,
        String taskName,
        String message,
        List<String> outputFilePaths,
        Instant createdAt,
        Instant updatedAt) {
}
