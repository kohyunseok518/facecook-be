package com.facecook.config;

import com.facecook.common.serviceend.ServiceEndInterceptor;
import com.facecook.common.serviceend.ServiceEndPolicy;
import com.facecook.common.serviceend.ServiceEndProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;

/**
 * 서비스 종료 차단 설정(facecook-be#155). 종료 시각 판단({@link ServiceEndPolicy})을 빈으로 만들고,
 * REST 차단 인터셉터를 다른 인터셉터보다 먼저 실행되게 등록한다.
 *
 * <p>{@code WebConfig}와 따로 둔 이유: 컨트롤러 슬라이스 테스트 여러 개가 {@code WebConfig}를 직접 불러 쓰는데,
 * 거기에 의존성을 더하면 그 테스트를 모두 고쳐야 한다.</p>
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(ServiceEndProperties.class)
public class ServiceEndConfig implements WebMvcConfigurer {

    /** 종료 뒤에도 받는 API. 후기 제출만 남긴다. 헬스체크(/actuator)는 /api 밖이라 원래 대상이 아니다. */
    static final String[] OPEN_AFTER_END_PATHS = {"/api/feedback"};

    private final ServiceEndProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    @Bean
    public ServiceEndPolicy serviceEndPolicy() {
        return new ServiceEndPolicy(properties, clock);
    }

    /**
     * 로그인 확인({@code SessionAuthenticationInterceptor})보다 먼저 돌아야 로그인하지 않은 요청도
     * {@code UNAUTHORIZED}가 아니라 {@code SERVICE_ENDED}를 받는다. 인터셉터 목록은 order 값으로 정렬된다.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ServiceEndInterceptor(serviceEndPolicy(), objectMapper))
                .addPathPatterns("/api/**")
                .excludePathPatterns(OPEN_AFTER_END_PATHS)
                .order(Ordered.HIGHEST_PRECEDENCE);
    }
}
