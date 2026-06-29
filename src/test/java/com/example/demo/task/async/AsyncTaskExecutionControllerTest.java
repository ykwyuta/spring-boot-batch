package com.example.demo.task.async;

import com.example.demo.task.exception.AsyncInputFileNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AsyncTaskExecutionController} の単体テスト
 * （テスト計画 AC-00〜AC-12・GC-01〜GC-02・GS-03〜GS-06、設計書「9.1」分岐 A1〜A6, A10〜A15、
 * 「9.3」分岐 C1, C2, C5〜C8）。
 *
 * <p>{@code LocalhostOnlyInterceptor}（A0a/A0b/C0a/C0b）は {@code @WebMvcTest} の対象クラス限定
 * 起動の範囲外であり、結合シナリオ（IG-01）でのみ疎通確認する（テスト計画セクション1の方針）。</p>
 */
@WebMvcTest(AsyncTaskExecutionController.class)
class AsyncTaskExecutionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AsyncTaskExecutionService asyncTaskExecutionService;

    /**
     * AC-00: リクエストボディがJSONとして不正な形式の場合、既存ハンドラ
     * （handleHttpMessageNotReadableException）が捕捉する（A1）。
     */
    @Test
    void executeAsync_malformedJson_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/internal/tasks/execute-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));

        verifyNoInteractions(asyncTaskExecutionService);
    }

    /**
     * AC-01: taskNameフィールドが無い（null）場合、バリデーションエラーとなる（A2）。
     */
    @Test
    void executeAsync_taskNameMissing_returnsValidationError() throws Exception {
        mockMvc.perform(post("/internal/tasks/execute-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(asyncTaskExecutionService);
    }

    /**
     * AC-02: taskNameが空白文字のみの場合も同様にバリデーションエラー（A2）。
     */
    @Test
    void executeAsync_taskNameBlank_returnsValidationError() throws Exception {
        mockMvc.perform(post("/internal/tasks/execute-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskName\": \"   \", \"inputFilePaths\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    /**
     * AC-03: taskNameが101文字以上の場合、バリデーションエラー（A3）。
     */
    @Test
    void executeAsync_taskNameTooLong_returnsValidationError() throws Exception {
        String taskName = "a".repeat(101);
        mockMvc.perform(post("/internal/tasks/execute-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskName\": \"" + taskName + "\", \"inputFilePaths\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    /**
     * AC-04: taskNameが1文字（下限）の場合、バリデーションを通過しService呼び出しに進む（A4）。
     */
    @Test
    void executeAsync_taskNameOneCharacter_passesValidation() throws Exception {
        String taskName = "a";
        when(asyncTaskExecutionService.acceptAsyncExecution(eq(taskName), eq(Collections.emptyMap()), eq(List.of())))
                .thenReturn(UUID.randomUUID());

        mockMvc.perform(post("/internal/tasks/execute-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskName\": \"" + taskName + "\", \"inputFilePaths\": []}"))
                .andExpect(status().isAccepted());

        verify(asyncTaskExecutionService).acceptAsyncExecution(taskName, Collections.emptyMap(), List.of());
    }

    /**
     * AC-05: taskNameが100文字（上限）の場合もバリデーションを通過する（A4）。
     */
    @Test
    void executeAsync_taskNameHundredCharacters_passesValidation() throws Exception {
        String taskName = "a".repeat(100);
        when(asyncTaskExecutionService.acceptAsyncExecution(eq(taskName), eq(Collections.emptyMap()), eq(List.of())))
                .thenReturn(UUID.randomUUID());

        mockMvc.perform(post("/internal/tasks/execute-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskName\": \"" + taskName + "\", \"inputFilePaths\": []}"))
                .andExpect(status().isAccepted());
    }

    /**
     * AC-06: inputFilePathsの要素に空白のみの文字列を含む場合、バリデーションエラー（A5）。
     */
    @Test
    void executeAsync_inputFilePathsContainsBlankElement_returnsValidationError() throws Exception {
        mockMvc.perform(post("/internal/tasks/execute-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskName\": \"sample-task\", \"inputFilePaths\": [\"   \"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(asyncTaskExecutionService);
    }

    /**
     * AC-07: inputFilePathsが101件以上の場合、バリデーションエラー（A6）。
     */
    @Test
    void executeAsync_inputFilePathsTooMany_returnsValidationError() throws Exception {
        StringBuilder paths = new StringBuilder();
        for (int i = 0; i < 101; i++) {
            if (i > 0) {
                paths.append(",");
            }
            paths.append("\"/tmp/f").append(i).append("\"");
        }

        mockMvc.perform(post("/internal/tasks/execute-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskName\": \"sample-task\", \"inputFilePaths\": [" + paths + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    /**
     * AC-08: Service呼び出し成功時、ControllerがtaskIdをAsyncTaskExecutionAcceptedResponseに変換する（A12）。
     */
    @Test
    void executeAsync_success_buildsAcceptedResponse() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(asyncTaskExecutionService.acceptAsyncExecution(eq("sample-task"), eq(Collections.emptyMap()), eq(List.of())))
                .thenReturn(taskId);

        mockMvc.perform(post("/internal/tasks/execute-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskName\": \"sample-task\", \"inputFilePaths\": []}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.acceptedAt")
                        .value(matchesPattern("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$")));
    }

    /**
     * AC-09: parametersが省略された場合、空のMapとしてServiceに渡される（A14）。
     */
    @Test
    void executeAsync_parametersOmitted_passesEmptyMap() throws Exception {
        when(asyncTaskExecutionService.acceptAsyncExecution(eq("sample-task"), eq(Collections.emptyMap()), eq(List.of())))
                .thenReturn(UUID.randomUUID());

        mockMvc.perform(post("/internal/tasks/execute-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskName\": \"sample-task\", \"inputFilePaths\": []}"))
                .andExpect(status().isAccepted());

        verify(asyncTaskExecutionService).acceptAsyncExecution("sample-task", Collections.emptyMap(), List.of());
    }

    /**
     * AC-10: parametersが指定されている場合、その値をそのままServiceに渡す（A15）。
     */
    @Test
    void executeAsync_parametersProvided_passedAsIs() throws Exception {
        Map<String, String> parameters = Map.of("key1", "value1");
        when(asyncTaskExecutionService.acceptAsyncExecution(eq("sample-task"), eq(parameters), eq(List.of())))
                .thenReturn(UUID.randomUUID());

        mockMvc.perform(post("/internal/tasks/execute-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskName\": \"sample-task\", \"parameters\": {\"key1\": \"value1\"}, "
                                + "\"inputFilePaths\": []}"))
                .andExpect(status().isAccepted());

        verify(asyncTaskExecutionService).acceptAsyncExecution("sample-task", parameters, List.of());
    }

    /**
     * AC-11: acceptAsyncExecutionがRejectedExecutionExceptionをスローした場合、専用ハンドラが捕捉する（A13）。
     */
    @Test
    void executeAsync_threadPoolSaturated_returnsServiceUnavailable() throws Exception {
        when(asyncTaskExecutionService.acceptAsyncExecution(eq("sample-task"), eq(Collections.emptyMap()), eq(List.of())))
                .thenThrow(new RejectedExecutionException("pool saturated"));

        mockMvc.perform(post("/internal/tasks/execute-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskName\": \"sample-task\", \"inputFilePaths\": []}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.errorCode").value("ASYNC_EXECUTOR_BUSY"));
    }

    /**
     * AC-12: acceptAsyncExecutionがAsyncInputFileNotFoundExceptionをスローした場合、専用ハンドラが捕捉する（A10/A11）。
     */
    @Test
    void executeAsync_inputFileNotFound_returnsNotFound() throws Exception {
        when(asyncTaskExecutionService.acceptAsyncExecution(
                eq("sample-task"), eq(Collections.emptyMap()), eq(List.of("/no/such/file"))))
                .thenThrow(new AsyncInputFileNotFoundException("input file not found: /no/such/file"));

        mockMvc.perform(post("/internal/tasks/execute-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskName\": \"sample-task\", \"inputFilePaths\": [\"/no/such/file\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.errorCode").value("INPUT_FILE_NOT_FOUND"));
    }

    /**
     * GC-01: taskIdパス変数が正しいUUID形式の場合、パース成功し状態ストア検索（Service呼び出し）に進む（C1）。
     */
    @Test
    void getStatus_validUuid_callsServiceWithParsedUuid() throws Exception {
        UUID taskId = UUID.randomUUID();
        AsyncTaskRecord record = AsyncTaskRecord.pending(taskId, "sample-task", List.of(), java.time.Instant.now());
        when(asyncTaskExecutionService.getStatus(taskId)).thenReturn(record);

        mockMvc.perform(get("/internal/tasks/{taskId}", taskId.toString()))
                .andExpect(status().isOk());

        verify(asyncTaskExecutionService).getStatus(taskId);
    }

    /**
     * GC-02: taskIdパス変数がUUID形式としてパース不能な場合、AsyncTaskNotFoundExceptionに変換してスローする（C2）。
     */
    @Test
    void getStatus_invalidUuid_returnsNotFoundWithoutCallingService() throws Exception {
        mockMvc.perform(get("/internal/tasks/{taskId}", "not-a-valid-uuid"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.errorCode").value("TASK_NOT_FOUND"));

        verifyNoInteractions(asyncTaskExecutionService);
    }

    /**
     * GS-03: status=PENDINGの場合、message=null・outputFilePaths=[]でレスポンスを組み立てる（C5）。
     */
    @Test
    void getStatus_pending_returnsNullMessageAndEmptyOutputFilePaths() throws Exception {
        UUID taskId = UUID.randomUUID();
        AsyncTaskRecord record = AsyncTaskRecord.pending(taskId, "sample-task", List.of(), Instant.now());
        when(asyncTaskExecutionService.getStatus(taskId)).thenReturn(record);

        mockMvc.perform(get("/internal/tasks/{taskId}", taskId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.outputFilePaths").isArray())
                .andExpect(jsonPath("$.outputFilePaths").isEmpty());
    }

    /**
     * GS-04: status=RUNNINGの場合も同様にmessage=null・outputFilePaths=[]でレスポンスを組み立てる（C6）。
     */
    @Test
    void getStatus_running_returnsNullMessageAndEmptyOutputFilePaths() throws Exception {
        UUID taskId = UUID.randomUUID();
        AsyncTaskRecord record = AsyncTaskRecord.pending(taskId, "sample-task", List.of(), Instant.now())
                .withRunning(Instant.now());
        when(asyncTaskExecutionService.getStatus(taskId)).thenReturn(record);

        mockMvc.perform(get("/internal/tasks/{taskId}", taskId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.outputFilePaths").isArray())
                .andExpect(jsonPath("$.outputFilePaths").isEmpty());
    }

    /**
     * GS-05: status=SUCCEEDEDの場合、message・outputFilePathsを設定してレスポンスを組み立てる（C7）。
     */
    @Test
    void getStatus_succeeded_returnsMessageAndOutputFilePaths() throws Exception {
        UUID taskId = UUID.randomUUID();
        String outputFilePath = "/tmp/async-tasks/" + taskId + "/result.txt";
        AsyncTaskRecord record = AsyncTaskRecord.pending(taskId, "sample-task", List.of(), Instant.now())
                .withSucceeded(List.of(outputFilePath), "task executed successfully", Instant.now());
        when(asyncTaskExecutionService.getStatus(taskId)).thenReturn(record);

        mockMvc.perform(get("/internal/tasks/{taskId}", taskId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.message").value("task executed successfully"))
                .andExpect(jsonPath("$.outputFilePaths[0]").value(outputFilePath));
    }

    /**
     * GS-06: status=FAILEDの場合、HTTPステータスは200のまま（422や4xx系にはならない）でmessageを
     * 設定し、outputFilePaths=[]・errorCodeフィールド自体が存在しないことを確認する（C8、設計書
     * 「8.3」「5.5」の方針）。
     */
    @Test
    void getStatus_failed_returnsOkWithMessageAndNoErrorCodeField() throws Exception {
        UUID taskId = UUID.randomUUID();
        AsyncTaskRecord record = AsyncTaskRecord.pending(
                        taskId, "task-business-failure", List.of(), Instant.now())
                .withFailed("TASK_EXECUTION_FAILED",
                        "precondition not satisfied for task: task-business-failure", Instant.now());
        when(asyncTaskExecutionService.getStatus(taskId)).thenReturn(record);

        mockMvc.perform(get("/internal/tasks/{taskId}", taskId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.message").value("precondition not satisfied for task: task-business-failure"))
                .andExpect(jsonPath("$.outputFilePaths").isArray())
                .andExpect(jsonPath("$.outputFilePaths").isEmpty())
                .andExpect(jsonPath("$.errorCode").doesNotExist());
    }
}
