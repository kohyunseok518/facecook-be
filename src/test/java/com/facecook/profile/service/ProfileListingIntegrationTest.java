package com.facecook.profile.service;

import com.facecook.config.ProfileActivityProperties;
import com.facecook.profile.dto.ProfileFiltersResponse;
import com.facecook.profile.dto.ProfileResponse;
import com.facecook.profile.dto.ProfileStatsResponse;
import com.facecook.support.MySqlIntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 탐색 목록·참가자 통계·탐색 필터가 활동 상태(active)인 참가자(participant) 프로필만 다루는지 실제 MySQL로
 * 확인한다(facecook-be#149). 관리자·슈퍼 계정에 프로필이 있거나 참가자가 정지되면 목록에 보이면 안 된다.
 *
 * <p>공유 컨테이너라 다른 테스트의 행이 있을 수 있어서, 통계는 넣기 전과 뒤의 차이로, 필터는 이 테스트만 쓰는
 * 학과 이름으로 확인한다.</p>
 *
 * <p>부작용: 테스트마다 계정 네 개(활동 참가자·정지 참가자·관리자·슈퍼)와 각 프로필을 커밋하고 끝나면 삭제한다.</p>
 */
@Import({ProfileService.class, ParticipantListCache.class, ProfileActivityLookup.class})
class ProfileListingIntegrationTest extends MySqlIntegrationTestSupport {

    @TestConfiguration
    static class Config {
        @Bean
        Clock clock() {
            // 한국 시간 2026-09-30 12:00
            return Clock.fixed(Instant.parse("2026-09-30T03:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        ProfileActivityProperties profileActivityProperties() {
            return new ProfileActivityProperties(15);
        }
    }

    @MockitoBean
    private ProfilePhotoUrlPolicy photoUrlPolicy;

    @Autowired
    private ProfileService profileService;

    @Autowired
    private ParticipantListCache participantListCache;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> userIds = new ArrayList<>();
    private final String tag = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void evictCache() {
        participantListCache.evictAfterCommit();
    }

    @AfterEach
    void cleanUp() {
        userIds.forEach(id -> {
            jdbcTemplate.update("delete from profile where user_id = ?", id);
            jdbcTemplate.update("delete from users where user_id = ?", id);
        });
        userIds.clear();
    }

    @Test
    void exploreListShowsOnlyActiveParticipants() {
        long participant = newUserWithProfile("participant", "active", "참가자");
        long suspended = newUserWithProfile("participant", "suspended", "정지");
        long admin = newUserWithProfile("admin", "active", "관리자");
        long superAccount = newUserWithProfile("super", "active", "슈퍼");

        List<Long> listed = participantListCache.getAllExcept(-1L).stream().map(ProfileResponse::userId).toList();

        assertThat(listed).contains(participant).doesNotContain(suspended, admin, superAccount);
    }

    @Test
    void statsCountOnlyActiveParticipants() {
        ProfileStatsResponse before = profileService.getStats();

        newUserWithProfile("participant", "active", "참가자");
        newUserWithProfile("participant", "suspended", "정지");
        newUserWithProfile("admin", "active", "관리자");
        newUserWithProfile("super", "active", "슈퍼");
        ProfileStatsResponse after = profileService.getStats();

        assertThat(after.total() - before.total()).isEqualTo(1);
        assertThat(after.activeNow() - before.activeNow()).isEqualTo(1);
    }

    @Test
    void filtersComeOnlyFromActiveParticipants() {
        newUserWithProfile("participant", "active", "참가자");
        newUserWithProfile("participant", "suspended", "정지");
        newUserWithProfile("admin", "active", "관리자");
        newUserWithProfile("super", "active", "슈퍼");

        for (boolean activeOnly : new boolean[]{false, true}) {
            ProfileFiltersResponse filters = profileService.getFilters(activeOnly);

            assertThat(filters.departments())
                    .contains(department("참가자"))
                    .doesNotContain(department("정지"), department("관리자"), department("슈퍼"));
        }
    }

    /** 계정과 프로필을 만들고 커밋한다. 마지막 활동은 고정 시각(한국 12:00)의 5분 전이라 "활동 중"이다. */
    private long newUserWithProfile(String role, String status, String label) {
        String email = "listing-" + tag + "-" + label.hashCode() + "@test.local";
        jdbcTemplate.update(
                "insert into users (email, role, status, last_active_at) values (?, ?, ?, '2026-09-30 11:55:00')",
                email, role, status);
        Long userId = jdbcTemplate.queryForObject("select user_id from users where email = ?", Long.class, email);
        jdbcTemplate.update(
                "insert into profile (user_id, nickname, gender, age, mbti, hobby, blood_type, department)"
                        + " values (?, ?, 'M', 22, 'INTJ', '독서', 'A', ?)",
                userId, label, department(label));
        userIds.add(userId);
        return userId;
    }

    private String department(String label) {
        return "목록검증-" + tag + "-" + label;
    }
}
