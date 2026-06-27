package com.example.demo.task.exception;

/**
 * {@code TaskExecutionService} 内で業務的に処理が失敗したことを表す例外。
 *
 * <p>発生原因に応じた {@link TaskExecutionErrorCode} を保持する。同一クラスで
 * {@code TASK_NOT_FOUND} / {@code TASK_EXECUTION_FAILED} の両方を表現する方針については
 * 設計書「6.3」を参照。</p>
 */
public class TaskExecutionException extends RuntimeException {

    private final TaskExecutionErrorCode errorCode;

    public TaskExecutionException(TaskExecutionErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public TaskExecutionErrorCode getErrorCode() {
        return errorCode;
    }
}
