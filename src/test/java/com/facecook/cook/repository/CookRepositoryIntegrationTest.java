package com.facecook.cook.repository;

import com.facecook.cook.entity.Cook;
import com.facecook.support.MySqlIntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CookRepository#findAllBetween}이 실제 MySQL에서 두 사람 사이의 콕만 방향 구분 없이 돌려주는지 확인한다.
 *
 * <p>확인 항목: 두 방향 콕을 모두 돌려주는지, 인자 순서를 바꿔도 같은 결과인지, 두 사람 중 한 명만 겹치는 다른
 * 쌍의 콕은 섞이지 않는지(JPQL의 {@code and}/{@code or} 묶음이 틀리면 여기서 걸린다), 콕이 없으면 빈 목록인지.</p>
 *
 * <p>부작용: 사용자 세 명과 콕을 커밋하고 끝나면 삭제한다.</p>
 */
class CookRepositoryIntegrationTest extends MySqlIntegrationTestSupport {

    @Autowired
    private CookRepository cookRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long userId : userIds) {
            jdbcTemplate.update("delete from cook where sender_id = ? or receiver_id = ?", userId, userId);
        }
        userIds.forEach(id -> jdbcTemplate.update("delete from users where user_id = ?", id));
        userIds.clear();
    }

    @Test
    void findAllBetweenReturnsBothDirectionsOnlyForThatPair() {
        long a = insertUser();
        long b = insertUser();
        long c = insertUser();
        insertCook(a, b);
        insertCook(b, a);
        insertCook(a, c);
        insertCook(c, b);

        assertThat(pairsOf(cookRepository.findAllBetween(a, b)))
                .containsExactlyInAnyOrder(a + "->" + b, b + "->" + a);
        assertThat(pairsOf(cookRepository.findAllBetween(b, a)))
                .containsExactlyInAnyOrder(a + "->" + b, b + "->" + a);
        assertThat(pairsOf(cookRepository.findAllBetween(a, c))).containsExactly(a + "->" + c);
    }

    @Test
    void findAllBetweenIsEmptyWhenThePairHasNoCook() {
        long a = insertUser();
        long b = insertUser();
        long c = insertUser();
        insertCook(a, c);

        assertThat(cookRepository.findAllBetween(a, b)).isEmpty();
    }

    private static List<String> pairsOf(List<Cook> cooks) {
        return cooks.stream().map(cook -> cook.getSenderId() + "->" + cook.getReceiverId()).toList();
    }

    private void insertCook(long senderId, long receiverId) {
        jdbcTemplate.update(
                "insert into cook (sender_id, receiver_id, status, sent_at) values (?, ?, 'pending', now())",
                senderId, receiverId);
    }

    private long insertUser() {
        String email = "cook-repository-" + UUID.randomUUID() + "@test.local";
        jdbcTemplate.update("insert into users (email) values (?)", email);
        Long id = jdbcTemplate.queryForObject("select user_id from users where email = ?", Long.class, email);
        userIds.add(id);
        return id;
    }
}
