package com.example.demo.task.exception;

/**
 * 不正な形式の{@code taskId}、または存在しない{@code taskId}が指定された場合にスローする例外
 * （設計書「2.1」「6.3」）。
 *
 * <p>{@code GlobalExceptionHandler} が{@code 404 Not Found}（{@code errorCode=TASK_NOT_FOUND}）に
 * 変換する。既存の{@link TaskExecutionException}（{@code TaskExecutionErrorCode.TASK_NOT_FOUND}）
 * とは異なる例外クラスであり、既存enumへの値追加は行わない（既存コード非破壊の原則）。</p>
 */
public class AsyncTaskNotFoundException extends RuntimeException {

    public AsyncTaskNotFoundException(String message) {
        super(message);
    }
}
