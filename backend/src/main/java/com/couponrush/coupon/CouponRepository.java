package com.couponrush.coupon;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    /**
     * 재고 확인과 차감을 한 문장으로 원자적으로 처리한다 (조건부 UPDATE).
     * - SET 이 "읽어둔 값"이 아니라 DB 의 현재 값 기준(issued_quantity + 1)이라 lost update 가 없다
     * - PostgreSQL(READ COMMITTED) 은 같은 행을 먼저 수정한 트랜잭션이 커밋될 때까지 기다린 뒤,
     *   최신 행 기준으로 WHERE 를 다시 평가한다 → 재고가 0 이 되는 순간 이후 요청은 0 행 갱신
     *
     * @return 갱신된 행 수. 1 이면 차감 성공, 0 이면 실패(없는 쿠폰/기간 아님/소진)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Coupon c
            SET c.issuedQuantity = c.issuedQuantity + 1, c.updatedAt = :now
            WHERE c.id = :couponId
              AND c.issuedQuantity < c.totalQuantity
              AND c.issueStartAt <= :now
              AND c.issueEndAt > :now
            """)
    int increaseIssuedQuantity(@Param("couponId") Long couponId, @Param("now") Instant now);
}
