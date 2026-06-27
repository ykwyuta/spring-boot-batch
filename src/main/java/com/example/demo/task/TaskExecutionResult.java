package com.example.demo.task;

/**
 * {@link TaskExecutionService#execute(String, java.util.Map)} の戻り値。
 *
 * @param message 処理結果メッセージ。
 */
public record TaskExecutionResult(String message) {
}
