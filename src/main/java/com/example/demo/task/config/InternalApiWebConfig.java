package com.example.demo.task.config;

import com.example.demo.task.interceptor.LocalhostOnlyInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * {@link LocalhostOnlyInterceptor} を {@code /internal/**} パスのみに登録する設定クラス。
 *
 * <p>既存・将来の業務用画面・API（{@code /internal/**} 以外）には影響を与えない
 * （設計書「5.1」）。</p>
 */
@Configuration
public class InternalApiWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LocalhostOnlyInterceptor())
                .addPathPatterns("/internal/**");
    }
}
