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
 * 익명 후기 저장(facecook-be#155). 로그인 없이 받으므로 서버마다 1분에 받는 개수를 제한한다.
 *
 * <p>사람별 제한은 하지 않는다 — 익명이라 사람을 가릴 값이 없고, 행사장 와이파이처럼 여러 사람이 같은 IP로
 * 나가는 경우가 많아 IP로 막으면 다른 참가자까지 막힌다. 한 사람의 반복 제출은 화면이 제출 뒤 입력창을 닫아 줄인다.</p>
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
     * <p>예외: {@code FEEDBACK_BUSY}(이 서버가 1분에 받는 개수를 넘음).</p>
     */
    public void create(String content) {
        if (!rateLimiter.tryAcquire()) {
            throw new ApiException(ErrorCode.FEEDBACK_BUSY);
        }
        feedbackRepository.save(Feedback.create(content.strip(), EventTime.now(clock)));
    }
}
