package com.example.demo.task.async;

/**
 * 非同期タスクの状態遷移を表すenum（設計書「3.2」）。
 *
 * <p>状態遷移は {@code PENDING -> RUNNING -> SUCCEEDED|FAILED} の一方向のみであり、
 * {@code SUCCEEDED}/{@code FAILED} は終端状態として以後遷移しない。</p>
 */
public enum AsyncTaskStatus {
    /** 受付済み、実行スレッドへのディスパッチ待ち。 */
    PENDING,
    /** 非同期実行スレッドで処理中。 */
    RUNNING,
    /** 処理が正常終了。 */
    SUCCEEDED,
    /** 処理が異常終了（業務的な失敗、または想定外の例外）。 */
    FAILED
}
