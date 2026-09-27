package com.facecook.cook.service;

import com.facecook.common.exception.ApiException;
import com.facecook.common.exception.ErrorCode;
import com.facecook.cook.dto.SendCookRequest;
import com.facecook.profile.service.ProfileActivityLookup;
import com.facecook.push.service.ParticipantPushNotificationService;
import com.facecook.support.ConcurrentRunner;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 행사 전체 하루 콕 한도(9/30은 3,000개)가 서로 겹치지 않는 사용자 쌍의 동시 전송에서도 지켜지는지
 * 실제 MySQL로 확인한다.
 *
 * <p>확인 항목: 마지막 한 자리를 두 쌍이 다투면 한 쌍만 성공하고 다른 쌍은 첫 쌍의 커밋을 본 채로
 * {@code EVENT_LIMIT}을 받는지(두 번째 전송이 행사 한도 잠금에서 기다리는 것을 DB에서 확인한 뒤 첫 전송을
 * 커밋한다), 마지막 다섯 자리를 열 쌍이 동시에 다투면 정확히 다섯 쌍만 성공하는지, 그날 0시 이전·다음날
 * 0시의 콕은 그날 한도를 소비하지 않는지, 검사(중복 등)에서 실패할 전송은 행사 한도 잠금을 기다리지 않는지.
 * 두 번째 전송이 잠금 없이 통과하거나(잠금 누락) 잠금을 기다리기 전 시점의 데이터로 건수를 세면(격리 수준 오류)
 * 이 테스트가 실패한다.</p>
 *
 * <p>부작용: 필러 사용자 78명과 그날 콕 수천 개를 커밋한 뒤 끝나면 모두 삭제한다. 공유 DB이므로 시작할 때도
 * 그날 콕을 비운다. 푸시는 목으로 대체한다.</p>
 */
@Import({CookService.class, EventLimitLock.class})
class EventWideLimitIntegrationTest extends MySqlIntegrationTestSupport {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    // 잠금을 기다리지 않는 전송이 끝나기에 충분한 시간. 기다린다면 잠금이 풀리지 않으므로 이 시간을 넘긴다.
    private static final Duration LOCK_FREE_TIMEOUT = Duration.ofSeconds(10);
    private static final long EVENT_LIMIT = 3_000L;
    private static final int FILLER_USERS = 78;
    private static final LocalDateTime SENT_ON_EVENT_DAY = LocalDateTime.of(2026, 9, 30, 10, 0);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        Clock clock() {
            // 한국 시간 2026-09-30 12:00 — 행사 첫날, 한도 3,000개가 적용되는 날
            return Clock.fixed(Instant.parse("2026-09-30T03:00:00Z"), ZoneOffset.UTC);
        }
    }

    @MockitoBean
    private ParticipantPushNotificationService pushNotificationService;

    @MockitoBean
    private ProfileActivityLookup activityLookup;

    @Autowired
    private CookService cookService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> userIds = new ArrayList<>();
    private List<Long> fillerUserIds;

    @BeforeEach
    void createFillerUsersAndClearEventDay() {
        clearEventDayCooks();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < FILLER_USERS; i++) {
            rows.add(new Object[]{"event-limit-filler-" + UUID.randomUUID() + "@test.local"});
        }
        jdbcTemplate.batchUpdate("insert into users (email) values (?)", rows);
        fillerUserIds = jdbcTemplate.queryForList(
                "select user_id from users where email like 'event-limit-filler-%' order by user_id", Long.class);
        userIds.addAll(fillerUserIds);
    }

    @AfterEach
    void cleanUp() {
        for (Long userId : userIds) {
            jdbcTemplate.update("delete from cook where sender_id = ? or receiver_id = ?", userId, userId);
        }
        userIds.forEach(id -> jdbcTemplate.update("delete from users where user_id = ?", id));
        userIds.clear();
    }

    @Test
    void lastSlotRaceBetweenDisjointPairsLeavesOneWinnerWhoseCommitTheLoserSees() throws Exception {
        fillEventDay(EVENT_LIMIT - 1);
        long[] first = newPair();
        long[] second = newPair();

        Throwable secondError = firstCommitsBeforeSecond(
                () -> cookService.send(first[0], new SendCookRequest(first[1])),
                () -> cookService.send(second[0], new SendCookRequest(second[1])));

        assertThat(secondError).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.EVENT_LIMIT));
        assertThat(sentOnEventDay()).isEqualTo(EVENT_LIMIT);
        assertThat(cookExists(first)).isTrue();
        assertThat(cookExists(second)).isFalse();
    }

    @Test
    void tenConcurrentDisjointSendersForFiveRemainingSlotsLetExactlyFiveThrough() {
        fillEventDay(EVENT_LIMIT - 5);
        List<long[]> pairs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            pairs.add(newPair());
        }

        List<Callable<Void>> tasks = new ArrayList<>();
        for (long[] pair : pairs) {
            tasks.add(() -> {
                cookService.send(pair[0], new SendCookRequest(pair[1]));
                return null;
            });
        }
        List<ConcurrentRunner.Outcome<Void>> outcomes = ConcurrentRunner.runTogether(tasks, TIMEOUT);

        long succeeded = outcomes.stream().filter(ConcurrentRunner.Outcome::succeeded).count();
        assertThat(succeeded).isEqualTo(5);
        outcomes.stream().filter(outcome -> !outcome.succeeded()).forEach(outcome ->
                assertThat(outcome.error()).isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.EVENT_LIMIT)));
        assertThat(sentOnEventDay()).isEqualTo(EVENT_LIMIT);
    }

    @Test
    void cooksSentBeforeMidnightOrAtNextMidnightDoNotConsumeTheDaysSlots() {
        fillEventDay(EVENT_LIMIT - 1);
        insertOutsideTheDay(3);
        long[] first = newPair();
        long[] second = newPair();

        cookService.send(first[0], new SendCookRequest(first[1]));

        assertThatSendFailsWithEventLimit(second);
        assertThat(sentOnEventDay()).isEqualTo(EVENT_LIMIT);
    }

    @Test
    void sendThatFailsValidationDoesNotWaitForTheEventLimitLock() throws Exception {
        long[] pair = newPair();
        cookService.send(pair[0], new SendCookRequest(pair[1]));

        Throwable error = runWhileEventLimitLockIsHeld(() -> cookService.send(pair[0], new SendCookRequest(pair[1])));

        assertThat(error).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE));
    }

    /**
     * 다른 트랜잭션이 {@code event_limit_lock}을 쥔 채로 command를 다른 스레드에서 실행하고, 잠금이 풀리기 전에 끝난
     * 결과(예외, 성공이면 null)를 돌려준다. command가 행사 잠금을 기다리면 제한 시간 안에 끝나지 못해 실패한다.
     */
    private Throwable runWhileEventLimitLockIsHeld(Runnable command) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            return new TransactionTemplate(transactionManager).execute(status -> {
                jdbcTemplate.queryForList("select lock_id from event_limit_lock where lock_id = 1 for update");
                Future<Throwable> result = executor.submit(() -> {
                    try {
                        command.run();
                        return null;
                    } catch (Throwable throwable) {
                        return throwable;
                    }
                });
                try {
                    return result.get(LOCK_FREE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                } catch (TimeoutException exception) {
                    throw new AssertionError("검사에서 실패할 전송이 행사 한도 잠금을 기다렸다", exception);
                } catch (InterruptedException | ExecutionException exception) {
                    throw new IllegalStateException(exception);
                }
            });
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertThatSendFailsWithEventLimit(long[] pair) {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> cookService.send(pair[0], new SendCookRequest(pair[1])))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.EVENT_LIMIT));
    }

    /** 필러 사용자들 사이의 서로 다른 (보낸 사람, 받은 사람) 쌍으로 그날 콕을 count개까지 채운다. */
    private void fillEventDay(long count) {
        List<Object[]> rows = new ArrayList<>();
        outer:
        for (Long sender : fillerUserIds) {
            for (Long receiver : fillerUserIds) {
                if (sender.equals(receiver)) continue;
                if (rows.size() == count) break outer;
                rows.add(new Object[]{sender, receiver, SENT_ON_EVENT_DAY});
            }
        }
        assertThat(rows).hasSize((int) count);
        jdbcTemplate.batchUpdate(
                "insert into cook (sender_id, receiver_id, status, sent_at) values (?, ?, 'pending', ?)", rows);
    }

    /** 그날 한도 밖의 콕: 전날 23:59:59 세 개와 다음날 00:00:00 세 개(필러 쌍 중 그날 채우기에 쓰지 않은 쌍). */
    private void insertOutsideTheDay(int eachSide) {
        List<Object[]> rows = new ArrayList<>();
        int skip = (int) (EVENT_LIMIT - 1);
        int index = 0;
        for (Long sender : fillerUserIds) {
            for (Long receiver : fillerUserIds) {
                if (sender.equals(receiver)) continue;
                if (index++ < skip) continue;
                if (rows.size() < eachSide) {
                    rows.add(new Object[]{sender, receiver, LocalDateTime.of(2026, 9, 29, 23, 59, 59)});
                } else if (rows.size() < eachSide * 2) {
                    rows.add(new Object[]{sender, receiver, LocalDateTime.of(2026, 10, 1, 0, 0, 0)});
                }
            }
        }
        assertThat(rows).hasSize(eachSide * 2);
        jdbcTemplate.batchUpdate(
                "insert into cook (sender_id, receiver_id, status, sent_at) values (?, ?, 'pending', ?)", rows);
    }

    private long sentOnEventDay() {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from cook where sent_at >= '2026-09-30 00:00:00' and sent_at < '2026-10-01 00:00:00'",
                Long.class);
        return count == null ? 0 : count;
    }

    private boolean cookExists(long[] pair) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from cook where sender_id = ? and receiver_id = ?", Integer.class, pair[0], pair[1]);
        return count != null && count > 0;
    }

    private void clearEventDayCooks() {
        jdbcTemplate.update(
                "delete from cook where sent_at >= '2026-09-29 00:00:00' and sent_at < '2026-10-02 00:00:00'");
    }

    private long[] newPair() {
        return new long[]{insertUser(), insertUser()};
    }

    private long insertUser() {
        String email = "event-limit-" + UUID.randomUUID() + "@test.local";
        jdbcTemplate.update("insert into users (email) values (?)", email);
        Long id = jdbcTemplate.queryForObject("select user_id from users where email = ?", Long.class, email);
        userIds.add(id);
        return id;
    }
}
