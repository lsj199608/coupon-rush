# 선착순 쿠폰 동시성 리포트

선착순 쿠폰 발급에서 **"대량 동시 요청에서도 정확히 재고만큼만 발급"**을 보장하기 위한 단계별 기록.
각 단계는 같은 테스트(`CouponIssueConcurrencyTest`)로 검증한다.

## 테스트 시나리오

| 항목 | 값 |
|---|---|
| 재고 | 100개 |
| 요청 | 서로 다른 사용자 1,000명 |
| 동시성 | 32 스레드, `CountDownLatch`로 동시에 출발 |
| 호출 대상 | `CouponIssueService.issue()` 직접 호출 (HTTP·인증 오버헤드 제외) |
| 환경 | Testcontainers PostgreSQL 17 (pgvector 이미지), HikariCP 기본 풀 10, 격리수준 READ COMMITTED |
| 기대 결과 | 성공 100 / `COUPON_SOLD_OUT` 900 / `coupon_issues` 100행 / `issued_quantity` 100 |

추가 시나리오: **같은 사용자가 동시에 10번 요청** → 성공 1 / `COUPON_ALREADY_ISSUED` 9

---

## 3단계-1. 순진한 구현 (재현)

```java
@Transactional
public CouponIssue issue(Long couponId, Long userId) {
    Coupon coupon = couponRepository.findById(couponId).orElseThrow(...);
    if (!coupon.isInIssuePeriod(now)) throw ...;
    if (coupon.isSoldOut()) throw COUPON_SOLD_OUT;              // ① 확인
    if (couponIssueRepository.existsByCouponIdAndUserId(...)) throw COUPON_ALREADY_ISSUED;

    coupon.increaseIssuedQuantity();                             // ② 메모리에서 +1 (dirty checking)
    return couponIssueRepository.save(new CouponIssue(...));
}
```

단일 요청 테스트는 모두 통과한다. 하지만 동시 요청에서는:

### 결과

| 지표 | 기대 | 실제 |
|---|---:|---:|
| 성공 응답 | 100 | **907** |
| `COUPON_SOLD_OUT` | 900 | 93 |
| `coupon_issues` 행 (실제 발급) | 100 | **907** (9배 초과) |
| `coupons.issued_quantity` | 100 | 100 |

같은 사용자 동시 10회: 성공 1 / `COUPON_ALREADY_ISSUED` 1 / **처리되지 않은 `DataIntegrityViolationException` 8** (API 에서는 500)

### 원인 — Lost Update

```
시간 →   T1                         T2                         DB issued_quantity
        SELECT → issued=5                                       5
                                    SELECT → issued=5           5
        5 < 100 통과                 5 < 100 통과
        UPDATE SET issued=6 (락)
                                    UPDATE SET issued=6 (대기)
        INSERT issue, COMMIT                                    6
                                    (락 획득) issued=6 덮어씀
                                    INSERT issue, COMMIT        6   ← 발급은 2건, 카운터는 +1
```

- Hibernate dirty checking 은 `SET issued_quantity = 6` 처럼 **읽어 둔 값 기반의 결과값**을 쓴다.
  두 번째 트랜잭션은 락을 기다렸다가 낡은 값으로 덮어쓴다.
- 카운터가 실제 발급보다 느리게 올라가니 "아직 남았다"는 판정이 계속 통과 → 발급 행만 쌓인다.
- `issued_quantity` 가 정확히 100에서 멈춘 것은 CHECK 제약(`issued_quantity <= total_quantity`) 덕분이지만,
  **이 제약은 초과 발급을 막지 못한다** (카운터와 실제 발급 행이 어긋나는 것이 문제의 본질).
- `@Transactional` 은 원자성(전부 성공/전부 롤백)을 줄 뿐, **"확인 후 변경(check-then-act)" 사이에 다른 트랜잭션이 끼어드는 것은 막지 않는다.**

중복 요청: 10개 요청이 모두 `exists` 검사를 동시에 통과 → 유니크 제약이 최종적으로 막아 데이터는 1건이지만,
예외를 처리하지 않아 사용자에게는 500 이 나간다.

---

## 3단계-2. 조건부 UPDATE (수정)

```java
@Modifying
@Query("""
    UPDATE Coupon c SET c.issuedQuantity = c.issuedQuantity + 1, c.updatedAt = :now
    WHERE c.id = :couponId AND c.issuedQuantity < c.totalQuantity
      AND c.issueStartAt <= :now AND c.issueEndAt > :now
    """)
int increaseIssuedQuantity(Long couponId, Instant now);
```

```java
@Transactional
public CouponIssue issue(Long couponId, Long userId) {
    if (exists(couponId, userId)) throw COUPON_ALREADY_ISSUED;      // 빠른 실패 (최종 판정 아님)
    if (couponRepository.increaseIssuedQuantity(couponId, now) == 0)
        throw failureReason(...);                                   // 없음 / 기간 아님 / 소진 구분
    try {
        return couponIssueRepository.saveAndFlush(new CouponIssue(...));
    } catch (DataIntegrityViolationException e) {
        if (유니크 제약 uk_coupon_issues_coupon_user 위반) throw COUPON_ALREADY_ISSUED;  // 롤백 → 차감 취소
        throw e;
    }
}
```

### 왜 정확한가

1. **확인과 변경이 한 문장**: `issued_quantity + 1` 은 DB 의 현재 값 기준으로 계산된다.
2. **PostgreSQL READ COMMITTED 의 UPDATE 재평가**: 같은 행을 먼저 수정 중인 트랜잭션이 있으면 커밋될 때까지 기다린 뒤,
   **최신 행 버전으로 WHERE 를 다시 평가**한다. 재고가 100이 된 뒤의 UPDATE 는 0행 갱신 → `COUPON_SOLD_OUT`.
3. **중복 발급은 유니크 제약이 최종 판정**: `exists` 검사를 동시에 통과해도 INSERT 에서 하나만 성공한다.
   실패한 쪽은 예외 → 트랜잭션 롤백으로 **같은 트랜잭션에서 차감한 재고도 함께 원복**된다.

### 결과 (3회 반복, 매번 동일)

| 지표 | 기대 | 실제 |
|---|---:|---:|
| 성공 응답 | 100 | **100** |
| `COUPON_SOLD_OUT` | 900 | **900** |
| `coupon_issues` 행 | 100 | **100** |
| `coupons.issued_quantity` | 100 | **100** |
| 소요 시간 | – | 약 4.8 ~ 5.7초 |

같은 사용자 동시 10회: 성공 1 / `COUPON_ALREADY_ISSUED` 9 / 발급 1행

> 소요 시간은 테스트 환경(컨테이너, 커넥션 풀 10, 사용자 생성 제외)에서의 참고값이며 벤치마크가 아니다.
> 4단계 비교 시 같은 조건으로 측정한다.

---

## 3단계-3. 해결책처럼 보이지만 아닌 것들

| 시도 | 서버 1대 | 서버 여러 대 | 왜 |
|---|:---:|:---:|---|
| 엔티티에 `AtomicBoolean soldOut` / `AtomicInteger` 필드 | ❌ | ❌ | JPA 는 트랜잭션마다 DB 에서 **새 Coupon 객체**를 만든다. 객체가 요청마다 달라 공유되는 것이 없다. 경쟁은 자바 객체가 아니라 **DB 행**에서 일어난다 |
| `@Transactional` + `synchronized` | ❌ | ❌ | 커밋은 프록시가 메서드 **바깥**에서 한다. 락이 먼저 풀려 다음 스레드가 커밋 전 값을 읽는다 |
| 싱글톤 빈의 `AtomicInteger` 카운터 | ✅ | ❌ | 서버마다 카운터가 따로 있어 N대면 N배 발급. 재시작 시 초기화, DB 트랜잭션 실패 시 직접 되돌려야 함 |
| **DB 조건부 UPDATE** (채택) | ✅ | ✅ | 확인+차감이 DB 한 문장 |
| Redis 원자 연산 (4단계) | ✅ | ✅ | "모든 서버가 공유하는 AtomicInteger" |

- `AtomicBoolean` 하나로는 "몇 개 남았는지"를 표현할 수 없어서, 결국 카운터를 확인 후 변경하는 틈이 남는다.
- 싱글톤 `AtomicInteger` 는 "원자 연산으로 빠르게 판정"이라는 방향은 맞다.
  그 카운터를 **모든 서버가 공유하는 곳**으로 옮긴 것이 4단계의 Redis 방식이다.

---

## 직접 실행해 보기

```powershell
cd backend
.\gradlew test --tests "com.couponrush.coupon.CouponIssueConcurrencyTest" --rerun
start build\reports\tests\test\classes\com.couponrush.coupon.CouponIssueConcurrencyTest.html   # Standard output 탭
```

순진한 구현의 실패를 다시 보려면 `CouponIssueService.issue()` 를 3단계-1 코드로 잠시 바꿔 실행한 뒤
`git checkout -- backend/src` 로 되돌린다.

---

## 남은 한계 → 4단계(Redis)에서 다룰 것

- **핫 로우(hot row) 경합**: 모든 요청이 `coupons` 의 **한 행**에 UPDATE 락을 걸고 순서대로 처리된다.
  락은 커밋까지 유지되므로(발급 이력 INSERT 포함) 처리량이 이 한 행의 직렬 처리 속도에 묶인다.
- **소진 후에도 DB 를 친다**: 재고가 0이 된 뒤의 900건도 매번 트랜잭션·UPDATE 를 수행한다.
- **커넥션 풀 고갈**: 요청이 락을 기다리는 동안 커넥션을 붙잡고 있어, 트래픽이 커지면 다른 API 까지 느려진다.

→ 4단계: Redis 의 원자 연산으로 재고 판정을 DB 밖으로 옮기고, 같은 테스트로 정확성과 소요 시간을 비교한다.
