package com.example.demo.task.exception;

/**
 * {@link TaskExecutionException} が保持するエラーコード。
 */
public enum TaskExecutionErrorCode {
    /** {@code taskName} に対応する処理が未定義の場合。 */
    TASK_NOT_FOUND,
    /** 処理実行中に業務的な前提条件不成立等が発生した場合。 */
    TASK_EXECUTION_FAILED
}
