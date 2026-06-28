package com.example.demo.task.async;

import com.example.demo.task.TaskExecutionResult;
import com.example.demo.task.TaskExecutionService;
import com.example.demo.task.exception.AsyncInputFileNotFoundException;
import com.example.demo.task.exception.AsyncTaskNotFoundException;
import com.example.demo.task.exception.TaskExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * 非同期実行の起点・実行本体（設計書「2.1」「4」「5.6」）。
 *
 * <p>既存の {@link TaskExecutionService} には依存するが変更は加えない。入力ファイルパスの存在
 * 検証、状態ストアへの初期レコード登録（{@code PENDING}）、{@code @Async} メソッドの呼び出し
 * （{@code RUNNING} への遷移と既存 {@code TaskExecutionService.execute(...)} の実体呼び出し、
 * 出力ファイルパスの決定、結果の状態ストアへの反映）を担う。</p>
 */
@Service
public class AsyncTaskExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(AsyncTaskExecutionService.class);

    private static final String OUTPUT_FILE_PATH_FORMAT = "/tmp/async-tasks/%s/result.txt";
    private static final String SAMPLE_TASK_NAME = "sample-task";
    private static final String ERROR_CODE_INTERNAL_ERROR = "INTERNAL_ERROR";

    private final TaskExecutionService taskExecutionService;
    private final AsyncTaskExecutionStateStore stateStore;
    private final AsyncTaskExecutionService self;

    /**
     * {@code self} は自分自身のSpring AOPプロキシを遅延注入したものである。{@code @Async} は
     * メソッド呼び出しがプロキシ経由であることを前提に動作するため、{@code acceptAsyncExecution}
     * から同一クラス内の {@code executeAsync} を直接（{@code this.executeAsync(...)}）呼び出すと
     * プロキシをバイパスし非同期実行されない（Spring AOPの自己呼び出しに関する既知の制約）。
     * {@code this} ではなく {@code self}（プロキシ越しの自己参照）経由で呼び出すことでこれを回避する。
     */
    public AsyncTaskExecutionService(TaskExecutionService taskExecutionService,
                                      AsyncTaskExecutionStateStore stateStore,
                                      @Lazy AsyncTaskExecutionService self) {
        this.taskExecutionService = taskExecutionService;
        this.stateStore = stateStore;
        this.self = self;
    }

    /**
     * 非同期実行リクエストを受理する（設計書「6.1」手順4、同期処理）。
     *
     * <p>入力ファイル存在検証、{@code PENDING}登録、{@code @Async}メソッドへのディスパッチを行い、
     * {@code taskId}を返す。</p>
     *
     * @throws AsyncInputFileNotFoundException {@code inputFilePaths} のいずれかが存在しない、
     *                                          またはディレクトリの場合（A10/A11）。
     * @throws RejectedExecutionException      スレッドプール・キューが飽和している場合（A13）。
     *                                          登録済みの{@code PENDING}レコードは削除してから
     *                                          再スローする。
     */
    public UUID acceptAsyncExecution(String taskName, Map<String, String> parameters, List<String> inputFilePaths) {
        List<String> resolvedInputFilePaths = inputFilePaths != null ? inputFilePaths : List.of();
        Map<String, String> resolvedParameters = parameters != null ? parameters : Collections.emptyMap();

        validateInputFilesExist(resolvedInputFilePaths);

        UUID taskId = UUID.randomUUID();
        Instant now = Instant.now();
        stateStore.save(AsyncTaskRecord.pending(taskId, taskName, resolvedInputFilePaths, now));

        try {
            self.executeAsync(taskId, taskName, resolvedParameters, resolvedInputFilePaths);
        } catch (RejectedExecutionException ex) {
            stateStore.remove(taskId);
            throw ex;
        }

        return taskId;
    }

    /**
     * 実際の非同期実行本体（設計書「4.4」）。
     *
     * <p>メソッド内で発生するすべての例外を必ず捕捉し、状態ストアを{@code FAILED}に更新してから
     * 握る（呼び出し元には何も伝播させない）。</p>
     */
    @Async("asyncTaskExecutor")
    public void executeAsync(UUID taskId, String taskName, Map<String, String> parameters,
                              List<String> inputFilePaths) {
        stateStore.update(taskId, record -> record.withRunning(Instant.now()));
        try {
            TaskExecutionResult result = taskExecutionService.execute(taskName, parameters);
            List<String> outputFilePaths = resolveOutputFilePaths(taskId, taskName);
            stateStore.update(taskId, record ->
                    record.withSucceeded(outputFilePaths, result.message(), Instant.now()));
        } catch (TaskExecutionException ex) {
            stateStore.update(taskId, record ->
                    record.withFailed(ex.getErrorCode().name(), ex.getMessage(), Instant.now()));
        } catch (Exception ex) {
            logger.error("unexpected error occurred while executing async task {}", taskId, ex);
            stateStore.update(taskId, record ->
                    record.withFailed(ERROR_CODE_INTERNAL_ERROR, "unexpected error occurred", Instant.now()));
        }
    }

    /**
     * 状態ストアからレコードを取得する（設計書「6.2」手順4）。
     *
     * @throws AsyncTaskNotFoundException レコードが存在しない場合（未発行、またはTTLで削除済み）。
     */
    public AsyncTaskRecord getStatus(UUID taskId) {
        return stateStore.find(taskId)
                .orElseThrow(() -> new AsyncTaskNotFoundException("async task not found: " + taskId));
    }

    private void validateInputFilesExist(List<String> inputFilePaths) {
        for (String inputFilePath : inputFilePaths) {
            Path path = Path.of(inputFilePath);
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                throw new AsyncInputFileNotFoundException("input file not found: " + inputFilePath);
            }
        }
    }

    /**
     * 出力ファイルパスを決定する（設計書「5.4」）。{@code sample-task} の場合のみ1件のパスを生成し、
     * それ以外（処理が失敗するデモ用タスク名）は空リストを返す。
     */
    private List<String> resolveOutputFilePaths(UUID taskId, String taskName) {
        if (SAMPLE_TASK_NAME.equals(taskName)) {
            return List.of(String.format(OUTPUT_FILE_PATH_FORMAT, taskId));
        }
        return List.of();
    }
}
