package com.example.demo.task;

import com.example.demo.task.exception.TaskExecutionErrorCode;
import com.example.demo.task.exception.TaskExecutionException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * デモとしての「与えられたタスク名・パラメータに基づき何らかの処理を行い、結果文字列を返す」
 * 業務処理本体（設計書「3.4」）。
 *
 * <p>本実装では既知のタスク名をハードコードで定義する。デモ用途として、以下の特定のタスク名で
 * 異常系の分岐（テスト計画SV-04/SV-05）を再現できるようにしている。</p>
 * <ul>
 *     <li>{@code task-business-failure}: 業務的な前提条件不成立を再現し、
 *         {@link TaskExecutionException}（{@code errorCode=TASK_EXECUTION_FAILED}）をスローする。</li>
 *     <li>{@code task-unexpected-error}: 想定外の実行時例外（{@link NullPointerException}）を
 *         再現し、ラップせずそのまま伝播させる。</li>
 *     <li>{@code sample-task}: 正常終了するデモタスク。</li>
 * </ul>
 * <p>上記以外の未知のタスク名は {@code TASK_NOT_FOUND} として扱う。</p>
 */
@Service
public class TaskExecutionService {

    /** 業務的な失敗（前提条件不成立）を再現するためのデモ用タスク名。 */
    public static final String TASK_NAME_BUSINESS_FAILURE = "task-business-failure";

    /** 想定外の実行時例外を再現するためのデモ用タスク名。 */
    public static final String TASK_NAME_UNEXPECTED_ERROR = "task-unexpected-error";

    private static final Set<String> KNOWN_TASK_NAMES = Set.of(
            "sample-task",
            TASK_NAME_BUSINESS_FAILURE,
            TASK_NAME_UNEXPECTED_ERROR);

    public TaskExecutionResult execute(String taskName, Map<String, String> parameters) {
        if (!KNOWN_TASK_NAMES.contains(taskName)) {
            throw new TaskExecutionException(
                    TaskExecutionErrorCode.TASK_NOT_FOUND, "unknown task: " + taskName);
        }

        if (TASK_NAME_BUSINESS_FAILURE.equals(taskName)) {
            throw new TaskExecutionException(
                    TaskExecutionErrorCode.TASK_EXECUTION_FAILED,
                    "precondition not satisfied for task: " + taskName);
        }

        if (TASK_NAME_UNEXPECTED_ERROR.equals(taskName)) {
            // 想定外の実行時例外を再現するデモ実装。TaskExecutionExceptionでラップせず
            // そのまま伝播させ、GlobalExceptionHandler.handleUnexpectedExceptionで捕捉させる。
            throw new NullPointerException("unexpected null reference while executing task: " + taskName);
        }

        return new TaskExecutionResult("task executed successfully");
    }
}
