package com.facecook.feedback.repository;

import com.facecook.feedback.entity.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code feedback} 테이블 저장. 조회 화면은 없고 운영진이 DB에서 직접 본다. */
public interface FeedbackRepository extends JpaRepository<Feedback, Long> {
}
