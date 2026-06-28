package com.example.demo.task.async;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * {@link AsyncTaskRecord} を {@code ConcurrentHashMap} で保持する状態ストア（設計書「3.1」）。
 *
 * <p>シングルトンBeanとして1インスタンスのみ生成され、{@code AsyncTaskExecutionService} に
 * コンストラクタインジェクションされる。キーは {@link UUID} をそのまま使う（方式検討「トラッキング
 * キーの生成方式」の結論を踏襲し、追加の抽象化は導入しない）。</p>
 */
@Component
public class AsyncTaskExecutionStateStore {

    private final ConcurrentHashMap<UUID, AsyncTaskRecord> records = new ConcurrentHashMap<>();

    /**
     * レコードを新規登録する（T1）。
     */
    public void save(AsyncTaskRecord record) {
        records.put(record.taskId(), record);
    }

    /**
     * {@code taskId} に対応するレコードを取得する。存在しない場合は呼び出し側に
     * 「taskIdが存在しない」分岐の処理を強制するため、{@link Optional} で返す。
     */
    public Optional<AsyncTaskRecord> find(UUID taskId) {
        return Optional.ofNullable(records.get(taskId));
    }

    /**
     * 既存レコードに対して更新関数を適用し、結果を保存する（設計書「3.4」）。
     *
     * <p>{@code ConcurrentHashMap#compute} を用いることで、読み取りと書き込みの間に他スレッドの
     * 介入を許さない。</p>
     *
     * @throws NullPointerException {@code taskId} に対応するレコードが存在しない場合
     *                                （{@code compute} に渡した {@code updater} が {@code null} を
     *                                返した場合と同様にレコードを除去してしまうため、本メソッドの
     *                                利用は既存レコードが存在することが既知の場合に限定する）。
     */
    public AsyncTaskRecord update(UUID taskId, UnaryOperator<AsyncTaskRecord> updater) {
        return records.compute(taskId, (key, current) -> {
            if (current == null) {
                throw new IllegalStateException("no async task record found for taskId: " + key);
            }
            return updater.apply(current);
        });
    }

    /**
     * レコードを削除する（設計書「4.3」、ディスパッチ失敗時のロールバックに使用）。
     */
    public void remove(UUID taskId) {
        records.remove(taskId);
    }

    /**
     * 完了済み（{@code SUCCEEDED}/{@code FAILED}）のレコードのうち、{@code updatedAt} が
     * {@code threshold} より古い（{@code isBefore}）ものを削除する（設計書「7」）。
     *
     * <p>{@code PENDING}/{@code RUNNING} のレコードは実行中のタスクを誤って消さないよう、
     * {@code updatedAt} の値に関わらず削除対象としない。</p>
     */
    public void removeIfOlderThan(Instant threshold) {
        records.values().removeIf(record -> isCompleted(record.status())
                && record.updatedAt().isBefore(threshold));
    }

    private boolean isCompleted(AsyncTaskStatus status) {
        return status == AsyncTaskStatus.SUCCEEDED || status == AsyncTaskStatus.FAILED;
    }
}
