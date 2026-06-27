package com.example.demo.task.interceptor;

import tools.jackson.databind.ObjectMapper;
import com.example.demo.task.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;

/**
 * {@code /internal/**} 配下へのリクエストについて、リクエスト元IPアドレスが
 * {@code 127.0.0.1} または {@code ::1}（IPv6ループバック）であるかを判定する。
 *
 * <p>条件を満たさない場合は {@code 403 Forbidden} を直接書き込み、Controllerの呼び出しを
 * ブロックする（設計書「5.1」）。</p>
 */
public class LocalhostOnlyInterceptor implements HandlerInterceptor {

    private static final Set<String> ALLOWED_ADDRESSES = Set.of("127.0.0.1", "::1");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String remoteAddr = request.getRemoteAddr();
        if (ALLOWED_ADDRESSES.contains(remoteAddr)) {
            return true;
        }

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse errorResponse = new ErrorResponse(
                "ERROR", "FORBIDDEN", "access from this address is not allowed", Instant.now());
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
        return false;
    }
}
