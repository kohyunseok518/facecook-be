package com.facecook.feedback.controller;

import com.facecook.feedback.dto.CreateFeedbackRequest;
import com.facecook.feedback.service.FeedbackService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 종료 화면의 익명 후기 제출 API. {@code POST /api/feedback} → {@link FeedbackService#create}.
 *
 * <p>로그인 없이 부른다 — {@code WebConfig}가 로그인 확인에서, {@code ServiceEndConfig}가 종료 차단에서 이 경로를 뺀다.
 * 성공하면 본문 없이 204.</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping
    public ResponseEntity<Void> create(
            @Valid @RequestBody CreateFeedbackRequest request,
            HttpServletRequest httpRequest
    ) {
        feedbackService.create(request.content(), clientAddress(httpRequest));
        return ResponseEntity.noContent().build();
    }

    /**
     * 제출 수 제한에 쓰는 접속 주소. ALB 뒤라 {@code getRemoteAddr()}는 ALB 주소다. ALB는 받은 요청의 실제 접속 주소를
     * {@code X-Forwarded-For} 맨 뒤에 덧붙이므로 마지막 값을 쓴다 — 앞쪽 값은 사용자가 헤더를 직접 넣어 꾸밀 수 있다.
     * 헤더가 없으면(로컬) 연결 주소를 쓴다.
     */
    static String clientAddress(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null) {
            String last = forwardedFor.substring(forwardedFor.lastIndexOf(',') + 1).strip();
            if (!last.isEmpty()) {
                return last;
            }
        }
        return request.getRemoteAddr();
    }
}
