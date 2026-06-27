package com.example.demo.task.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.core.MethodParameter;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GlobalExceptionHandler} の単体テスト
 * （テスト計画 EH-01〜EH-05、設計書「4.2」「6.1」「6.2」）。
 *
 * <p>ハンドラメソッドを直接呼び出すことで、レスポンス組み立てロジック（ステータス・ボディ形式）
 * を直接的に検証する。</p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /**
     * EH-01: MethodArgumentNotValidExceptionを捕捉した場合、400・errorCode=VALIDATION_ERRORを組み立てる。
     */
    @Test
    void handleValidationException_returnsBadRequestWithValidationError() throws Exception {
        MethodArgumentNotValidException ex = buildMethodArgumentNotValidException();

        ResponseEntity<ErrorResponse> response = handler.handleValidationException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("ERROR");
        assertThat(response.getBody().errorCode()).isEqualTo("VALIDATION_ERROR");
    }

    /**
     * EH-02: errorCode=TASK_NOT_FOUNDの場合、HttpStatus.NOT_FOUND（404）を組み立てる。
     */
    @Test
    void handleTaskExecutionException_taskNotFound_returnsNotFound() {
        TaskExecutionException ex = new TaskExecutionException(
                TaskExecutionErrorCode.TASK_NOT_FOUND, "unknown task: foo");

        ResponseEntity<ErrorResponse> response = handler.handleTaskExecutionException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("TASK_NOT_FOUND");
    }

    /**
     * EH-03: errorCode=TASK_EXECUTION_FAILEDの場合、HttpStatus.UNPROCESSABLE_ENTITY（422）を組み立てる。
     */
    @Test
    void handleTaskExecutionException_taskExecutionFailed_returnsUnprocessableEntity() {
        TaskExecutionException ex = new TaskExecutionException(
                TaskExecutionErrorCode.TASK_EXECUTION_FAILED, "precondition not satisfied");

        ResponseEntity<ErrorResponse> response = handler.handleTaskExecutionException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("TASK_EXECUTION_FAILED");
    }

    /**
     * EH-04: 想定外のExceptionを捕捉した場合、500・errorCode=INTERNAL_ERRORを組み立てる。
     * スタックトレースや内部実装詳細を含まないことも確認する（設計書「6.1」）。
     */
    @Test
    void handleUnexpectedException_returnsInternalServerErrorWithoutDetails() {
        RuntimeException ex = new RuntimeException("unexpected");

        ResponseEntity<ErrorResponse> response = handler.handleUnexpectedException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("ERROR");
        assertThat(response.getBody().errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message())
                .doesNotContain("RuntimeException")
                .doesNotContain("java.lang")
                .doesNotContain("at com.example");
    }

    /**
     * EH-05: HttpMessageNotReadableExceptionを捕捉した場合、400・errorCode=BAD_REQUESTを組み立てる。
     */
    @Test
    void handleHttpMessageNotReadableException_returnsBadRequest() {
        HttpMessageNotReadableException ex =
                new HttpMessageNotReadableException("malformed JSON request", (org.springframework.http.HttpInputMessage) null);

        ResponseEntity<ErrorResponse> response = handler.handleHttpMessageNotReadableException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("BAD_REQUEST");
    }

    private MethodArgumentNotValidException buildMethodArgumentNotValidException() throws Exception {
        Method method = DummyTarget.class.getMethod("dummyMethod", String.class);
        MethodParameter methodParameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new DummyTarget(), "request");
        bindingResult.addError(new FieldError("request", "taskName", "must not be blank"));
        return new MethodArgumentNotValidException(methodParameter, bindingResult);
    }

    /** バリデーション例外組み立て用のダミー対象クラス。 */
    static class DummyTarget {
        public void dummyMethod(String taskName) {
        }
    }
}
