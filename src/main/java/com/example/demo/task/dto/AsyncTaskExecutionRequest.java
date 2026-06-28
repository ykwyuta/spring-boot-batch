package com.example.demo.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * {@code POST /internal/tasks/execute-async} のリクエストボディに対応するDTO（設計書「5.2」）。
 *
 * @param taskName       実行するタスクの識別子。必須、1〜100文字（既存同期APIと同じ制約）。
 * @param parameters     タスクに渡す追加パラメータ。省略時は空Mapとして扱う。
 * @param inputFilePaths ローカルファイルシステム上の入力ファイルパス（絶対パス）一覧。
 *                        各要素は空文字・空白のみ不可。最大100件。省略時は空リストとして扱う。
 */
public record AsyncTaskExecutionRequest(
        @NotBlank
        @Size(max = 100)
        String taskName,
        Map<String, String> parameters,
        @Size(max = 100)
        List<@NotBlank String> inputFilePaths) {
}
