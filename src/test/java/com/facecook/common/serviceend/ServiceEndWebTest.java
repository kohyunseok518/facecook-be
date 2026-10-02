package com.facecook.common.serviceend;

import com.facecook.auth.repository.UserRepository;
import com.facecook.auth.service.UserActivityService;
import com.facecook.common.exception.GlobalExceptionHandler;
import com.facecook.common.session.ActivityTrackingInterceptor;
import com.facecook.common.session.CurrentUserArgumentResolver;
import com.facecook.common.session.SessionAuthenticationInterceptor;
import com.facecook.common.session.SessionAuthenticator;
import com.facecook.common.session.SessionCookieService;
import com.facecook.common.session.SessionProperties;
import com.facecook.common.session.SessionTokenSigner;
import com.facecook.config.ServiceEndConfig;
import com.facecook.config.WebConfig;
import com.facecook.feedback.controller.FeedbackController;
import com.facecook.feedback.service.FeedbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 서비스 종료 시각 전후로 REST 요청이 어떻게 처리되는지 확인한다(facecook-be#155).
 * 종료 시각은 한국 시간 2026-10-03 00:00이고, 시계를 그 전후로 옮겨 가며 요청한다.
 *
 * <p>옛 화면이 종료 화면으로 넘어가려면 401이 CORS 헤더와 함께 와야 하고(헤더가 없으면 브라우저가 응답을 못 읽는다),
 * 로그인하지 않은 요청도 {@code UNAUTHORIZED}가 아니라 {@code SERVICE_ENDED}를 받아야 한다.</p>
 */
@WebMvcTest(FeedbackController.class)
@Import({
        GlobalExceptionHandler.class,
        WebConfig.class,
        ServiceEndConfig.class,
        SessionAuthenticationInterceptor.class,
        SessionAuthenticator.class,
        ActivityTrackingInterceptor.class,
        CurrentUserArgumentResolver.class,
        SessionTokenSigner.class,
        SessionCookieService.class,
        ServiceEndWebTest.TestConfig.class
})
@TestPropertySource(properties = {
        "app.service-end.end-at=2026-10-03T00:00:00+09:00",
        "app.cors.allowed-origins=https://facecook.example"
})
class ServiceEndWebTest {

    private static final String ORIGIN = "https://facecook.example";
    private static final Instant BEFORE_END = Instant.parse("2026-10-02T14:59:59Z");
    private static final Instant AT_END = Instant.parse("2026-10-02T15:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MovableClock clock;

    @MockitoBean
    private FeedbackService feedbackService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private UserActivityService userActivityService;

    @BeforeEach
    void resetClock() {
        clock.instant = BEFORE_END;
    }

    @Test
    void beforeEndLoginCheckStillAnswersUnauthorized() throws Exception {
        mockMvc.perform(get("/api/test/ping").header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void afterEndEveryApiAnswersServiceEndedWithCorsHeader() throws Exception {
        clock.instant = AT_END;

        mockMvc.perform(get("/api/test/ping").header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SERVICE_ENDED"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void afterEndPublicLoginApiIsBlockedToo() throws Exception {
        clock.instant = AT_END;

        mockMvc.perform(post("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@test.local\",\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SERVICE_ENDED"));
    }

    @Test
    void afterEndCorsPreflightPasses() throws Exception {
        clock.instant = AT_END;

        mockMvc.perform(options("/api/cooks")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN));
    }

    @Test
    void afterEndFeedbackIsAcceptedWithoutLogin() throws Exception {
        clock.instant = AT_END;

        mockMvc.perform(post("/api/feedback")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"  즐거웠어요  \"}"))
                .andExpect(status().isNoContent());
        verify(feedbackService).create(eq("  즐거웠어요  "), anyString());
    }

    @Test
    void feedbackLimitUsesLastForwardedForAddressAddedByAlb() throws Exception {
        // 사용자가 앞에 꾸며 넣은 주소(9.9.9.9)가 아니라 ALB가 맨 뒤에 덧붙인 실제 주소로 센다
        mockMvc.perform(post("/api/feedback")
                        .header("X-Forwarded-For", "9.9.9.9, 203.0.113.7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"좋았어요\"}"))
                .andExpect(status().isNoContent());
        verify(feedbackService).create("좋았어요", "203.0.113.7");
    }

    @Test
    void feedbackLimitUsesConnectionAddressWithoutForwardedFor() throws Exception {
        mockMvc.perform(post("/api/feedback")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.4");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"좋았어요\"}"))
                .andExpect(status().isNoContent());
        verify(feedbackService).create("좋았어요", "198.51.100.4");
    }

    @Test
    void blankFeedbackIsRejected() throws Exception {
        mockMvc.perform(post("/api/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        verify(feedbackService, never()).create(anyString(), anyString());
    }

    @Test
    void feedbackOver1000CharactersIsRejected() throws Exception {
        mockMvc.perform(post("/api/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + "가".repeat(1001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
        verify(feedbackService, never()).create(anyString(), anyString());
    }

    /** 종료 차단 대상이 되는 일반 API 자리. 로그인이 필요한 경로다. */
    @RestController
    static class PingController {
        @GetMapping("/api/test/ping")
        String ping() {
            return "pong";
        }
    }

    static class MovableClock extends Clock {
        Instant instant = BEFORE_END;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        MovableClock clock() {
            return new MovableClock();
        }

        @Bean
        PingController pingController() {
            return new PingController();
        }

        @Bean
        SessionProperties sessionProperties() {
            return new SessionProperties("test-secret", "FACECOOK_SESSION", 604800, false, "Lax", null);
        }
    }
}
