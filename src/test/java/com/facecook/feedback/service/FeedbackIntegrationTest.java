package com.facecook.feedback.service;

import com.facecook.feedback.repository.FeedbackRepository;
import com.facecook.support.MySqlIntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 후기가 V8 마이그레이션으로 만든 실제 MySQL 테이블에 저장되는지 확인한다(facecook-be#155).
 *
 * <p>부작용: 후기 한 건을 커밋하고 끝나면 삭제한다.</p>
 */
class FeedbackIntegrationTest extends MySqlIntegrationTestSupport {

    // 한국 시간 2026-10-03 00:30
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T15:30:00Z"), ZoneOffset.UTC);

    @Autowired private FeedbackRepository feedbackRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final String tag = UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from feedback where content like ?", "%" + tag + "%");
    }

    @Test
    void savesStrippedContentWithKoreanTime() {
        FeedbackService service = new FeedbackService(feedbackRepository, new FeedbackRateLimiter(CLOCK), CLOCK);

        service.create("  재밌었어요 " + tag + "\n");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select content, created_at from feedback where content like ?", "%" + tag + "%");
        assertThat(row.get("content")).isEqualTo("재밌었어요 " + tag);
        assertThat(row.get("created_at")).isEqualTo(LocalDateTime.of(2026, 10, 3, 0, 30));
    }
}
