package com.example.demo.task.interceptor;

import com.example.demo.task.exception.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LocalhostOnlyInterceptor} の単体テスト（テスト計画 IT-01〜IT-03、設計書「4.2」分岐 #0a/#0b）。
 */
class LocalhostOnlyInterceptorTest {

    private final LocalhostOnlyInterceptor interceptor = new LocalhostOnlyInterceptor();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * IT-01: remoteAddr が 127.0.0.1 の場合、preHandle は true を返し、レスポンスへの書き込みは行われない。
     */
    @Test
    void preHandle_allowsLoopbackIpv4() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/tasks/execute");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        // レスポンスステータスが初期値（200）のまま、書き込みも行われていないこと
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
    }

    /**
     * IT-02: remoteAddr が ::1（IPv6ループバック）の場合も処理続行（true を返す）。
     */
    @Test
    void preHandle_allowsLoopbackIpv6() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/tasks/execute");
        request.setRemoteAddr("::1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
    }

    /**
     * IT-03: remoteAddr がローカルホスト以外の場合、403を書き込み false を返す。
     * レスポンスボディは ObjectMapper でデシリアライズし、共通エラーフォーマットの各フィールドを検証する。
     */
    @Test
    void preHandle_rejectsNonLocalhost() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/tasks/execute");
        request.setRemoteAddr("192.168.1.10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);

        ErrorResponse body = objectMapper.readValue(response.getContentAsString(), ErrorResponse.class);
        assertThat(body.status()).isEqualTo("ERROR");
        assertThat(body.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(body.message()).isNotBlank();
        assertThat(body.timestamp()).isNotNull();
    }
}
