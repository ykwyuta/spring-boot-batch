package com.example.demo.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * {@code POST /internal/tasks/execute} のリクエストボディに対応するDTO。
 *
 * @param taskName   実行するタスクの識別子。必須、1〜100文字。
 * @param parameters タスクに渡す追加パラメータ。省略時は空Mapとして扱う。
 */
public record TaskExecutionRequest(
        @NotBlank
        @Size(max = 100)
        String taskName,
        Map<String, String> parameters) {
}
