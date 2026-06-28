package com.example.demo.task.async;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code async-task} プレフィックスの設定値をバインドする設定クラス（設計書「10」）。
 *
 * <p>いずれの値も既定値をコード上に持たせることで、{@code application.properties} への
 * 追記漏れがあっても起動時エラーにならないようにする。</p>
 *
 * @param executor        非同期タスク実行用スレッドプールの設定。
 * @param retentionMinutes 完了済みタスクの状態保持期間（分）。既定値30。
 */
@ConfigurationProperties(prefix = "async-task")
public record AsyncTaskProperties(Executor executor, Integer retentionMinutes) {

    public AsyncTaskProperties(Executor executor, Integer retentionMinutes) {
        this.executor = executor != null ? executor : new Executor(null, null, null);
        this.retentionMinutes = retentionMinutes != null ? retentionMinutes : 30;
    }

    /**
     * {@code async-task.executor} プレフィックスの設定値。
     *
     * @param corePoolSize  {@code ThreadPoolTaskExecutor} のコアプールサイズ。既定値2。
     * @param maxPoolSize   {@code ThreadPoolTaskExecutor} の最大プールサイズ。既定値4。
     * @param queueCapacity {@code ThreadPoolTaskExecutor} のキュー長。既定値50。
     */
    public record Executor(Integer corePoolSize, Integer maxPoolSize, Integer queueCapacity) {

        public Executor(Integer corePoolSize, Integer maxPoolSize, Integer queueCapacity) {
            this.corePoolSize = corePoolSize != null ? corePoolSize : 2;
            this.maxPoolSize = maxPoolSize != null ? maxPoolSize : 4;
            this.queueCapacity = queueCapacity != null ? queueCapacity : 50;
        }
    }
}
