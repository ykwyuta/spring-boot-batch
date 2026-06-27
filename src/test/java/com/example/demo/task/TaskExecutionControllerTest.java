package com.example.demo.task;

import com.example.demo.task.dto.TaskExecutionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.Map;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TaskExecutionController} の単体テスト
 * （テスト計画 CT-01〜CT-09、設計書「4.2」分岐 #1〜#4、#10〜#11）。
 *
 * <p>{@code LocalhostOnlyInterceptor} は {@code @WebMvcTest} の対象クラス限定起動の範囲外であり、
 * 本テストではController本来のロジックの検証に焦点を当てる（テスト計画セクション2の方針）。</p>
 */
@WebMvcTest(TaskExecutionController.class)
class TaskExecutionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TaskExecutionService taskExecutionService;

    /**
     * CT-01: リクエストボディが不正なJSONの場合、HttpMessageNotReadableExceptionをGlobalExceptionHandlerが捕捉する。
     */
    @Test
    void execute_malformedJson_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/internal/tasks/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    /**
     * CT-02: taskNameフィールドが無い（null）場合、バリデーションエラーとなる。
     */
    @Test
    void execute_taskNameMissing_returnsValidationError() throws Exception {
        mockMvc.perform(post("/internal/tasks/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    /**
     * CT-03: taskNameが空白文字のみの場合も同様にバリデーションエラーとなる。
     */
    @Test
    void execute_taskNameBlank_returnsValidationError() throws Exception {
        mockMvc.perform(post("/internal/tasks/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskName\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    /**
     * CT-04: taskNameが101文字以上の場合、バリデーションエラーとなる。
     */
    @Test
    void execute_taskNameTooLong_returnsValidationError() throws Exception {
        String taskName = "a".repeat(101);
        mockMvc.perform(post("/internal/tasks/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskName\": \"" + taskName + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    /**
     * CT-05: taskNameが1文字（下限）の場合、バリデーションを通過しService呼び出しに進む。
     */
    @Test
    void execute_taskNameOneCharacter_passesValidation() throws Exception {
        String taskName = "a";
        when(taskExecutionService.execute(eq(taskName), eq(Collections.emptyMap())))
                .thenReturn(new TaskExecutionResult("task executed successfully"));

        mockMvc.perform(post("/internal/tasks/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskName\": \"" + taskName + "\"}"))
                .andExpect(status().isOk());

        verify(taskExecutionService).execute(taskName, Collections.emptyMap());
    }

    /**
     * CT-06: taskNameが100文字（上限）の場合もバリデーションを通過する。
     */
    @Test
    void execute_taskNameHundredCharacters_passesValidation() throws Exception {
        String taskName = "a".repeat(100);
        when(taskExecutionService.execute(eq(taskName), eq(Collections.emptyMap())))
                .thenReturn(new TaskExecutionResult("task executed successfully"));

        mockMvc.perform(post("/internal/tasks/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskName\": \"" + taskName + "\"}"))
                .andExpect(status().isOk());

        verify(taskExecutionService).execute(taskName, Collections.emptyMap());
    }

    /**
     * CT-07: parametersが省略された場合、空のMapとしてServiceに渡される。
     */
    @Test
    void execute_parametersOmitted_passesEmptyMap() throws Exception {
        when(taskExecutionService.execute(eq("sample-task"), eq(Collections.emptyMap())))
                .thenReturn(new TaskExecutionResult("task executed successfully"));

        mockMvc.perform(post("/internal/tasks/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskName\": \"sample-task\"}"))
                .andExpect(status().isOk());

        verify(taskExecutionService).execute("sample-task", Collections.emptyMap());
    }

    /**
     * CT-08: parametersが指定されている場合、その値がそのままServiceに渡される。
     */
    @Test
    void execute_parametersProvided_passedAsIs() throws Exception {
        Map<String, String> parameters = Map.of("key1", "value1");
        when(taskExecutionService.execute(eq("sample-task"), eq(parameters)))
                .thenReturn(new TaskExecutionResult("task executed successfully"));

        mockMvc.perform(post("/internal/tasks/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskName\": \"sample-task\", \"parameters\": {\"key1\": \"value1\"}}"))
                .andExpect(status().isOk());

        verify(taskExecutionService).execute("sample-task", parameters);
    }

    /**
     * CT-09: Service呼び出し成功時、ControllerがTaskExecutionResultをTaskExecutionResponseに変換する
     * （設計書「4.2」分岐 #5（真）・#7（正常終了）に付随する正常系レスポンス組み立ての確認ケース。
     * テスト計画レビュー指摘事項への対応として、対象分岐をこのテストコード上で明記する）。
     */
    @Test
    void execute_success_buildsResponseFromResult() throws Exception {
        when(taskExecutionService.execute(eq("sample-task"), eq(Collections.emptyMap())))
                .thenReturn(new TaskExecutionResult("task executed successfully"));

        mockMvc.perform(post("/internal/tasks/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskName\": \"sample-task\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.taskName").value("sample-task"))
                .andExpect(jsonPath("$.message").value("task executed successfully"))
                // executedAt がISO-8601形式（LocalDateTimeのデフォルト直列化形式）の文字列であることを確認
                .andExpect(jsonPath("$.executedAt")
                        .value(matchesPattern("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?$")));
    }

    // 以下は参考用: TaskExecutionRequestのフィールド存在確認（コンパイル時の整合性確認用）
    @SuppressWarnings("unused")
    private static TaskExecutionRequest sampleRequest() {
        return new TaskExecutionRequest("sample-task", Collections.emptyMap());
    }
}
