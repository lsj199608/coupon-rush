package com.couponrush.coupon;

import com.couponrush.IntegrationTest;
import com.couponrush.coupon.dto.CouponResponse;
import com.couponrush.coupon.issue.redis.RedisCouponStock;
import com.couponrush.coupon.search.CouponSearchCondition;
import com.couponrush.coupon.search.CouponSearchCondition.KeywordMatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 검색 조건 → DB 조회 (CouponSpecifications + CouponService.search). 실제 Postgres·Redis */
@IntegrationTest
class CouponSearchTest {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration DAY = Duration.ofDays(1);

    @Autowired CouponService couponService;
    @Autowired CouponRepository couponRepository;
    @Autowired RedisCouponStock redisCouponStock;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    /** 초 단위로 자른다 — 경계 테스트에서 PostgreSQL(µs)에 저장된 값이 원래 값보다 작아지지 않게 (Windows 시계는 100ns) */
    final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @AfterEach
    void tearDown() {
        ConcurrencyTestSupport.cleanUp(jdbcTemplate, redisTemplate);
    }

    private Coupon save(String name, String description, Instant start, Instant end) {
        return couponRepository.save(Coupon.create(name, description, 100, start, end));
    }

    private Coupon open(String name, String description) {
        return save(name, description, now.minus(HOUR), now.plus(DAY));
    }

    private List<String> names(CouponSearchCondition condition) {
        return couponService.search(condition).stream().map(CouponResponse::name).toList();
    }

    private static CouponSearchCondition keywords(KeywordMatch match, String... keywords) {
        return new CouponSearchCondition(List.of(keywords), match, Set.of(), null, null, null, null, false);
    }

    @Test
    void 조건이_없으면_전부_오픈_시각_최신순() {
        save("옛날", null, now.minus(DAY.multipliedBy(3)), now.minus(DAY));
        open("지금", null);
        save("다음", null, now.plus(DAY), now.plus(DAY.multipliedBy(2)));

        assertThat(names(CouponSearchCondition.anyKeyword(List.of()))).containsExactly("다음", "지금", "옛날");
    }

    @Test
    void 단어는_이름이나_설명에서_대소문자_없이_부분_일치로_찾는다() {
        open("아메리카노 COFFEE 쿠폰", null);
        open("라떼 할인", "따뜻한 coffee 한 잔");
        open("치킨 반값", "바삭한 치킨");

        assertThat(names(keywords(KeywordMatch.ALL, "coffee"))).containsExactlyInAnyOrder("아메리카노 COFFEE 쿠폰", "라떼 할인");
    }

    @Test
    void ALL_은_모든_단어_ANY_는_하나라도() {
        open("커피 할인", null);
        open("커피 증정", null);
        open("치킨 할인", null);

        assertThat(names(keywords(KeywordMatch.ALL, "커피", "할인"))).containsExactly("커피 할인");
        assertThat(names(keywords(KeywordMatch.ANY, "커피", "할인")))
                .containsExactlyInAnyOrder("커피 할인", "커피 증정", "치킨 할인");
    }

    @Test
    void 퍼센트와_밑줄은_글자_그대로_찾는다() {
        open("50% 할인", null);
        open("500원 할인", null);
        open("a_b 쿠폰", null);
        open("axb 쿠폰", null);

        assertThat(names(keywords(KeywordMatch.ALL, "50%"))).containsExactly("50% 할인");
        assertThat(names(keywords(KeywordMatch.ALL, "a_b"))).containsExactly("a_b 쿠폰");
    }

    @Test
    void 상태는_조회_시각_기준이고_여러_개면_그중_하나() {
        save("종료", null, now.minus(DAY.multipliedBy(2)), now.minus(DAY));
        open("진행", null);
        save("예정", null, now.plus(DAY), now.plus(DAY.multipliedBy(2)));

        assertThat(names(status(CouponStatus.OPEN))).containsExactly("진행");
        assertThat(names(status(CouponStatus.UPCOMING, CouponStatus.ENDED))).containsExactlyInAnyOrder("예정", "종료");
    }

    private static CouponSearchCondition status(CouponStatus... statuses) {
        return new CouponSearchCondition(List.of(), KeywordMatch.ALL, Set.of(statuses), null, null, null, null, false);
    }

    @Test
    void 기간은_from_포함_to_미포함() {
        Instant from = now.plus(DAY);
        Instant to = now.plus(DAY.multipliedBy(2));
        save("경계 시작", null, from, to.plus(DAY));
        save("경계 끝", null, to, to.plus(DAY));
        save("이전", null, from.minusSeconds(1), to.plus(DAY));

        assertThat(names(new CouponSearchCondition(List.of(), KeywordMatch.ALL, Set.of(), from, to, null, null, false)))
                .containsExactly("경계 시작");
        assertThat(names(new CouponSearchCondition(List.of(), KeywordMatch.ALL, Set.of(), null, null, to, to.plus(DAY), false)))
                .as("셋 다 종료가 to + 1일 — 종료 범위의 끝(to)은 포함하지 않는다").isEmpty();
    }

    @Test
    void 남은_것만은_Redis_재고_기준으로도_거른다() {
        Coupon redisSoldOut = open("Redis 소진", null);
        redisCouponStock.load(redisSoldOut, List.of());
        redisTemplate.opsForValue().set(RedisCouponStock.stockKey(redisSoldOut.getId()), "0");   // DB 카운터는 아직 0건 발급(늦음)
        Coupon dbSoldOut = open("DB 소진", null);
        jdbcTemplate.update("UPDATE coupons SET issued_quantity = total_quantity WHERE id = ?", dbSoldOut.getId());
        Coupon remaining = open("남음", null);
        redisCouponStock.load(remaining, List.of());

        assertThat(names(new CouponSearchCondition(List.of(), KeywordMatch.ALL, Set.of(), null, null, null, null, true)))
                .containsExactly("남음");
    }

    @Test
    void 조건은_모두_AND() {
        open("커피 진행", null);
        save("커피 예정", null, now.plus(DAY), now.plus(DAY.multipliedBy(2)));
        open("치킨 진행", null);

        assertThat(names(new CouponSearchCondition(List.of("커피"), KeywordMatch.ALL, Set.of(CouponStatus.OPEN),
                null, null, null, null, false))).containsExactly("커피 진행");
    }
}
