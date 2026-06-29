package com.example.demo.task.async;

import com.example.demo.task.TaskExecutionService;
import com.example.demo.task.exception.AsyncInputFileNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AsyncTaskExecutionService#acceptAsyncExecution(String, Map, List)} の単体テスト
 * （テスト計画 AS-01〜AS-07、設計書「9.1」分岐 A7〜A13）。
 *
 * <p>{@code AsyncTaskExecutionStateStore} は実インスタンスを用いて状態ストアへの登録・削除を
 * 実際に確認する。{@code self}（自己呼び出し用プロキシ参照）にはスタブ実装を渡し、
 * 「ディスパッチ呼び出し自体が例外をスローするか否か」のみを制御する
 * （依頼事項に記載の方式。実際の {@code @Async} スレッドプール経由のディスパッチは検証しない）。</p>
 */
class AsyncTaskExecutionServiceAcceptTest {

    private AsyncTaskExecutionStateStore stateStore;

    @BeforeEach
    void setUp() {
        stateStore = Mockito.spy(new AsyncTaskExecutionStateStore());
    }

    /**
     * AS-01: inputFilePathsがnull（省略）の場合、空リストとして扱い検証をスキップして処理続行する（A7）。
     */
    @Test
    void acceptAsyncExecution_inputFilePathsNull_treatedAsEmptyList() {
        AsyncTaskExecutionService service = newServiceWithImmediateDispatch();

        UUID taskId = service.acceptAsyncExecution("sample-task", Map.of(), null);

        assertThat(taskId).isNotNull();
        Optional<AsyncTaskRecord> record = stateStore.find(taskId);
        assertThat(record).isPresent();
        assertThat(record.get().inputFilePaths()).isEmpty();
    }

    /**
     * AS-02: inputFilePathsが空リスト（List.of()）の場合、検証対象が存在しないため常に検証を通過する（A8）。
     */
    @Test
    void acceptAsyncExecution_inputFilePathsEmptyList_skipsValidation() {
        AsyncTaskExecutionService service = newServiceWithImmediateDispatch();

        UUID taskId = service.acceptAsyncExecution("sample-task", Map.of(), List.of());

        assertThat(taskId).isNotNull();
        Optional<AsyncTaskRecord> record = stateStore.find(taskId);
        assertThat(record).isPresent();
        assertThat(record.get().inputFilePaths()).isEmpty();
    }

    /**
     * AS-03: inputFilePathsの各要素がすべて存在する通常ファイルの場合、検証を通過しPENDING登録へ進む（A9）。
     */
    @Test
    void acceptAsyncExecution_allInputFilesExist_passesValidation(@TempDir Path tempDir) throws Exception {
        Path file1 = tempDir.resolve("input1.txt");
        Path file2 = tempDir.resolve("input2.txt");
        Files.writeString(file1, "content1");
        Files.writeString(file2, "content2");
        List<String> inputFilePaths = List.of(file1.toString(), file2.toString());

        AsyncTaskExecutionService service = newServiceWithImmediateDispatch();

        UUID taskId = service.acceptAsyncExecution("sample-task", Map.of(), inputFilePaths);

        assertThat(taskId).isNotNull();
        Optional<AsyncTaskRecord> record = stateStore.find(taskId);
        assertThat(record).isPresent();
        assertThat(record.get().inputFilePaths()).isEqualTo(inputFilePaths);
    }

    /**
     * AS-04: inputFilePathsのいずれかのパスがファイルシステム上に存在しない場合、
     * AsyncInputFileNotFoundExceptionがスローされる。状態ストアにレコードは残らない（A10）。
     */
    @Test
    void acceptAsyncExecution_inputFileMissing_throwsAndDoesNotRegister() {
        String missingPath = "/no/such/file-" + UUID.randomUUID();
        AsyncTaskExecutionService service = newServiceWithImmediateDispatch();

        assertThatThrownBy(() -> service.acceptAsyncExecution("sample-task", Map.of(), List.of(missingPath)))
                .isInstanceOf(AsyncInputFileNotFoundException.class);

        // 入力ファイル検証は taskId 生成・状態ストア登録より前に行われるため、save は一度も呼ばれない。
        Mockito.verify(stateStore, Mockito.never()).save(Mockito.any());
    }

    /**
     * AS-05: inputFilePathsのいずれかのパスがディレクトリ（通常ファイルでない）の場合、
     * AsyncInputFileNotFoundExceptionがスローされる。状態ストアにレコードは残らない（A11）。
     */
    @Test
    void acceptAsyncExecution_inputPathIsDirectory_throwsAndDoesNotRegister(@TempDir Path tempDir) {
        AsyncTaskExecutionService service = newServiceWithImmediateDispatch();

        assertThatThrownBy(() ->
                service.acceptAsyncExecution("sample-task", Map.of(), List.of(tempDir.toString())))
                .isInstanceOf(AsyncInputFileNotFoundException.class);

        Mockito.verify(stateStore, Mockito.never()).save(Mockito.any());
    }

    /**
     * AS-06: スレッドプール・キューに空きがある場合、ディスパッチ成功しPENDINGレコードを
     * 保持したままtaskIdを返す（A12）。
     */
    @Test
    void acceptAsyncExecution_dispatchSucceeds_keepsRecordAndReturnsTaskId() {
        AsyncTaskExecutionService service = newServiceWithImmediateDispatch();

        UUID taskId = service.acceptAsyncExecution("sample-task", Map.of(), List.of());

        assertThat(taskId).isNotNull();
        assertThat(stateStore.find(taskId)).isPresent();
    }

    /**
     * AS-07: スレッドプール・キューが飽和している場合、RejectedExecutionExceptionが発生し
     * 登録済みPENDINGレコードを削除してから例外を再スローする（A13）。
     */
    @Test
    void acceptAsyncExecution_dispatchRejected_removesPendingRecordAndRethrows() {
        AsyncTaskExecutionService self = Mockito.mock(AsyncTaskExecutionService.class);
        Mockito.doThrow(new RejectedExecutionException("pool saturated"))
                .when(self).executeAsync(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        AsyncTaskExecutionService service = new AsyncTaskExecutionService(
                Mockito.mock(TaskExecutionService.class), stateStore, self);

        assertThatThrownBy(() -> service.acceptAsyncExecution("sample-task", Map.of(), List.of()))
                .isInstanceOf(RejectedExecutionException.class);

        // taskIdは例外発生時には呼び出し元に返却されないため、save()に渡されたレコードから
        // 実際に生成されたtaskIdを捕捉し、それを使ってfindが空であることを確認する。
        ArgumentCaptor<AsyncTaskRecord> savedRecord = ArgumentCaptor.forClass(AsyncTaskRecord.class);
        Mockito.verify(stateStore).save(savedRecord.capture());
        UUID taskId = savedRecord.getValue().taskId();
        assertThat(stateStore.find(taskId)).isEmpty();
    }

    /**
     * 即時（同一スレッドで同期的に）ディスパッチを行うselfスタブを用いてServiceを構築する。
     * executeAsyncの呼び出し自体は記録されるが、実処理（RUNNING遷移等）はセクション3で別途検証する
     * ため、ここでは何もしないスタブとする。
     */
    private AsyncTaskExecutionService newServiceWithImmediateDispatch() {
        AsyncTaskExecutionService self = Mockito.mock(AsyncTaskExecutionService.class);
        // 何もしない（即時ディスパッチ成功を模したスタブ）。
        return new AsyncTaskExecutionService(Mockito.mock(TaskExecutionService.class), stateStore, self);
    }
}
