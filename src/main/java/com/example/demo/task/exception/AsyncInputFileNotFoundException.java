package com.example.demo.task.exception;

/**
 * {@code inputFilePaths} のいずれかがファイルシステム上に存在しない場合、またはディレクトリ
 * （通常ファイルでない）場合にスローする例外（設計書「2.1」「5.3」）。
 *
 * <p>{@code GlobalExceptionHandler} が{@code 404 Not Found}
 * （{@code errorCode=INPUT_FILE_NOT_FOUND}）に変換する。</p>
 */
public class AsyncInputFileNotFoundException extends RuntimeException {

    public AsyncInputFileNotFoundException(String message) {
        super(message);
    }
}
