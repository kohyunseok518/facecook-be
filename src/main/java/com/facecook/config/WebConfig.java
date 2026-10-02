package com.facecook.config;

import com.facecook.common.session.ActivityTrackingInterceptor;
import com.facecook.common.session.CurrentUserArgumentResolver;
import com.facecook.common.session.SessionAuthenticationInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Spring MVC(REST API) 공통 설정: CORS, 로그인 확인 인터셉터, {@code @CurrentUser} 주입.
 *
 * <p>요청 하나가 컨트롤러에 도착하기까지:</p>
 * <ol>
 * <li>{@code SlowRequestLoggingFilter}(서블릿 필터) — 전체 처리 시간 측정 시작</li>
 * <li>{@code SessionAuthenticationInterceptor} — 세션 쿠키로 로그인 확인, 사용자 정보를 요청에 넣음</li>
 * <li>{@code ActivityTrackingInterceptor} — 마지막 활동 시각 갱신</li>
 * <li>컨트롤러 — {@code @CurrentUser} 파라미터는 {@code CurrentUserArgumentResolver}가 채움</li>
 * </ol>
 *
 * <p>{@link #PUBLIC_AUTH_PATHS}는 로그인하기 전에 불러야 하는 API라 두 인터셉터에서 뺀다.
 * {@code /api/auth/me}는 로그인 확인용이라 빠지지 않는다.</p>
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(CorsProperties.class)
public class WebConfig implements WebMvcConfigurer {

    private static final String[] PUBLIC_AUTH_PATHS = {
            "/api/auth/request-code",
            "/api/auth/verify-signup",
            "/api/auth/verify-login",
            "/api/auth/login",
            "/api/auth/logout",
            // 서비스 종료 화면의 익명 후기 제출(#155)
            "/api/feedback"
    };

    private final CorsProperties corsProperties;
    private final SessionAuthenticationInterceptor sessionAuthenticationInterceptor;
    private final ActivityTrackingInterceptor activityTrackingInterceptor;
    private final CurrentUserArgumentResolver currentUserArgumentResolver;

    /**
     * {@code allowCredentials(true)}: 브라우저가 다른 도메인 API 호출에 세션 쿠키를
     * 싣도록 허용한다. 이때는 허용 주소를 {@code "*"}로 둘 수 없어서 목록을 명시한다.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(corsProperties.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(sessionAuthenticationInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(PUBLIC_AUTH_PATHS);
        // sessionAuthenticationInterceptor 다음 순서로 등록해야 한다 — 이
        // 인터셉터가 CURRENT_USER_ATTRIBUTE를 먼저 채워둬야 활동기록이 누구
        // 것인지 알 수 있다.
        registry.addInterceptor(activityTrackingInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(PUBLIC_AUTH_PATHS);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }
}
