package com.example.demo.task;

import com.example.demo.task.exception.TaskExecutionErrorCode;
import com.example.demo.task.exception.TaskExecutionException;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TaskExecutionService} の単体テスト
 * （テスト計画 SV-01〜SV-05、設計書「4.2」分岐 #5〜#9）。
 */
class TaskExecutionServiceTest {

    private final TaskExecutionService service = new TaskExecutionService();

    /**
     * SV-01: taskNameに対応する処理が定義されている場合（#5 true分岐）、処理を実行しTaskExecutionResultを返す。
     */
    @Test
    void execute_knownTask_returnsResult() {
        TaskExecutionResult result = service.execute("sample-task", Collections.emptyMap());

        assertThat(result).isNotNull();
        assertThat(result.message()).isEqualTo("task executed successfully");
    }

    /**
     * SV-02: taskNameに対応する処理が未定義の場合（#5 false分岐/#6）、
     * TaskExecutionException（errorCode=TASK_NOT_FOUND）をスローする。
     */
    @Test
    void execute_unknownTask_throwsTaskNotFound() {
        assertThatThrownBy(() -> service.execute("unknown-task", Collections.emptyMap()))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(ex -> {
                    TaskExecutionException taskExecutionException = (TaskExecutionException) ex;
                    assertThat(taskExecutionException.getErrorCode())
                            .isEqualTo(TaskExecutionErrorCode.TASK_NOT_FOUND);
                    assertThat(taskExecutionException.getMessage()).contains("unknown-task");
                });
    }

    /**
     * SV-03: 処理実行中に例外が発生しない場合（#7 正常終了）、TaskExecutionResultを返す。
     */
    @Test
    void execute_normalCompletion_returnsResultWithoutException() {
        TaskExecutionResult result = service.execute("sample-task", Collections.emptyMap());

        assertThat(result).isNotNull();
    }

    /**
     * SV-04: 業務的な前提条件不成立（#8）が発生した場合、
     * TaskExecutionException（errorCode=TASK_EXECUTION_FAILED）をスローする。
     * ダミータスク {@code task-business-failure} を用いて再現する。
     */
    @Test
    void execute_businessFailureTask_throwsTaskExecutionFailed() {
        assertThatThrownBy(() -> service.execute(
                TaskExecutionService.TASK_NAME_BUSINESS_FAILURE, Collections.emptyMap()))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(ex -> {
                    TaskExecutionException taskExecutionException = (TaskExecutionException) ex;
                    assertThat(taskExecutionException.getErrorCode())
                            .isEqualTo(TaskExecutionErrorCode.TASK_EXECUTION_FAILED);
                });
    }

    /**
     * SV-05: 想定外の実行時例外（#9）が発生した場合、TaskExecutionExceptionでラップせずそのまま伝播する。
     * ダミータスク {@code task-unexpected-error} を用いて再現する。
     */
    @Test
    void execute_unexpectedErrorTask_propagatesRawRuntimeException() {
        assertThatThrownBy(() -> service.execute(
                TaskExecutionService.TASK_NAME_UNEXPECTED_ERROR, Collections.emptyMap()))
                .isInstanceOf(NullPointerException.class)
                .isNotInstanceOf(TaskExecutionException.class);
    }
}
