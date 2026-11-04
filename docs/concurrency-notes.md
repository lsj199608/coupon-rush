# 선착순 쿠폰 동시성 노트

선착순 쿠폰 발급에서 **"대량 동시 요청에서도 정확히 재고만큼만 발급"**을 보장하기 위한 단계별 기록.
모든 방식은 같은 테스트(`CouponIssueConcurrencyTest`, `CouponIssueStrategyBenchmarkTest`)로 검증·측정한다.

## 요약

| 단계 | 방식 | 재고 100 / 1000명 결과 | 최대 경합(재고 1000 / 1000명) 평균 | 비고 |
|---|---|---|---:|---|
| 3-1 | 순진한 구현 (조회 → 자바 확인 → 메모리 +1) | ❌ **907건 발급** (9배 초과) | – | lost update |
| 3-2 | **조건부 UPDATE** (채택, 기본값) | ✅ 100건 | **2,413 ms** | 락 구간 최단, 재시도 없음 |
| 3-3 | 비관적 락 (`SELECT ... FOR UPDATE`) | ✅ 100건 | 5,143 ms (약 2배) | 락 구간이 SELECT부터 |
| 3-3 | 낙관적 락 (`@Version` + 재시도) | ✅ 100건 | 12,815 ms (약 5배) | 1000건에 충돌 5,221회 |
| 4 | Redis | (예정) | (예정) | DB 한 행 병목 제거 |

- **핵심 원인**: "확인한 다음 변경(check-then-act)"을 자바에서 두 단계로 나누면, 그 사이에 다른 트랜잭션이 끼어든다.
  `@Transactional`은 원자성(전부 커밋 / 전부 롤백)을 줄 뿐, 이 끼어들기는 막지 않는다.
- **해결 원리**: 확인과 변경을 DB가 **한 번에(원자적으로)** 하게 하거나(조건부 UPDATE),
  읽는 순간부터 잠그거나(비관적), 쓰는 순간 충돌을 감지해 다시 시도한다(낙관적).
- **DB 락의 공통 한계**: 어떤 방식이든 `coupons`의 **한 행**에 요청이 줄을 선다 → 4단계에서 Redis로 옮긴다.

---

## 테스트 환경과 시나리오

| 항목 | 값 |
|---|---|
| 요청 | 서로 다른 사용자 1,000명 (JDBC 배치로 미리 생성) |
| 동시성 | 32 스레드, `ExecutorService` + `CountDownLatch`로 동시에 출발 |
| 호출 대상 | 발급 로직(`CouponIssuer.issue()`) 직접 호출 (HTTP·인증 오버헤드 제외) |
| DB | Testcontainers PostgreSQL 17 (pgvector 이미지), 격리수준 READ COMMITTED, HikariCP 풀 10 |
| 측정 | 출발 신호 ~ 마지막 요청 종료 (회원 생성 등 준비 시간 제외) |

- **H2는 쓰지 않는다**: 행 락, MVCC, UPDATE 재평가 동작이 PostgreSQL과 달라서 동시성 결과를 믿을 수 없다.
- **시나리오 A (기본)**: 재고 100 / 요청 1000 → 기대값: 성공 100 / `COUPON_SOLD_OUT` 900 / 발급 100행 / `issued_quantity` 100
- **시나리오 B (최대 경합)**: 재고 1000 / 요청 1000 → 1000명 전원이 같은 행을 끝까지 경쟁
- **중복 시나리오**: 같은 사용자가 동시에 10번 요청 → 기대값: 성공 1 / `COUPON_ALREADY_ISSUED` 9

---

## 3-1. 순진한 구현 — 초과 발급 재현

```java
@Transactional
public CouponIssue issue(Long couponId, Long userId) {
    Coupon coupon = couponRepository.findById(couponId).orElseThrow(...);   // 락 없이 읽음
    if (!coupon.isInIssuePeriod(now)) throw COUPON_NOT_IN_PERIOD;
    if (coupon.isSoldOut()) throw COUPON_SOLD_OUT;                          // ① 확인
    if (couponIssueRepository.existsByCouponIdAndUserId(...)) throw COUPON_ALREADY_ISSUED;

    coupon.increaseIssuedQuantity();                                         // ② 메모리에서 +1 (dirty checking)
    return couponIssueRepository.save(new CouponIssue(...));
}
```

단일 요청 테스트는 모두 통과한다. 동시 요청 결과:

| 지표 | 기대 | 실제 |
|---|---:|---:|
| 성공 응답 | 100 | **907** |
| `COUPON_SOLD_OUT` | 900 | 93 |
| `coupon_issues` 행 (실제 발급) | 100 | **907** (9배 초과) |
| `coupons.issued_quantity` | 100 | 100 |

같은 사용자 동시 10회: 성공 1 / `COUPON_ALREADY_ISSUED` 1 / **처리되지 않은 `DataIntegrityViolationException` 8** (API에서는 500)

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

- Hibernate dirty checking은 `SET issued_quantity = 6`처럼 **읽어 둔 값으로 계산한 결과**를 쓴다.
  UPDATE 순간에 행 락은 걸리지만, 기다린 쪽도 **이미 계산해 둔 옛날 값**으로 덮어쓴다.
- 카운터가 실제 발급보다 느리게 올라가니 "아직 남았다"는 판정이 계속 통과하고, 발급 행만 쌓인다.
- `issued_quantity`가 정확히 100에서 멈춘 것은 CHECK 제약(`issued_quantity <= total_quantity`) 덕분이다.
  하지만 **이 제약은 초과 발급을 막지 못한다**. 문제의 본질은 카운터와 실제 발급 행이 어긋나는 것이다.
- 중복 요청: 10개 요청이 모두 `exists` 검사를 동시에 통과한다. 유니크 제약이 최종적으로 막아서 데이터는 1건이지만,
  예외를 처리하지 않아서 사용자에게는 500이 나간다.

---

## 3-2. 조건부 UPDATE — 수정 (채택)

```java
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query("""
    UPDATE Coupon c
    SET c.issuedQuantity = c.issuedQuantity + 1, c.version = c.version + 1, c.updatedAt = :now
    WHERE c.id = :couponId AND c.issuedQuantity < c.totalQuantity
      AND c.issueStartAt <= :now AND c.issueEndAt > :now
    """)
int increaseIssuedQuantity(Long couponId, Instant now);
```

```java
@Transactional
public CouponIssue issue(Long couponId, Long userId) {
    support.ensureNotIssued(couponId, userId);                   // 빠른 실패 (최종 판정 아님)
    if (couponRepository.increaseIssuedQuantity(couponId, now) == 0)
        throw failureReason(...);                                // 없음 / 기간 아님 / 소진 구분
    return support.saveIssue(couponId, userId, now);             // 유니크 위반 → COUPON_ALREADY_ISSUED (롤백 → 차감 취소)
}
```

### 왜 정확한가

1. **확인과 변경이 한 문장**: `issued_quantity + 1`은 읽어 둔 값이 아니라 DB의 현재 값 기준으로 계산된다.
2. **PostgreSQL READ COMMITTED의 UPDATE 재평가**: 같은 행을 먼저 수정 중인 트랜잭션이 있으면 커밋될 때까지 기다린 뒤,
   **최신 행 버전으로 WHERE를 다시 평가**한다. 재고가 100이 된 뒤의 UPDATE는 0행 갱신 → `COUPON_SOLD_OUT`.
3. **중복 발급은 유니크 제약이 최종 판정**: `exists` 검사를 동시에 통과해도 INSERT에서 하나만 성공한다.
   실패한 쪽은 예외가 나고 트랜잭션이 롤백되면서 **같은 트랜잭션에서 차감한 재고도 함께 원복**된다.
4. **`version`도 함께 올린다**: 벌크 UPDATE는 `@Version`을 자동으로 올리지 않는다.
   방식이 섞여 쓰여도 낙관적 락이 변경을 감지할 수 있게 직접 올린다.

결과: 성공 100 / 소진 900 / 발급 100행 / `issued_quantity` 100 (반복 실행해도 매번 같음).
같은 사용자 동시 10회: 성공 1 / `COUPON_ALREADY_ISSUED` 9 / 발급 1행.

---

## 3-3. DB 락 방식 비교

세 방식을 같은 인터페이스(`CouponIssuer`) 아래 구현하고, 같은 테스트로 정확성과 소요 시간을 비교했다.
실제 API가 쓰는 방식은 `app.coupon.issue.strategy`(환경변수 `COUPON_ISSUE_STRATEGY`)로 고른다. 기본값은 조건부 UPDATE.

| 방식 | 구현 | 락을 쥐는 구간 |
|---|---|---|
| 조건부 UPDATE | `UPDATE ... SET issued = issued + 1 WHERE issued < total` | UPDATE ~ 커밋 |
| 비관적 락 | `@Lock(PESSIMISTIC_WRITE)` → `SELECT ... FOR UPDATE` → 자바에서 확인 → `+1` | **SELECT ~ 커밋** (더 일찍, 더 오래) |
| 낙관적 락 | `@Version` → 락 없이 읽고 `+1` → `UPDATE ... WHERE version = ?` → 0행이면 롤백 후 재시도 (최대 100회, jitter backoff) | 없음 (UPDATE 순간 충돌 감지) |

> 비관적 락 코드는 3-1 순진한 구현과 로직이 같다. `findById` 대신 `findByIdForUpdate`를 쓰는 한 줄만 다르다.
> **이 한 줄이 907건과 100건을 가른다.**

### 정확성 — 세 방식 모두 통과

`CouponIssueConcurrencyTest`를 방식별로 실행한 결과:

| 방식 | 성공 | 소진 | 발급 행 | `issued_quantity` | 같은 사용자 동시 10회 |
|---|---:|---:|---:|---:|---|
| 조건부 UPDATE | 100 | 900 | 100 | 100 | 성공 1 / 이미 발급됨 9 |
| 비관적 락 | 100 | 900 | 100 | 100 | 성공 1 / 이미 발급됨 9 |
| 낙관적 락 | 100 | 900 | 100 | 100 | 성공 1 / 이미 발급됨 9 |

### 소요 시간 — `./gradlew benchmark` (워밍업 1회 제외, 3회 측정)

모든 회차에서 초과 발급 0건, 카운터 = 실제 발급 행.

**시나리오 A. 재고 100 / 요청 1000** (100명 성공 후 900명은 소진 응답)

| 방식 | 평균 (ms) | 최소 | 최대 | 성공 | 버전 충돌 (재시도) |
|---|---:|---:|---:|---:|---:|
| 조건부 UPDATE | 3,746 | 2,705 | 4,642 | 100 | - |
| 비관적 락 | 3,033 | 2,860 | 3,299 | 100 | - |
| 낙관적 락 | 3,775 | 3,431 | 4,219 | 100 | 617 |

> 같은 조건의 다른 측정에서는 조건부 2,584 / 비관적 2,895 / 낙관적 3,583 ms로 순서가 바뀌었다.
> 최소~최대 범위가 서로 겹치므로 **이 시나리오에서 세 방식의 차이는 측정 오차 수준**이다.
> 경합은 처음 100건에만 있고, 나머지 900건은 소진 판정으로 빨리 끝나기 때문이다.
> 그래도 낙관적 락은 성공 100건을 위해 **617번 충돌하고 재시도**했다 (성공 1건당 약 6번).

**시나리오 B. 재고 1000 / 요청 1000** (전원이 끝까지 경쟁 — 최대 경합)

| 방식 | 평균 (ms) | 최소 | 최대 | 성공 | 버전 충돌 (재시도) |
|---|---:|---:|---:|---:|---:|
| 조건부 UPDATE | **2,413** | 1,985 | 3,265 | 1000 | - |
| 비관적 락 | 5,143 | 4,942 | 5,349 | 1000 | - |
| 낙관적 락 | 12,815 | 9,365 | 14,695 | 1000 | **5,221** |

경합이 계속되면 차이가 분명해진다: **조건부 UPDATE < 비관적 락 (약 2배) < 낙관적 락 (약 5배)**.

> 로컬 PC(Windows + Docker Desktop) 기준 참고값이다. 절대값보다 방식 간 순서와 배율을 본다.

### 해석

- **조건부 UPDATE가 가장 빠른 이유**: 락을 UPDATE 순간에만 잡고, "읽기 → 자바에서 판단 → 쓰기" 왕복이 없다.
  한 요청이 행을 쥐고 있는 시간이 가장 짧다.
- **비관적 락이 약 2배 느린 이유**: SELECT부터 락을 잡아서 "SELECT → 자바 판단 → INSERT → UPDATE → 커밋" 동안 줄 전체가 멈춘다.
  행을 쥐는 시간이 길어진 만큼 직렬 처리량이 줄어든다.
- **낙관적 락이 가장 느린 이유**: 32개 스레드가 같은 행을 노리면 매 라운드 1개만 성공하고 나머지는 롤백한다.
  1000건 성공에 5,221번 충돌했다 (성공 1건당 약 5번 헛수고). 실패할 때마다 트랜잭션, SELECT, 롤백, 백오프 대기를 다시 거친다.
  이번에는 최대 100회 재시도 안에 모두 성공했지만, 경합이 더 심해지면 **재고가 남았는데도 `COUPON_ISSUE_CONFLICT`로
  실패하는 사용자**가 생길 수 있다 (선착순 공정성 훼손).

### 이 시나리오(선착순, 경합 극심)에 맞는 방식

1. **조건부 UPDATE** (채택) — 가장 짧은 락, 재시도 없음, 코드도 가장 단순하다.
2. **비관적 락** — 정확하고 순서대로 처리된다. 확인 로직이 복잡해서 한 문장의 UPDATE로 표현하기 어려울 때 쓰는 선택지.
3. **낙관적 락** — 이 시나리오에는 맞지 않는다. 충돌이 드문 경우(예: 관리자가 쿠폰 정보를 수정하는데 가끔 겹치는 경우)에 맞는 도구다.

### DB 락 방식의 공통 한계 → 4단계(Redis)에서 다룰 것

1. **핫 로우(hot row) 직렬화**: 어떤 방식이든 결국 `coupons`의 **한 행**을 순서대로 고친다.
   처리량의 상한이 "이 행을 한 번 고치는 데 걸리는 시간"에 묶여서, **앱 서버를 늘려도 빨라지지 않는다**.
2. **커넥션 풀 고갈**: 락을 기다리는 요청도 DB 커넥션을 쥐고 있다. 풀(기본 10개)이 발급 대기로 가득 차면
   로그인이나 조회 같은 **다른 API까지 커넥션을 못 얻어 느려지거나 타임아웃**이 난다.
3. **소진된 뒤에도 DB를 친다**: 재고가 0이 된 다음에도 요청마다 트랜잭션, 쿼리, (비관적이면) 락 대기까지 거친다.
4. **락 대기 시간 제어가 어렵다**: PostgreSQL의 `FOR UPDATE`에는 ms 단위 대기 제한이 없다
   (`NOWAIT` / `SKIP LOCKED` 또는 세션 `lock_timeout`만 가능). 대기가 길어지면 요청 스레드가 쌓인다.
5. **낙관적 락 고유의 한계**: 경합에 비례해 재시도가 폭증하고 DB 부하도 늘어난다. 재시도 상한을 넘으면 재고가 있어도 실패한다.

→ 4단계: 재고 판정을 Redis의 원자 연산으로 옮겨 **DB 한 행에 줄 서는 구조 자체**를 없앤다.
`IssueStrategy`에 Redis 방식을 추가하면 같은 테스트와 benchmark에 자동으로 포함된다.

---

## 해결책처럼 보이지만 아닌 것들

| 시도 | 서버 1대 | 서버 여러 대 | 왜 |
|---|:---:|:---:|---|
| 엔티티에 `AtomicBoolean soldOut` / `AtomicInteger` 필드 | ❌ | ❌ | JPA는 트랜잭션마다 DB에서 **새 Coupon 객체**를 만든다. 객체가 요청마다 달라서 공유되는 것이 없다. 경쟁은 자바 객체가 아니라 **DB 행**에서 일어난다 |
| `@Transactional` + `synchronized` | ❌ | ❌ | 커밋은 프록시가 메서드 **바깥**에서 한다. 락이 먼저 풀려서 다음 스레드가 커밋 전 값을 읽는다 |
| 싱글톤 빈의 `AtomicInteger` 카운터 | ✅ | ❌ | 서버마다 카운터가 따로 있어 N대면 N배 발급. 재시작하면 초기화되고, DB 트랜잭션이 실패하면 직접 되돌려야 한다 |
| **DB 조건부 UPDATE** (채택) | ✅ | ✅ | 확인 + 차감이 DB 한 문장 |
| Redis 원자 연산 (4단계) | ✅ | ✅ | "모든 서버가 공유하는 AtomicInteger" |

- `AtomicBoolean` 하나로는 "몇 개 남았는지"를 표현할 수 없어서, 결국 카운터를 확인한 다음 변경하는 틈이 남는다.
- 싱글톤 `AtomicInteger`는 "원자 연산으로 빠르게 판정"한다는 방향은 맞다.
  그 카운터를 **모든 서버가 공유하는 곳**으로 옮긴 것이 4단계의 Redis 방식이다.

---

## 구현 위치

| 파일 | 역할 |
|---|---|
| `coupon/issue/IssueStrategy` | `CONDITIONAL_UPDATE`(기본) / `PESSIMISTIC_LOCK` / `OPTIMISTIC_LOCK` |
| `coupon/issue/CouponIssuer` | 공통 인터페이스. 트랜잭션은 구현체가 직접 관리 |
| `coupon/issue/CouponIssueSupport` | 공통: 중복 빠른 실패, 기간·소진 검사, 유니크 위반 → `COUPON_ALREADY_ISSUED` |
| `coupon/issue/ConditionalUpdateCouponIssuer` | 조건부 UPDATE |
| `coupon/issue/PessimisticLockCouponIssuer` | `findByIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)`) |
| `coupon/issue/OptimisticLockCouponIssuer` | `@Version` + `TransactionTemplate` 재시도 (`app.coupon.issue.optimistic-max-attempts`, 기본 100) |
| `coupon/CouponIssueService` | 설정된 방식으로 위임. **`@Transactional`을 붙이지 않는다** (낙관적 락 재시도가 깨짐) |
| `db/migration/V3__add_coupon_version.sql` | `coupons.version` 컬럼 |
| `test/.../CouponIssueConcurrencyTest` | 정확성 (방식별 파라미터 테스트) |
| `test/.../CouponIssueStrategyBenchmarkTest` | 소요 시간 (`@Tag("benchmark")`) |

## 직접 실행해 보기

```powershell
cd backend
# 정확성 (세 방식 모두)
.\gradlew test --tests "com.couponrush.coupon.CouponIssueConcurrencyTest" --rerun
start build\reports\tests\test\classes\com.couponrush.coupon.CouponIssueConcurrencyTest.html   # Standard output 탭

# 소요 시간 비교 (약 3~4분)
.\gradlew benchmark
notepad build\benchmark\coupon-issue-strategies.md
```

- 순진한 구현의 실패를 다시 보려면 `PessimisticLockCouponIssuer`의 `findByIdForUpdate`를 `findById`로 잠시 바꿔 실행한 뒤
  `git checkout -- backend/src`로 되돌린다.
- 실제 서버의 방식을 바꾸려면 `infra/.env`에 `COUPON_ISSUE_STRATEGY=pessimistic-lock`(또는 `optimistic-lock`)을 넣고 bootRun을 재시작한다.
