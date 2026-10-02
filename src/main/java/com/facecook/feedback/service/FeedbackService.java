package com.facecook.feedback.service;

import com.facecook.common.exception.ApiException;
import com.facecook.common.exception.ErrorCode;
import com.facecook.common.time.EventTime;
import com.facecook.feedback.entity.Feedback;
import com.facecook.feedback.repository.FeedbackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * 익명 후기 저장(facecook-be#155). 로그인 없이 받으므로 1분에 받는 개수를 접속 주소별·서버 전체로 제한한다
 * ({@link FeedbackRateLimiter}). 접속 주소는 제한에만 쓰고 저장하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final FeedbackRepository feedbackRepository;
    private final FeedbackRateLimiter rateLimiter;
    private final Clock clock;

    /**
     * 앞뒤 공백을 지운 글을 저장한다.
     *
     * <p>예외: {@code FEEDBACK_BUSY}(이 접속 주소나 이 서버가 1분에 받는 개수를 넘음).</p>
     */
    public void create(String content, String clientAddress) {
        if (!rateLimiter.tryAcquire(clientAddress)) {
            throw new ApiException(ErrorCode.FEEDBACK_BUSY);
        }
        feedbackRepository.save(Feedback.create(content.strip(), EventTime.now(clock)));
    }
}
