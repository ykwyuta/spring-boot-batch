package com.example.demo.task.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GlobalExceptionHandler} の新規追加分（非同期タスク機能向け）の単体テスト
 * （テスト計画 EH-A01〜EH-A04、設計書「9」分岐 A10/A11/A13・C2/C4 のハンドラ側）。
 *
 * <p>既存4メソッド（{@code handleHttpMessageNotReadableException}等）は変更されないため
 * {@link GlobalExceptionHandlerTest}（EH-01〜EH-05）の対象であり、本クラスでは再掲しない
 * （テスト計画セクション6の方針）。</p>
 */
class GlobalExceptionHandlerAsyncTaskTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /**
     * EH-A01: AsyncTaskNotFoundExceptionを捕捉した場合、404・errorCode=TASK_NOT_FOUNDを組み立てる
     * （GC-02／GS-02と連動）。
     */
    @Test
    void handleAsyncTaskNotFoundException_returnsNotFound() {
        AsyncTaskNotFoundException ex = new AsyncTaskNotFoundException("async task not found: dummy");

        ResponseEntity<ErrorResponse> response = handler.handleAsyncTaskNotFoundException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("ERROR");
        assertThat(response.getBody().errorCode()).isEqualTo("TASK_NOT_FOUND");
    }

    /**
     * EH-A02: AsyncInputFileNotFoundExceptionを捕捉した場合、404・errorCode=INPUT_FILE_NOT_FOUNDを
     * 組み立てる（AC-12／AS-04／AS-05と連動）。
     */
    @Test
    void handleAsyncInputFileNotFoundException_returnsNotFound() {
        AsyncInputFileNotFoundException ex = new AsyncInputFileNotFoundException("input file not found: /no/such/file");

        ResponseEntity<ErrorResponse> response = handler.handleAsyncInputFileNotFoundException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("ERROR");
        assertThat(response.getBody().errorCode()).isEqualTo("INPUT_FILE_NOT_FOUND");
    }

    /**
     * EH-A03: RejectedExecutionExceptionを捕捉した場合、503・errorCode=ASYNC_EXECUTOR_BUSYを組み立てる
     * （AC-11／AS-07と連動）。
     */
    @Test
    void handleRejectedExecutionException_returnsServiceUnavailable() {
        RejectedExecutionException ex = new RejectedExecutionException("pool saturated");

        ResponseEntity<ErrorResponse> response = handler.handleRejectedExecutionException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("ERROR");
        assertThat(response.getBody().errorCode()).isEqualTo("ASYNC_EXECUTOR_BUSY");
    }

    /**
     * EH-A04（回帰確認）: 新規3例外はいずれも既存の汎用ハンドラ（handleUnexpectedException、
     * 500・INTERNAL_ERROR）には到達せず、専用ハンドラの期待結果通りのステータス・errorCodeになる
     * ことを確認する。{@code @ExceptionHandler}の型ディスパッチ自体はSpringのMVCインフラに
     * 委ねられているため、ここでは各専用ハンドラの戻り値がhandleUnexpectedExceptionの戻り値
     * （500・INTERNAL_ERROR）と異なることを明示的に確認する形で代替する。
     */
    @Test
    void dedicatedHandlers_neverFallBackToUnexpectedExceptionHandler() {
        ResponseEntity<ErrorResponse> taskNotFound =
                handler.handleAsyncTaskNotFoundException(new AsyncTaskNotFoundException("not found"));
        ResponseEntity<ErrorResponse> inputFileNotFound = handler.handleAsyncInputFileNotFoundException(
                new AsyncInputFileNotFoundException("not found"));
        ResponseEntity<ErrorResponse> executorBusy =
                handler.handleRejectedExecutionException(new RejectedExecutionException("busy"));

        for (ResponseEntity<ErrorResponse> response : List.of(taskNotFound, inputFileNotFound, executorBusy)) {
            assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().errorCode()).isNotEqualTo("INTERNAL_ERROR");
        }
        assertThat(taskNotFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(inputFileNotFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(executorBusy.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
