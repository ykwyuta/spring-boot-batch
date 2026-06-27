package com.example.demo.task.exception;

import java.time.Instant;

/**
 * 異常時の共通エラーレスポンスフォーマット（設計書「4.3」）。
 *
 * @param status     {@code "ERROR"} 固定。
 * @param errorCode  エラー種別を表すコード（例: {@code VALIDATION_ERROR}）。
 * @param message    エラー内容を表すメッセージ。
 * @param timestamp  エラー発生時刻。
 */
public record ErrorResponse(
        String status,
        String errorCode,
        String message,
        Instant timestamp) {
}
