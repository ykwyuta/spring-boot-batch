package com.example.demo.task.async.config;

import com.example.demo.task.async.AsyncTaskProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 非同期タスク実行専用の {@link ThreadPoolTaskExecutor} を定義する設定クラス（設計書「4.2」）。
 *
 * <p>{@code @EnableAsync} と {@code @EnableScheduling}（保持期限スケジューラ用、設計書「7」）を
 * 同一クラスにまとめて宣言する。</p>
 */
@Configuration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(AsyncTaskProperties.class)
public class AsyncTaskExecutorConfig {

    private final AsyncTaskProperties properties;

    public AsyncTaskExecutorConfig(AsyncTaskProperties properties) {
        this.properties = properties;
    }

    @Bean(name = "asyncTaskExecutor")
    public Executor asyncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.executor().corePoolSize());
        executor.setMaxPoolSize(properties.executor().maxPoolSize());
        executor.setQueueCapacity(properties.executor().queueCapacity());
        executor.setThreadNamePrefix("async-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
