package com.example.demo.task.async;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 状態ストアが保持する1タスク分のレコード（設計書「3.3」）。
 *
 * <p>イミュータブルな{@code record}として実装し、状態遷移のたびに{@code withXxx}メソッドで
 * 新しいインスタンスを生成する。生成したインスタンスは
 * {@link AsyncTaskExecutionStateStore#update(UUID, java.util.function.UnaryOperator)} 経由で
 * 状態ストアに再格納する。</p>
 *
 * @param taskId         トラッキングキー。
 * @param status         現在の状態。
 * @param taskName       実行された（実行中の）タスク名。
 * @param inputFilePaths リクエストされた入力ファイルパス一覧。
 * @param outputFilePaths 出力ファイルパス一覧。{@code SUCCEEDED}時のみ値を持つ。
 * @param message        完了時のメッセージ。未完了時は{@code null}。
 * @param errorCode      失敗時の内部エラーコード。{@code AsyncTaskStatusResponse}には含めない
 *                        （設計書「5.5」「8.2」）。
 * @param createdAt      非同期実行リクエストを受理した時刻。
 * @param updatedAt       状態が最後に更新された時刻。
 */
public record AsyncTaskRecord(
        UUID taskId,
        AsyncTaskStatus status,
        String taskName,
        List<String> inputFilePaths,
        List<String> outputFilePaths,
        String message,
        String errorCode,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 非同期実行リクエスト受理直後（T1）の初期レコードを生成する。
     */
    static AsyncTaskRecord pending(UUID taskId, String taskName, List<String> inputFilePaths, Instant now) {
        return new AsyncTaskRecord(
                taskId, AsyncTaskStatus.PENDING, taskName, inputFilePaths, List.of(), null, null, now, now);
    }

    /**
     * {@code RUNNING} への遷移（T2）。
     */
    AsyncTaskRecord withRunning(Instant now) {
        return new AsyncTaskRecord(
                taskId, AsyncTaskStatus.RUNNING, taskName, inputFilePaths, outputFilePaths, message, errorCode,
                createdAt, now);
    }

    /**
     * {@code SUCCEEDED} への遷移（T3）。
     */
    AsyncTaskRecord withSucceeded(List<String> outputFilePaths, String message, Instant now) {
        return new AsyncTaskRecord(
                taskId, AsyncTaskStatus.SUCCEEDED, taskName, inputFilePaths, outputFilePaths, message, null,
                createdAt, now);
    }

    /**
     * {@code FAILED} への遷移（T4）。
     */
    AsyncTaskRecord withFailed(String errorCode, String message, Instant now) {
        return new AsyncTaskRecord(
                taskId, AsyncTaskStatus.FAILED, taskName, inputFilePaths, List.of(), message, errorCode,
                createdAt, now);
    }
}
