package com.facecook.common.serviceend;

import com.facecook.common.exception.ErrorCode;
import com.facecook.common.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 서비스 종료 시각 이후 REST 요청을 401 {@code SERVICE_ENDED}로 막는다(facecook-be#155).
 * {@code ServiceEndConfig}가 로그인 확인보다 먼저 실행되게 등록하고, 후기 제출만 뺀다.
 *
 * <p>401로 돌려주는 이유: 이미 열려 있는 옛 화면 코드는 401을 받으면 로그인 화면으로 페이지를 새로 연다.
 * 그 이동이 새로 배포한 FE를 거치면서 종료 화면으로 바뀐다. 다른 상태(410·503)는 옛 화면이 이동하지 않는다.</p>
 *
 * <p>예외를 던지지 않고 응답을 직접 쓴다 — 종료 뒤에는 열린 화면마다 10초 간격으로 요청이 오는데,
 * {@code GlobalExceptionHandler}를 거치면 요청마다 WARN 로그가 남는다. CORS 헤더는 Spring이 인터셉터보다
 * 먼저 붙이므로 다른 도메인의 화면도 이 응답을 읽을 수 있다. CORS 사전 요청(OPTIONS)은 통과시킨다.</p>
 */
@RequiredArgsConstructor
public class ServiceEndInterceptor implements HandlerInterceptor {

    private final ServiceEndPolicy policy;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull Object handler
    ) throws IOException {
        if (HttpMethod.OPTIONS.matches(request.getMethod()) || !policy.isEnded()) {
            return true;
        }
        response.setStatus(ErrorCode.SERVICE_ENDED.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.from(ErrorCode.SERVICE_ENDED));
        return false;
    }
}
