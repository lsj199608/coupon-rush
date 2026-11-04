# 선착순 쿠폰 동시성 노트

선착순 쿠폰 발급에서 **"대량 동시 요청에서도 정확히 재고만큼만 발급"**을 보장하기 위한 단계별 기록.
모든 방식은 같은 테스트(`CouponIssueConcurrencyTest`, `CouponIssueStrategyBenchmarkTest`)로 검증·측정한다.

## 요약

**정확성** (재고 100)

| 단계 | 방식 | 1,000명 | 10,000명 | 1,000명 × 3회 (중복 요청) |
|---|---|---|---|---|
| 3-1 | 순진한 구현 (조회 → 자바 확인 → 메모리 +1) | ❌ **907건 발급** (9배 초과) | – | – |
| 3-2 | **조건부 UPDATE** (DB 방식 중 채택) | ✅ 100건 | ✅ 100건 | ✅ 100명 / 중복 0 |
| 3-3 | 비관적 락 (`SELECT ... FOR UPDATE`) | ✅ 100건 | ✅ 100건 | ✅ 100명 / 중복 0 |
| 3-3 | 낙관적 락 (`@Version` + 재시도) | ✅ 100건 | ✅ 100건 | ✅ 100명 / 중복 0 |
| 4 | **Redis Lua** (재고 판정을 Redis로) | ✅ 100건 | ✅ 100건 | ✅ 100명 / 중복 0 |

**소요 시간** (재고 100, 32스레드, 같은 실행에서 측정 — [4장 상세](#4-redis--재고-판정을-db-밖으로))

| 방식 | 1,000명 | 10,000명 | 1,000명 × 3회 | 최대 경합 (재고 1000 / 1000명) |
|---|---:|---:|---:|---:|
| 비관적 락 | 5,280 ms | 26,577 ms | 9,021 ms | 5,896 ms |
| 낙관적 락 | 4,428 ms | 19,132 ms | 6,346 ms | 19,232 ms (충돌 5,360회) |
| 조건부 UPDATE (참고) | 2,563 ms | 16,866 ms | 4,906 ms | 6,217 ms |
| **Redis** | **684 ms** | **2,387 ms** | **644 ms** | **3,234 ms** |

- **핵심 원인**: "확인한 다음 변경(check-then-act)"을 자바에서 두 단계로 나누면, 그 사이에 다른 트랜잭션이 끼어든다.
  `@Transactional`은 원자성(전부 커밋 / 전부 롤백)을 줄 뿐, 이 끼어들기는 막지 않는다.
- **해결 원리**: 확인과 변경을 DB가 **한 번에(원자적으로)** 하게 하거나(조건부 UPDATE),
  읽는 순간부터 잠그거나(비관적), 쓰는 순간 충돌을 감지해 다시 시도한다(낙관적).
- **DB 락의 공통 한계**: 어떤 방식이든 `coupons`의 **한 행**에 요청이 줄을 선다 → 4단계에서 판정을 Redis로 옮겼다.
- **Redis의 효과**: 소진·중복·기간 외 요청은 DB에 오지 않는다. 그래서 **요청이 재고보다 많을수록 차이가 커진다**
  (10,000명에서 비관적 락보다 약 11배, 낙관적 락보다 약 8배 빠름). 실제 선착순 이벤트가 바로 이 형태다.
- **Redis의 남은 한계**: 성공한 요청은 여전히 DB에 동기로 쓴다. 그래서 전원이 성공하는 최대 경합에서는 개선 폭이 약 2배에 그친다
  → 5단계(Kafka)에서 DB 쓰기를 비동기로 분리한다.
- **측정하다 발견한 성능 버그**: `ResourceScriptSource`로 만든 Lua 스크립트가 호출마다 파일 변경 여부를 동기화 블록 안에서 확인해서
  모든 요청이 직렬화됐다(Lua 10,000회 12.1초 → 수정 후 2.9초). [4장 참고](#측정하다-발견한-성능-버그--lua-스크립트-로딩).

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
실제 API가 쓰는 방식은 `app.coupon.issue.strategy`(환경변수 `COUPON_ISSUE_STRATEGY`)로 고른다. 3단계 당시 기본값은 조건부 UPDATE였고, 4단계부터 기본값은 **REDIS**다.

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

## 4. Redis — 재고 판정을 DB 밖으로

### 4단계 한눈에 보기

- **API 기본 발급 방식은 `REDIS`** 다. DB 방식(조건부 UPDATE / 비관적 / 낙관적)은 비교와 대체용으로 남겨 두었고,
  `COUPON_ISSUE_STRATEGY`로 바꿀 수 있다(전략 패턴: `CouponIssuer` 구현체 4개).
- **설계**: Lua 스크립트 하나로 기간, 중복, 재고를 판정하고 차감까지 원자적으로 끝낸다 → SUCCESS일 때만 DB에 저장 → DB가 실패하면 보상 Lua.
- **키**: `event:{id}:stock` / `event:{id}:issued` / `event:{id}:meta` (아래 [구조](#구조) 참고).

**3방식 성능 비교** (재고 100, 32스레드, 같은 실행에서 측정. 모든 실행에서 초과 발급 0, 중복 발급 0)

| 방식 | 1,000명 | 10,000명 | 1,000명 × 3회 (중복 요청) | 최대 경합 (재고 1000 / 1000명) |
|---|---:|---:|---:|---:|
| 비관적 락 | 5,280 ms | 26,577 ms | 9,021 ms | 5,896 ms |
| 낙관적 락 | 4,428 ms | 19,132 ms | 6,346 ms | 19,232 ms (충돌 5,360회) |
| **Redis** | **684 ms** | **2,387 ms** | **644 ms** | **3,234 ms** |
| (참고) 조건부 UPDATE | 2,563 ms | 16,866 ms | 4,906 ms | 6,217 ms |

- 10,000명: Redis가 비관적 락보다 약 **11배**, 낙관적 락보다 약 **8배** 빠르다. 요청이 재고보다 많을수록 차이가 커진다.
- 최대 경합(전원 성공)에서는 약 2배다. 성공 요청은 여전히 DB에 동기로 쓰기 때문이다 → 5단계.
- 측정 방법과 해석: [규모별 비교](#규모별-비교--1000명--10000명--중복-요청-couponissuescalebenchmarktest), [성능 버그](#측정하다-발견한-성능-버그--lua-스크립트-로딩).

**정합성 이슈와 해결 요약**

| 이슈 | 해결 | 검증 |
|---|---|---|
| Redis 성공 후 DB 저장 실패 | 보상 Lua (INCR + SREM, 원자적) | INSERT 실패 / INSERT 후 실패 / 정상 300 + 실패 700 동시 |
| 보상 중 "거짓 소진" | 데이터는 맞음, DB 실패가 드물면 허용 | 동시성 테스트에서 재고 1 남은 상태로 관찰 |
| Redis Set 유실 후 재요청 | DB 유니크 → 재고만 원복, `ALREADY_ISSUED` | `RedisCouponIssuerTest` |
| Redis 데이터 유실 / 미적재 | 503 `STOCK_NOT_READY` → sync API로 DB 기준 재적재 | `RedisCouponIssuerTest`, `CouponApiTest` |
| Redis와 DB 카운터 불일치 | 조건부 UPDATE 0행 → 롤백 + 보상 (DB 제약 = 이중 안전망) | `RedisCouponIssuerTest` |
| Lua 성공 직후 서버 종료 (보상 불가) | **미구현** — 선택지 A~E 정리, 5단계에서 결정 | [보상이 불가능한 경우](#보상이-불가능한-경우--어떤-불일치가-생기고-어떻게-복구하나-설명만-미구현) |

불변식: `Redis 남은 재고 + DB 발급 행 = 총 수량`, `Redis issued Set = DB 발급 userId 집합`.

### 구조

```
발급 요청
  └─ Lua 스크립트 (Redis, 원자적, 1회 왕복)
       기간 확인 → 중복(SISMEMBER) → 재고(GET) → DECR + SADD
       ├─ NOT_IN_PERIOD / DUPLICATE / SOLD_OUT  → 바로 응답 (DB 접근 없음)
       ├─ NOT_READY (재고 미적재)                 → DB로 이유 구분 (없는 쿠폰 / 기간 / 준비 안 됨 503)
       └─ SUCCESS → DB 트랜잭션: coupon_issues INSERT + issued_quantity +1
                     └─ 실패하면 보상 Lua: INCR + SREM (재고 되돌림)
```

| Redis 키 | 타입 | 내용 |
|---|---|---|
| `event:{couponId}:stock` | string | 남은 재고 |
| `event:{couponId}:issued` | set | 발급받은 userId |
| `event:{couponId}:meta` | hash | 발급 기간 `start`, `end` (epoch ms) |

- **중괄호 `{couponId}`는 Redis Cluster hash tag**다. 세 키가 같은 슬롯에 들어가야 Lua 스크립트 하나에서 함께 다룰 수 있다.
- **`meta` 해시를 추가한 이유**: 키 2개로는 발급 기간을 알 수 없어서 요청마다 DB를 읽어야 한다. 기간까지 Lua에서 판정해야 발급 경로에서 DB 읽기가 없어진다.
- **만료**: 발급 종료 + 7일. 발급 Set은 첫 SADD 때 생기므로, 스크립트가 재고 키와 같은 TTL을 붙인다.
- **적재 시점**: 쿠폰을 생성하면 **DB 커밋 직후**(`afterCommit`)에 적재한다. 발급 시작 전에 미리 올라가 있다.
  DB가 원본이라 롤백된 쿠폰의 재고가 Redis에 남지 않게 커밋 이후에 한다.
- **복구**: `POST /api/admin/coupons/{id}/stock/sync`는 DB 발급 이력 기준으로 다시 적재한다
  (재고 = 총 수량 - 발급 이력 수, Set = 발급받은 userId). 진행 중에 호출하면 커밋 전 발급이 사라질 수 있어서, 트래픽이 없을 때만 쓴다.

### 왜 Lua인가 — GET 후 DECR을 따로 하면 안 되는 이유

```java
// ❌ 따로 하면
Integer stock = redis.get("event:{1}:stock");        // ① 확인
if (stock > 0 && !redis.sIsMember(issued, userId)) {
    redis.decr("event:{1}:stock");                    // ② 변경
    redis.sAdd(issued, userId);
}
```

- **3단계의 lost update와 똑같은 check-then-act 문제**다. Redis 명령 하나하나는 원자적이지만, **명령과 명령 사이**에는 다른 클라이언트의 명령이 끼어든다.
  재고가 1일 때 100명이 동시에 GET하면 100명 모두 1을 보고 통과한다. 그 뒤 DECR이 100번 실행돼서 재고는 -99가 되고, 100명에게 발급된다.
  중복 검사(SISMEMBER)와 추가(SADD) 사이도 마찬가지라, 같은 사람이 두 번 받을 수 있다.
- **`DECR`만 쓰고 결과가 음수면 INCR로 되돌리는 방식**도 있다. 재고만 보면 정확하지만, 중복 확인과 기간 확인까지 원자적으로 묶을 수 없다.
  게다가 소진 후 몰린 요청마다 DECR과 INCR이 반복돼서 재고 값이 계속 음수로 출렁인다(조회 API에 음수가 보일 수 있다).
- **`MULTI/EXEC`(트랜잭션)로는 안 되나?** MULTI 안의 명령은 큐에 쌓였다가 한 번에 실행되기 때문에, **중간 결과(GET 값)를 보고 분기할 수 없다.**
  `WATCH`를 쓰면 낙관적 락이 되는데, 3-3에서 본 것처럼 경합이 심하면 재시도가 폭증한다.
- **Lua는 스크립트 전체가 하나의 명령처럼 실행된다.** Redis는 명령을 한 스레드에서 차례로 처리하므로, 스크립트가 실행되는 동안에는 다른 명령이 끼어들 수 없다.
  "기간 → 중복 → 재고 확인 → 차감 + 기록"이 **한 번의 왕복으로, 원자적으로** 끝난다. 네트워크 왕복도 4~5번에서 1번으로 줄어든다.
- 주의: 스크립트가 오래 걸리면 그동안 Redis 전체가 멈춘다. 그래서 스크립트는 O(1) 명령만 짧게 쓴다(SISMEMBER, GET, DECR, SADD 모두 O(1)).

### DB 저장과 보상

- **SUCCESS일 때만 DB에 쓴다**: `coupon_issues` INSERT를 먼저 하고, 마지막에 `issued_quantity + 1`을 한다(쿠폰 행 락을 가장 짧게 쥐기 위해).
  DB의 CHECK 제약과 조건부 UPDATE도 그대로 두었다. Redis와 DB가 어긋나면 0행 갱신으로 감지하고 보상한다(이중 안전망).
- **Redis 호출이 끝난 뒤에 DB 트랜잭션을 연다**: Redis를 기다리는 동안 DB 커넥션을 쥐고 있지 않기 위해서다(`TransactionTemplate`).
- **보상**: DB 저장이 실패하면 보상 Lua로 `INCR`과 `SREM`을 실행한다.
  유니크 위반(DB에는 이미 이력이 있는데 Redis Set이 유실된 경우)이면 재고만 되돌리고 Set에는 남긴다.
- **보상까지 실패하면** Redis 재고가 실제보다 1 적어진다. **초과 발급이 아니라 덜 발급되는 방향**이라 안전하다. 로그를 남기고, 재동기화 API로 맞춘다.

### 정확성

`CouponIssueConcurrencyTest`(재고 100 / 1000명, 같은 사용자 동시 10회)를 네 방식 모두 통과했다.
Redis: 성공 100 / 소진 900 / 발급 100행 / `issued_quantity` 100, 중복은 성공 1 / `ALREADY_ISSUED` 9.

추가 검증(`RedisCouponIssuerTest`, `RedisCouponStockTest`):
DB 저장 실패 시 재고 원복, Set 유실 후 중복 발급 시 처리, 재고 미적재(503), Redis를 비운 뒤 재동기화하고 이어서 정확히 발급되는지,
기간 경계(시작 포함, 종료 미포함), 보상할 때 키를 새로 만들지 않는지.

### 규모별 비교 — 1,000명 / 10,000명 / 중복 요청 (`CouponIssueScaleBenchmarkTest`)

재고 100 / 32스레드. 1,000명은 3회 평균(워밍업 1회 제외), 나머지는 1회 측정.
Redis는 Testcontainers 컨테이너(`redis:7.4-alpine`)를 쓴다.

**모든 실행에서 검증한 것**
- 초과 발급 0건: `coupon_issues` 행 = 재고(100)
- 중복 발급 0건: `GROUP BY user_id HAVING count(*) > 1` 결과가 0행
- 성공 응답 수 = 발급 행 = `coupons.issued_quantity`, 예상하지 못한 예외 0건, 모든 요청이 응답을 받음
- Redis 방식은 추가로 Redis 남은 재고 = 0, 발급 Set 크기 = 100

| 방식 | 1,000명 (ms) | 10,000명 (ms) | 1,000명 × 3회 (ms) | 1,000명 × 3회 결과 |
|---|---:|---:|---:|---|
| 비관적 락 | 5,280 | 26,577 | 9,021 | 성공 100 / 이미 발급 200 / 소진 2,700 |
| 낙관적 락 | 4,428 | 19,132 | 6,346 | 성공 100 / 이미 발급 200 / 소진 2,700 |
| 조건부 UPDATE (참고) | 2,563 | 16,866 | 4,906 | 성공 100 / 이미 발급 200 / 소진 2,700 |
| **Redis** | **684** | **2,387** | **644** | 성공 100 / 이미 발급 200 / 소진 2,700 |

- **1,000명 × 3회**: 사용자 1,000명이 각자 3번씩, 순서를 섞어서 동시에 요청한다(3,000건). 당첨자 100명의 나머지 요청 200건은 `ALREADY_ISSUED`,
  나머지 2,700건은 `SOLD_OUT`이다. 네 방식 모두 한 사람이 두 장 받은 경우가 없었다.
- **10,000명에서 Redis는 비관적 락보다 약 11배, 낙관적 락보다 약 8배, 조건부 UPDATE보다 약 7배 빠르다.**
  DB 방식은 요청 수에 비례해 늘어난다(요청 1건당 약 1.7~2.7ms). 실패할 요청도 모두 DB 트랜잭션과 락 대기를 거치기 때문이다.
  Redis는 요청 1건당 약 0.24ms이고, 그마저 대부분 Docker Desktop의 네트워크 왕복이다(아래 진단 참고).

### 재고 / 경합 시나리오별 비교 (`CouponIssueStrategyBenchmarkTest`)

요청 1,000 / 32스레드 / 워밍업 1회를 빼고 3회 평균. 모든 회차에서 초과 발급 0건.

| 방식 | A. 재고 100 (ms) | B. 재고 1000 = 전원 성공 (ms) | 낙관적 락 충돌 (A / B) |
|---|---:|---:|---:|
| 비관적 락 | 2,149 | 5,896 | - |
| 낙관적 락 | 3,141 | 19,232 | 506 / 5,360 |
| 조건부 UPDATE (참고) | 2,076 | 6,217 | - |
| **Redis** | **516** | **3,234** | - |

> 로컬 PC(Windows + Docker Desktop) 기준이다. 같은 조건이라도 실행마다 DB 방식은 ±30~50% 정도 흔들린다.
> (예: 조건부 UPDATE의 B는 3단계에서 2,413ms, 이번에는 6,217ms). 절대값보다 **같은 실행 안에서의 순서와 배율**을 본다.

### 측정하다 발견한 성능 버그 — Lua 스크립트 로딩

10,000명을 처음 측정했을 때 Redis가 **13.4초**로, DB 방식(18~34초)과 큰 차이가 없었다.
재고가 100이라 9,900건은 DB에 가지 않아야 하는데 이상해서, 발급 로직을 빼고 Redis 호출만 따로 측정했다.

| 측정 (32스레드, 10,000회) | 수정 전 | 수정 후 |
|---|---:|---:|
| Redis 단일 왕복 (1스레드) | 약 2.0 ms | 약 1.8 ms |
| 단순 명령 `EXISTS` | 2,405 ms | 1,903 ms |
| **Lua `tryIssue`** | **12,145 ms** | **2,890 ms** (약 4배) |

- **원인**: `ResourceScriptSource`(파일 리소스)로 만든 `DefaultRedisScript`는 SHA를 꺼낼 때마다 **동기화 블록 안에서 "스크립트 파일이 바뀌었나"를 확인**한다(파일 시스템 조회).
  그래서 모든 발급 요청이 이 자리에서 한 줄로 섰다. 같은 연결로 보내는 단순 명령은 빠른데 Lua만 5배 느렸던 이유다.
- **수정**: 기동할 때 스크립트 내용을 한 번만 읽어서 문자열로 `DefaultRedisScript(String, Class)`를 만든다. 변경 확인이 없어진다.
- **교훈**: "Redis를 썼으니 빠르다"가 아니라 **측정해서 확인**해야 한다. 그리고 이상한 수치는 구성 요소를 하나씩 떼어서 측정하면 원인이 보인다.
- 수정 후 남은 시간은 대부분 Windows Docker Desktop의 포트 포워딩 왕복이다(1건당 약 1.8ms). Linux 서버에서는 훨씬 작아진다.

### 해석

- **요청이 재고보다 많을수록 Redis의 이점이 커진다**: 실패할 요청(소진, 중복, 기간 외)이 Redis Lua 한 번으로 끝나고 DB에 오지 않는다.
  DB 방식은 실패할 요청도 트랜잭션을 열고, 쿠폰 행 락을 기다리고, 쿼리를 실행한 다음에야 실패한다.
  그래서 1,000명 → 10,000명으로 늘면 DB 방식은 시간이 비례해서 늘고(약 5~7배), Redis는 완만하게 는다(약 3.5배).
- **B(전원 성공)에서 개선 폭이 작은 이유**: 1000건 **모두 성공**이라 1000건 모두 DB에 쓴다(INSERT + 쿠폰 행 UPDATE).
  재고 판정은 Redis로 옮겼지만 **성공 요청의 DB 쓰기는 여전히 동기적이고, `coupons` 한 행을 갱신**한다.
  즉 병목이 "판정"에서 "성공 요청의 DB 쓰기"로 옮겨 갔다.
- **실제 선착순 이벤트는 A에 가깝다**: 재고보다 요청이 훨씬 많다(예: 재고 1만, 요청 100만). 실패 요청이 DB를 안 거치는 효과가 그대로 커진다.
- **커넥션 풀 관점**: DB 방식은 1000건 모두 커넥션을 쥐고 락을 기다린다. Redis 방식은 성공한 요청만, 그것도 짧게 쓴다.
  발급이 몰려도 로그인이나 조회 같은 다른 API가 커넥션을 얻기 쉬워진다(수치로는 측정하지 않았다).

### 보상 검증 — DB 저장 실패를 강제로 일으켜 보기

| 테스트 | 강제하는 실패 | 확인한 것 |
|---|---|---|
| `RedisCouponIssuerTest` | INSERT 단계: 존재하지 않는 userId (FK 위반) | 재고 원복, Set에서 제거, 되돌린 재고를 다른 사람이 정상 발급 |
| `RedisCouponIssuerTest` | INSERT **후** 단계: DB 카운터를 일부러 꽉 채워 조건부 UPDATE 0행 | 먼저 실행된 INSERT까지 롤백, 재고 원복, Set에서 제거 |
| `RedisCouponIssuerTest` | 위 실패 후 원인 해결 | 같은 사용자가 다시 요청하면 정상 발급 |
| `RedisCouponIssuerTest` | Redis Set 유실 + DB 유니크 위반 | 재고만 원복, Set에는 남김, `ALREADY_ISSUED` |
| `RedisCompensationConcurrencyTest` | 정상 300명 + 실패 강제 700건을 섞어 동시 요청 (재고 100) | 아래 불변식 |

**동시성 + 강제 실패 결과**

```
응답: SUCCESS 99 / SOLD_OUT 687 / DB 예외(보상됨) 214
DB 발급 행 99 / DB 카운터 99 / Redis 남은 재고 1 / Redis Set 99
```

- **불변식은 지켜졌다**: `Redis 남은 재고 + DB 발급 행 = 총 수량`(1 + 99 = 100), `Redis Set 크기 = DB 발급 행`(99),
  실패한 userId는 Set과 DB 어디에도 남지 않음.
- **하지만 "거짓 소진"이 생긴다**: 실패할 요청이 재고를 잠깐 잡고 있다가 보상으로 돌려주기까지 수 ms가 걸린다.
  그 사이에 요청한 정상 사용자는 재고 0을 보고 `SOLD_OUT`을 받는다. 그래서 재고 1이 남았는데도 소진 응답을 받은 사람이 있다.
  남은 재고는 다음 요청자가 가져가므로 **데이터는 맞지만, 응답은 공정하지 않을 수 있다.**
  DB 실패가 드물면 무시할 만하지만, DB 장애처럼 실패가 몰리면 눈에 띈다.
  (완화책: 남은 재고가 있는데 `SOLD_OUT`이면 짧게 한 번 재시도, 또는 소진 판정 직후 "곧 다시 열릴 수 있음" 안내)

### 보상이 불가능한 경우 — 어떤 불일치가 생기고 어떻게 복구하나 (설명만, 미구현)

보상은 "같은 프로세스가 살아서 예외를 잡을 때"만 동작한다. 아래 상황에서는 보상 코드가 실행되지 않는다.

| 상황 | Redis | DB | 결과 |
|---|---|---|---|
| ① Lua SUCCESS 직후 서버가 죽음 (OOM, 강제 종료, 배포 중 kill) | 재고 -1, Set에 userId | 이력 없음 | **유령 발급**: 재고가 하나 줄었는데 아무도 못 받음, 그 사용자는 다시 신청하면 `DUPLICATE` |
| ② DB 실패 후 보상 Lua도 실패 (Redis 순간 장애, 타임아웃) | 재고 -1, Set에 userId | 이력 없음 | ①과 같음 (로그만 남음) |
| ③ DB 커밋은 됐는데 응답 직전에 서버가 죽음 | 재고 -1, Set에 userId | 이력 있음 | 데이터는 일관됨. 사용자는 실패로 알지만 내 쿠폰 목록에는 있음 |
| ④ DB 타임아웃 (실제로는 커밋됐는지 모름) 후 보상 실행 | 재고 원복, Set에서 제거 | 이력이 있을 수도 있음 | **역방향 불일치**: 재고가 하나 더 풀림 → 최악의 경우 초과 발급 1건 (DB 유니크·CHECK 제약이 최종적으로 막는다) |

①②는 **덜 발급되는 방향**(안전하지만 재고 손실), ④는 **더 풀리는 방향**이라 더 위험하다.
④는 DB 제약(`issued_quantity <= total_quantity` CHECK, 조건부 UPDATE)이 마지막 안전망이라 실제 초과 발급은 막히고, 해당 요청이 DB에서 실패한다.

**공통 신호**: 정상이라면 언제나 `Redis 남은 재고 + DB 발급 행 = 총 수량` 그리고 `Redis Set = DB 발급 userId 집합`이다.
이 두 식이 깨졌는지 보면 불일치를 감지할 수 있다.

#### 선택지

**A. 대조(reconciliation) 배치** — 가장 단순한 사후 복구
- 진행 중인 쿠폰마다 주기적으로(예: 1분) `SMEMBERS issued`와 DB의 `user_id` 목록을 비교한다.
- **Set에만 있고 DB에 없는 userId** = 유령 발급 후보 → Lua로 `SREM` + `INCR`해서 재고를 되돌린다.
- **문제**: 지금 막 Redis에서 성공하고 DB에 쓰는 중인 요청도 "Set에만 있음"으로 보인다. 이걸 되돌리면 ④처럼 재고가 더 풀린다.
  그래서 **"성공한 지 N초가 지난 것만"** 처리해야 하는데, 지금 Set에는 시각 정보가 없다 → Set 대신 `ZSET`(score = 성공 시각)으로 바꿔야 한다.
- 장점: 기존 흐름을 거의 안 바꾼다. 단점: N초 동안은 불일치가 남고, 배치가 실행되는 동안의 경쟁 조건을 신경 써야 한다.

**B. 대기(pending) 기록 + 복구 워커** — A를 더 안전하게
- Lua가 SUCCESS를 낼 때 `event:{id}:pending`(hash 또는 zset: userId → 시각)에도 함께 기록한다(같은 스크립트라 원자적).
- DB 커밋 후 pending에서 제거한다. 보상할 때도 제거한다.
- 워커가 "N초 이상 된 pending"만 확인해서, DB에 이력이 있으면 pending만 지우고, 없으면 재고를 되돌린다.
- 장점: 검사 대상이 "처리 중이던 것"으로 좁아지고 판정이 명확하다. 단점: Redis 키와 쓰기가 하나 늘고, 워커를 운영해야 한다.

**C. Lua 안에서 Redis Stream에 기록 (`XADD`)** — 유실 구간 자체를 없앤다
- 발급 Lua에서 `DECR` + `SADD`와 **같은 스크립트 안에서** `XADD event:{id}:issue-log * userId ...`까지 실행한다.
  이렇게 하면 "Redis 차감은 됐는데 기록은 없다"는 상태가 생기지 않는다(원자적).
- DB 저장은 Stream 컨슈머(Consumer Group)가 한다. 서버가 죽어도 기록이 Stream에 남아 있어서 다른 컨슈머가 이어서 처리한다(at-least-once).
  중복 처리는 `uk_coupon_issues_coupon_user` 유니크 제약으로 멱등하게 막는다.
- 장점: ①②가 구조적으로 사라진다. 응답도 DB를 기다리지 않아서 빨라진다(비동기).
  단점: "발급 완료" 응답 시점에 DB에는 아직 없을 수 있다(최종 일관성). Redis의 영속성(AOF)에 의존한다.

**D. Kafka + 트랜잭셔널 아웃박스** — 5단계 방향
- Redis SUCCESS 후 Kafka에 발급 이벤트를 발행하고, 컨슈머가 DB에 저장한다.
- 단, **Lua와 Kafka 발행은 원자적으로 묶을 수 없다.** 그래서 Redis와 Kafka 사이에서 죽으면 ①이 그대로 남는다.
  이 구간을 막으려면 C(Redis Stream에 먼저 기록)나 B(pending)를 함께 써야 한다.
- 장점: 처리량, 재처리, 다른 서비스(알림 등)로의 확장. 단점: 구성 요소와 운영 부담이 가장 크다.

**E. 이벤트 종료 후 전체 대조 + 수동 처리** — 최후의 방어선
- 이벤트가 끝나면 `Redis 남은 재고 + DB 발급 = 총 수량`을 검사한다. 어긋나면 알림을 보내고,
  유령 발급 사용자에게는 보상 쿠폰을 지급하거나 CS로 처리한다.
- 어떤 방식을 택하든 **모니터링 지표**(위 두 불변식)로는 항상 두는 것이 좋다.

#### 추천

- **지금(4단계)**: 보상 Lua + DB 제약 + 재동기화 API + **불변식 모니터링(E)**. 서버가 갑자기 죽는 일은 드물고, 생겨도 덜 발급되는 방향이라 안전하다.
- **5단계**: **C(Lua 안에서 Stream에 기록)** 또는 **B + D(pending + Kafka)**. 성공 요청의 동기 DB 쓰기(현재 병목)도 함께 없앨 수 있다.
  C는 구성이 단순하고, D는 Kafka라는 학습 목표에 맞는다. 5단계를 설계할 때 이 둘을 비교해서 정한다.

### Redis 방식의 한계와 다음 단계

1. **성공 요청의 동기 DB 쓰기**: 위 B 결과의 원인이다 → **5단계(Kafka)**: Redis가 SUCCESS를 판정하면 발급 이벤트만 발행하고 바로 응답한다.
   DB 저장은 컨슈머가 비동기로, 묶어서 처리한다. `issued_quantity`도 컨슈머가 집계한다.
2. **두 저장소의 정합성**: Redis와 DB가 어긋날 수 있다(보상 실패, Redis 장애 등). 지금은 보상, 재동기화 API, DB 제약으로 막는다.
   비동기로 바꾸면 "Redis는 성공했는데 DB 저장은 나중"이 되므로, 메시지 유실 방지와 멱등 처리가 필요하다.
3. **Redis 장애 = 발급 중단**: Redis가 죽으면 발급할 수 없다(DB로 자동 전환하지 않는다. 전환하는 순간 두 재고가 갈라진다).
   운영에서는 Redis 복제(Replica/Sentinel)와 AOF 영속성이 필요하다.
4. **재적재(sync)는 트래픽이 없을 때만** 해야 한다(진행 중인 발급과 경쟁하기 때문이다).

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
| `coupon/issue/redis/RedisCouponIssuer` | Lua 판정 → SUCCESS일 때만 DB 저장 → 실패하면 보상 |
| `coupon/issue/redis/RedisCouponStock` | Redis 키, Lua 스크립트 호출 (발급 / 적재 / 보상) |
| `resources/redis/*.lua` | `coupon-issue.lua`, `coupon-stock-load.lua`, `coupon-issue-restore.lua` |
| `coupon/CouponService` | 생성 시 커밋 후 Redis 적재(`afterCommit`), `syncStock`(DB 기준 재적재) |
| `coupon/CouponIssueService` | 설정된 방식으로 위임. **`@Transactional`을 붙이지 않는다** (낙관적 락 재시도가 깨짐) |
| `db/migration/V3__add_coupon_version.sql` | `coupons.version` 컬럼 |
| `test/.../CouponIssueConcurrencyTest` | 정확성 (방식별 파라미터 테스트) |
| `test/.../CouponIssueStrategyBenchmarkTest` | 소요 시간: 재고 100 / 1000, 요청 1000 (`@Tag("benchmark")`) |
| `test/.../CouponIssueScaleBenchmarkTest` | 소요 시간 + 정합성: 1,000명 / 10,000명 / 1,000명 × 3회 (`@Tag("benchmark")`) |

## 직접 실행해 보기

```powershell
cd backend
# 정확성 (세 방식 모두)
.\gradlew test --tests "com.couponrush.coupon.CouponIssueConcurrencyTest" --rerun
start build\reports\tests\test\classes\com.couponrush.coupon.CouponIssueConcurrencyTest.html   # Standard output 탭

# 소요 시간 비교 (두 benchmark 합쳐 약 8분)
.\gradlew benchmark
notepad build\benchmark\coupon-issue-strategies.md   # 재고 100 / 1000 시나리오
notepad build\benchmark\coupon-issue-scale.md        # 1,000명 / 10,000명 / 중복 요청

# 하나만 돌리기
.\gradlew benchmark --tests "com.couponrush.coupon.CouponIssueScaleBenchmarkTest"
```

- 순진한 구현의 실패를 다시 보려면 `PessimisticLockCouponIssuer`의 `findByIdForUpdate`를 `findById`로 잠시 바꿔 실행한 뒤
  `git checkout -- backend/src`로 되돌린다.
- 실제 서버의 방식을 바꾸려면 `infra/.env`에 `COUPON_ISSUE_STRATEGY=redis`(또는 `pessimistic-lock`, `optimistic-lock`)를 넣고 bootRun을 재시작한다.
  진행 중인 이벤트에서 방식을 바꾸면 Redis와 DB 재고가 갈라지므로, 바꾼 뒤에는 `POST /api/admin/coupons/{id}/stock/sync`로 맞춘다.
- Redis 상태 보기: `docker exec coupon-rush-redis redis-cli GET "event:{1}:stock"`, `SCARD "event:{1}:issued"`
