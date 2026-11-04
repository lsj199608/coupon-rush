package com.couponrush.coupon;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    /**
     * [조건부 UPDATE] 재고 확인과 차감을 한 문장으로 원자적으로 처리한다.
     * - SET 이 "읽어둔 값"이 아니라 DB 의 현재 값 기준(issued_quantity + 1)이라 lost update 가 없다
     * - PostgreSQL(READ COMMITTED) 은 같은 행을 먼저 수정한 트랜잭션이 커밋될 때까지 기다린 뒤,
     *   최신 행 기준으로 WHERE 를 다시 평가한다 → 재고가 0 이 되는 순간 이후 요청은 0 행 갱신
     * - version 도 올린다: 벌크 UPDATE 는 @Version 을 자동으로 올리지 않으므로, 낙관적 락 방식과 섞여도 충돌을 감지하게 한다
     *
     * @return 갱신된 행 수. 1 이면 차감 성공, 0 이면 실패(없는 쿠폰/기간 아님/소진)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Coupon c
            SET c.issuedQuantity = c.issuedQuantity + 1, c.version = c.version + 1, c.updatedAt = :now
            WHERE c.id = :couponId
              AND c.issuedQuantity < c.totalQuantity
              AND c.issueStartAt <= :now
              AND c.issueEndAt > :now
            """)
    int increaseIssuedQuantity(@Param("couponId") Long couponId, @Param("now") Instant now);

    /**
     * [비관적 락] SELECT ... FOR UPDATE.
     * 이 행을 읽는 순간부터 커밋/롤백까지 다른 트랜잭션의 FOR UPDATE 는 대기한다 → 읽은 값이 커밋 때까지 최신임이 보장된다.
     * (PostgreSQL 은 FOR UPDATE 에 ms 단위 대기 시간을 지정할 수 없어 lock timeout 힌트는 쓰지 않는다)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Coupon c WHERE c.id = :couponId")
    Optional<Coupon> findByIdForUpdate(@Param("couponId") Long couponId);

    /** 대조 점검용: 기간 안에 종료된 이벤트 */
    List<Coupon> findByIssueEndAtBetween(Instant from, Instant to);
}
