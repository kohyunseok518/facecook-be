package com.facecook.feedback.controller;

import com.facecook.feedback.dto.CreateFeedbackRequest;
import com.facecook.feedback.service.FeedbackService;
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
    public ResponseEntity<Void> create(@Valid @RequestBody CreateFeedbackRequest request) {
        feedbackService.create(request.content());
        return ResponseEntity.noContent().build();
    }
}
