# 선착순 쿠폰 동시성 노트

선착순 쿠폰 발급에서 **"대량 동시 요청에서도 정확히 재고만큼만 발급"**을 보장하기 위한 단계별 기록.
모든 방식은 같은 테스트(`CouponIssueConcurrencyTest`, `CouponIssueStrategyBenchmarkTest`)로 검증·측정한다.

## 요약

> 5단계(Kafka 비동기)는 [5장 한눈에 보기](#5단계-한눈에-보기)와 5~7장 참고. 아래 소요 시간 표는 4단계까지의 동기 방식 비교다.
> 비동기 요약 (재고 1000 / 1000명): 응답 p99 178 → 27ms, 모든 응답 2.5 → 0.6s로 빨라졌지만, DB 반영 완료는 2.5 → 12.0s로 느려졌다 (7장).

**정확성** (재고 100)

| 단계 | 방식 | 1,000명 | 10,000명 | 1,000명 × 3회 (중복 요청) |
|---|---|---|---|---|
| 3-1 | 순진한 구현 (조회 → 자바 확인 → 메모리 +1) | ❌ **907건 발급** (9배 초과) | – | – |
| 3-2 | **조건부 UPDATE** (DB 방식 중 채택) | ✅ 100건 | ✅ 100건 | ✅ 100명 / 중복 0 |
| 3-3 | 비관적 락 (`SELECT ... FOR UPDATE`) | ✅ 100건 | ✅ 100건 | ✅ 100명 / 중복 0 |
| 3-3 | 낙관적 락 (`@Version` + 재시도) | ✅ 100건 | ✅ 100건 | ✅ 100명 / 중복 0 |
| 4 | **Redis Lua** (재고 판정을 Redis로) | ✅ 100건 | ✅ 100건 | ✅ 100명 / 중복 0 |
| 5 | **Redis Lua + Kafka 비동기** (기본, DB 저장은 컨슈머) | ✅ 100건 | ✅ 100건 | – (미측정. 중복 판정은 4단계와 같은 Lua) |

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

## 5. Kafka 비동기 처리 — 기본 파이프라인

### 5단계 한눈에 보기

**목표**: 성공 요청의 동기 DB 쓰기(4단계의 남은 병목)를 요청 경로에서 떼어 낸다. API는 판정만 하고 DB 저장은 컨슈머가 맡는다.
기본 모드는 `async`이고, `COUPON_ISSUE_MODE=sync`이면 4단계 동기 방식으로 돌아간다.

**토픽 구조**

| 토픽 | 파티션 | 키 | 값 | 컨슈머 |
|---|---|---|---|---|
| `coupon-issue-requests` | 3 (설정 가능, 늘리기만) | **eventId(쿠폰 ID)** | `{requestId, eventId, userId, requestedAt}` JSON | `coupon-issue-consumer`, 스레드 3 |
| `coupon-issue-requests.DLT` | 메인과 같은 수 | 원본 그대로 | 원본 + 예외 헤더 | `coupon-issue-dlt-consumer`, 2초 간격 무한 재시도 |

Producer: `acks=all`, `enable.idempotence=true`. 컨슈머: `ack-mode=record`, `enable-auto-commit=false`.

**흐름**

```
POST /issue → Lua 판정 ─ 실패 → 409/400/503 (Kafka 미발행)
              └ SUCCESS → PENDING(Redis) → Kafka 발행 + ack 대기 → 202 + Location
                                            └ 실패/타임아웃 → 멱등 보상 + 503

컨슈머 → 멱등 확인 → 취소 확인 → [INSERT + 카운터 +1 + COMPLETED] → 커밋
         ├ 영구 실패 → FAILED 먼저 → 멱등 보상 → 커밋
         └ 일시 실패 → 지수 백오프 3회 → DLT → DLT 컨슈머: FAILED + 멱등 보상
```

**설계 결정**

| 결정 | 이유 | 상세 |
|---|---|---|
| 키 = 쿠폰 ID | 같은 쿠폰을 한 스레드가 순서대로 처리 → `coupons` 행 락 경합 0. 대가: 이벤트 하나는 병렬화 안 됨 | 5장 설계 이유, 7-2 |
| 202 Accepted | 응답 시점엔 판정만 끝, DB 저장은 처리 중 → 201(리소스 생성)이 아니다. `Location`으로 결과 조회 주소 | 5장 설계 이유 |
| ack 받은 뒤 202 | 202는 "요청을 잃어버리지 않는다"는 약속. 발행 미확정이면 보상 + 503 | 5장, 6-2 |
| PENDING은 Redis, 결과는 DB | 요청 경로에서 DB 쓰기 제거. PENDING은 발행 전에 기록 | 5장 설계 이유 |
| 멱등 2단계 | requestId 사전 확인 + 유니크 위반 시 "같은 요청이면 정상" | 5장 컨슈머 신뢰성 |
| 지수 백오프 → DLT | 일시 오류는 재시도, 그래도 실패하면 Kafka(살아 있음)로 넘기고 DB가 돌아오면 DLT 컨슈머가 FAILED + 보상 | 5장 컨슈머 신뢰성 |
| 커밋은 DB 성공 후 | 리스너 정상 반환 후에만 커밋, 실패면 되감기, DLT 발행 성공해야 넘김 | 5장 컨슈머 신뢰성 |
| FAILED 먼저, 보상 나중 | 반대 순서면 재처리 시 되돌린 재고로 발급 → 초과 발급 위험 | 5장 설계 이유 |
| 보상 멱등 (restored Set) | 발행 실패·컨슈머·DLT가 같은 요청을 겹쳐 보상해도 한 번만 | 6-1 |
| 취소된 발행 판별 | ack 타임아웃 후 보상한 요청의 메시지가 실제로 도착하면 발급하지 않음(PUBLISH_CANCELLED) | 6-2 |
| 대조 점검 | 종료된 이벤트의 Redis ↔ DB 불변식 위반을 WARN 로그 (수정은 사람이) | 6-3 |

**한계**: Lua 성공 직후 발행 전에 서버가 죽으면 유령 발급이 생긴다(감지만 가능). 타임아웃과 컨슈머 처리 순서가 엇갈리면 응답과 결과가 다를 수 있다(감지만 가능).
이벤트 하나의 DB 반영은 파티션당 약 50건/s다. 초과 발급은 어떤 경우에도 DB 제약이 막는다 → [6-4](#6-4-남아-있는-한계-솔직한-기록)

**측정** (재고 1000 / 1000명, 워밍업 후) → [7장](#7-동기-redis-vs-kafka-비동기--응답-시간과-완료-시간-파티션컨커런시)

| | 동기 Redis | Kafka 비동기 |
|---|---:|---:|
| 응답 p50 / p99 | 72 / 178 ms | **19 / 27 ms** |
| 모든 응답 반환 | 2,508 ms | **620 ms** |
| DB 반영 완료 | **2,508 ms** | 12,023 ms |

이벤트 6개 동시: 파티션/스레드 3/1 26.3s → 3/3 14.3s → 6/6 **7.3s**. 이벤트 1개는 설정과 무관하게 14~20s.

---

### 구조

```
POST /api/coupons/{id}/issue  (mode=async, 기본)
  ├─ Lua 판정 (4단계와 동일)  ── 실패 → 409/400/503 바로 응답 (Kafka에 안 보냄)
  └─ SUCCESS
       ├─ Redis PENDING 기록   issue-request:{requestId} (TTL 1일)
       ├─ Kafka 발행           topic=coupon-issue-requests, key=eventId(쿠폰 ID), acks=all, idempotence
       │    └─ 브로커 ack 실패/5초 타임아웃 → Redis 보상(INCR+SREM) + PENDING 삭제 → 503 COUPON_ISSUE_UNAVAILABLE
       └─ 202 Accepted { requestId, status: PENDING }, Location: /api/issue-requests/{requestId}

컨슈머 (group=coupon-issue-consumer, 파티션 3 = 스레드 3, ack-mode=record)
  ├─ 멱등: issue_requests 에 결과가 있으면 건너뜀
  ├─ 한 트랜잭션: coupon_issues INSERT → issued_quantity +1(요청 시각 기준) → issue_requests COMPLETED
  ├─ 영구 실패(회원 없음 / DB 중복 / 재고 불일치): issue_requests FAILED 먼저 기록 → 그다음 Redis 보상
  └─ 일시 실패(DB 연결 등): 지수 백오프 재시도(0.5s → 1s → 2s) → 소진되면 DLT → DLT 컨슈머가 FAILED + 보상 (5장 "컨슈머 신뢰성")

GET /api/issue-requests/{requestId}: DB(COMPLETED/FAILED) → 없으면 Redis(PENDING) → 없으면 404 (본인 것만)
```

| 구성 | 위치 |
|---|---|
| 요청 경로 | `coupon/issue/async/AsyncCouponIssueRequester` |
| 컨슈머 / 처리 | `CouponIssueEventConsumer` / `CouponIssueEventProcessor` |
| 토픽·에러 핸들러 | `CouponIssueKafkaConfig` (파티션 3, 지수 백오프 재시도, DLT `coupon-issue-requests.DLT`) |
| 이벤트 | `CouponIssueRequestedEvent(requestId, eventId, userId, requestedAt)` — JSON 문자열 |
| 발급 요청 상태 | `coupon/request/` — `IssueRequest`(DB, V4), `PendingIssueRequestStore`(Redis), 조회 API |
| 모드 전환 | `app.coupon.issue.mode` = `async`(기본) / `sync`(4단계까지의 동기 방식, `strategy` 사용) |

### 설계 이유

**메시지 키를 eventId(쿠폰 ID)로 잡은 이유**
- Kafka는 **같은 키를 항상 같은 파티션**에 넣고, 한 파티션은 컨슈머 그룹 안에서 **한 스레드만** 읽는다.
  그래서 같은 쿠폰의 발급 요청은 **한 스레드가 순서대로** DB에 쓴다.
- DB의 병목은 `coupons` **한 행**(issued_quantity)이었다. 같은 쿠폰의 쓰기를 한 스레드로 모으면 **그 행을 두고 경쟁하는 트랜잭션이 없어진다**(락 대기 0).
  다음 단계에서 여러 건을 묶어 `issued_quantity + N`으로 한 번에 갱신하는 배치 처리도 자연스럽게 가능하다.
- 쿠폰별 처리 순서가 판정 순서와 같아서, 로그나 장애 분석이 쉽다.
- **대가**: 큰 이벤트 하나는 한 파티션, 한 스레드에 몰린다(hot partition). 파티션을 늘려도 **한 쿠폰의 처리량은 늘지 않는다**.
  여러 이벤트가 동시에 열리면 그때 파티션 수만큼 병렬로 처리된다.
- **userId를 키로 잡으면?** 부하는 고르게 퍼지지만, 같은 쿠폰의 쓰기가 3개 스레드로 흩어져서 `coupons` 행 락 경합이 그대로 돌아온다. 쿠폰 단위 순서도 사라진다.
- 키를 안 주면(라운드 로빈) userId 키와 같은 문제가 생기고, 같은 사용자의 중복 메시지도 다른 파티션으로 흩어진다.

**202 Accepted로 바꾼 이유**
- 응답 시점에는 **선착순 판정만 끝났고 DB 저장은 아직**이다. 201 Created는 "리소스가 만들어졌다"는 뜻이라 맞지 않는다.
- 202는 HTTP 표준에서 "요청을 받았고 처리는 나중에 끝난다"는 뜻이고, 결과를 확인할 주소를 `Location`으로 알려 준다.
  클라이언트는 `GET /api/issue-requests/{requestId}`로 PENDING → COMPLETED / FAILED를 확인한다.
- 사용자 입장에서는 "당첨 확정"으로 보여 줘도 된다. Redis 판정이 끝났으니 FAILED는 회원 탈퇴 같은 예외 상황뿐이다.
- 202를 주기 전에 **브로커 ack(acks=all)를 받는다**. 202는 "이 요청을 잃어버리지 않는다"는 약속이라,
  발행이 확정되지 않았으면 Redis를 되돌리고 503으로 실패시킨다.

**Producer: acks=all + idempotence**
- `acks=all`: 리더뿐 아니라 모든 in-sync replica가 기록해야 성공이다. 브로커 하나가 죽어도 발급 이벤트가 사라지지 않는다.
  (로컬은 브로커 1대라 의미가 작다. 운영에서는 replication 3 + `min.insync.replicas=2`가 함께 필요하다)
- `enable.idempotence=true`: 네트워크 오류로 Producer가 재전송해도 브로커가 PID와 시퀀스 번호로 **중복 기록을 막는다**.
  `max.in.flight.requests.per.connection ≤ 5`이면 순서도 지켜진다.
- 이것은 "Producer → 브로커" 구간의 중복만 막는다. **컨슈머가 같은 메시지를 다시 읽는 것**(처리 후 커밋 전에 죽은 경우)은 막지 못한다
  → 컨슈머 쪽 멱등 처리(requestId)가 따로 필요하다.

**PENDING은 Redis, 최종 결과는 DB**
- 요청 경로에서 DB 쓰기를 없애는 것이 이 단계의 목적이다. PENDING을 DB에 쓰면 요청마다 INSERT가 다시 생긴다.
- PENDING은 Kafka 발행 **전에** 기록한다. 컨슈머가 아주 빨리 끝나도 "COMPLETED 후에 PENDING이 생기는" 역전이 없다.
- 조회는 DB를 먼저 본다. 컨슈머가 PENDING 키를 지우지 못해도 결과가 틀리지 않는다(키는 TTL로 정리).
- PENDING 키는 다음 단계에서 **"처리되지 못한 요청" 대조**(4장 선택지 B)에 그대로 쓸 수 있다.

**컨슈머 실패 처리 순서: FAILED 먼저, 보상은 나중**
- 보상(Redis 재고 복구)을 먼저 하고 FAILED 기록이 실패하면, 메시지가 재처리되면서 **이미 되돌린 재고로 발급**되어 초과 발급 위험이 생긴다.
- FAILED를 먼저 확정하면, 보상이 실패해도 재처리 시 멱등 검사에서 건너뛴다 → 덜 발급되는 방향(안전).

**DB 기간 검사는 요청 시각 기준**
- 컨슈머가 밀려서 이벤트 종료 후에 처리해도, Redis가 기간 안에 판정한 요청은 저장되어야 한다.
  `increaseIssuedQuantity(couponId, event.requestedAt())`로 판정 시각을 넘긴다.

**JSON 문자열로 직렬화한 이유**
- Spring Kafka의 `JsonSerializer` 대신 Boot 기본 `StringSerializer` + `ObjectMapper`를 썼다.
  ProducerFactory를 새로 정의하지 않아도 되고, 기존 String 기반 KafkaTemplate(인프라 테스트 등)과 충돌하지 않는다.
  타입 헤더에 클래스명이 실리지 않아, 컨슈머 쪽 패키지가 달라져도 깨지지 않는다.
- 형식이 깨진 메시지(poison pill)는 다시 읽어도 깨져 있으므로, 재시도하지 않고 로그를 남긴 뒤 건너뛴다.

### 정확성 검증

| 테스트 | 확인한 것 |
|---|---|
| `AsyncCouponIssueFlowTest` | 토픽 파티션 3, 기본 모드 ASYNC, 접수 → PENDING → COMPLETED, 처리 후 PENDING 키 삭제 |
| | Redis 판정 실패 시 Kafka 미발행, **같은 메시지 2번 → 1건만 저장(멱등)** |
| | 회원 FK 위반 → FAILED + Redis 재고 복구 + Set 제거 |
| | DB 중복(Set 유실) → FAILED `COUPON_ALREADY_ISSUED`, 재고만 복구 |
| | 이벤트 종료 후 처리해도 요청 시각 기준으로 저장, 다른 사람의 requestId 또는 없는 requestId는 404 |
| `AsyncCouponIssueRequesterTest` (mock) | 키 = 쿠폰 ID, **Kafka 발행 실패/예외 → Redis 보상 + PENDING 삭제 + 503**, 판정 실패 시 미발행 |
| `AsyncCouponIssueConcurrencyTest` | 아래 표 |
| `CouponApiTest` | 202 + Location + PENDING → COMPLETED → 내 쿠폰 목록에 표시 |
| `FlywayMigrationTest` | `ck_issue_requests_status` = `IssueRequestStatus` enum |

**비동기 동시성** (32스레드, 단일 측정, 워밍업 없음)

| 시나리오 | 접수 응답 | 접수 완료 (모든 202 반환) | DB 반영 완료 | 정합성 |
|---|---|---:|---:|---|
| 재고 100 / 1000명 | 202 100 / 소진 900 | 2,816 ms | 8,050 ms | DB 100 / 카운터 100 / Redis 0·Set 100 / 중복 0 / FAILED 0 |
| 재고 1000 / 1000명 | 202 1000 | 3,377 ms | 29,969 ms | DB 1000 / 카운터 1000 / Redis 0·Set 1000 / 중복 0 / FAILED 0 |

### 측정 결과의 해석 — 기본 파이프라인의 한계 (솔직한 기록)

- **정확성은 완벽하지만, 처리량 이점은 아직 없다.**
  - 접수 시간은 동기 Redis(4장: 재고 100은 684ms, 1000/1000은 3,234ms)보다 **빠르지 않다**.
    성공 요청마다 Redis 왕복 2번(PENDING `HSET` + `EXPIRE`)과 Kafka ack 대기가 추가됐기 때문이다.
    첫 시나리오는 워밍업 없이 측정해서 Kafka Producer 초기화(메타데이터 조회 등)가 섞였을 수 있다(따로 확인하지 않음).
  - DB 반영은 1건당 약 27ms로 느리다. 같은 쿠폰의 메시지가 한 스레드에서 **한 건씩** 처리되고,
    건마다 DB 쿼리 4번(멱등 확인, INSERT, UPDATE, 결과 INSERT), Redis 1번, **오프셋 커밋 1번**(`ack-mode=record`)이
    각각 Docker Desktop 네트워크 왕복을 탄다.
- **지금 얻은 것**: API 응답이 DB 상태와 분리됐다. DB가 느리거나 잠시 멈춰도 접수는 계속되고, DB 쓰기는 컨슈머가 자기 속도로 소화한다.
  DB에 오는 동시 쓰기도 "파티션 수(3)" 이하로 제한된다(커넥션 풀 고갈 방지).
- **다음 개선 (제안)**
  1. **배치 컨슈머**: 메시지를 묶어서 받고, 묶음 INSERT + 쿠폰별 `issued_quantity + N` 한 번 + 오프셋 커밋 한 번.
     라운드트립이 "건당"에서 "묶음당"으로 줄어 가장 큰 효과가 예상된다.
  2. **PENDING 기록을 한 번의 왕복으로**: `HSET`과 `EXPIRE`를 파이프라인이나 Lua 하나로 합친다.
  3. **Producer 배치**: `linger.ms`를 몇 ms 주면 동시 요청들의 발행이 묶여서 ack 대기 횟수가 줄어든다(응답 지연과 trade-off).
  4. ~~**DLT + PENDING 대조**~~ → DLT는 구현 완료(아래 "컨슈머 신뢰성"). PENDING 대조는 남음.

### 남은 정합성 구간

| 구간 | 결과 | 현재 |
|---|---|---|
| Lua 성공 후 PENDING 기록 전 / Kafka 발행 전에 서버 종료 | 유령 발급 (4장 ①과 같음) | **감지만 가능** — 6장 대조 점검이 "Redis에만 있는 사용자"로 찾아냄. 자동 복구는 안 함 |
| Kafka 발행 타임아웃(실제로는 기록됐을 수도) 후 보상 | Redis는 되돌렸는데 메시지는 남아 있을 수 있음 | ✅ 대부분 해결 — 컨슈머가 복구 기록을 보고 PUBLISH_CANCELLED 처리 (6장). 컨슈머가 보상보다 먼저 처리하면 남음 |
| 컨슈머 재시도 소진 | DLT 발행 → FAILED + 보상 | ✅ DLT 컨슈머가 처리 (아래 "컨슈머 신뢰성") |
| 처리 후 오프셋 커밋 전에 컨슈머 종료 | 같은 메시지를 다시 받음 | ✅ requestId 멱등 처리로 해결 |
| 리밸런싱 등으로 같은 메시지가 동시에 처리됨 | 사전 확인을 함께 통과 | ✅ 유니크 위반을 "이미 처리됨"으로 판정해 정상 종료 |

### 컨슈머 신뢰성 — 멱등, 지수 백오프, DLT, 커밋 시점

```
메인 컨슈머 (coupon-issue-requests, ack-mode=record)
  처리 성공 ───────────────────────────────→ 리스너 정상 반환 → 오프셋 커밋
  중복 메시지 (사전 확인 or 유니크 위반) ──→ "이미 처리됨" → 정상 반환 → 커밋 (FAILED·보상 없음)
  영구 실패 (회원 없음 등) ───────────────→ FAILED 기록 + 보상 → 정상 반환 → 커밋
  일시 실패 (DB 장애 등) ─────────────────→ 예외 → 커밋 안 함, 같은 오프셋으로 되감기
        └ 0.5s → 1s → 2s 지수 백오프로 재시도 (같은 파티션의 다음 메시지도 기다림 = 쿠폰별 순서 유지)
        └ 모두 실패 → DLT 발행 (원본 + 예외 헤더) → 발행 성공해야 오프셋 커밋
  형식이 깨진 메시지 ─────────────────────→ 재시도 없이 바로 DLT

DLT 컨슈머 (coupon-issue-requests.DLT, 전용 컨테이너, 2초 간격 무한 재시도)
  결과가 이미 있음(마지막 재시도가 실제로는 커밋됨) → 건너뜀
  없음 → FAILED(RETRY_EXHAUSTED) 기록 → Redis 보상(INCR + SREM) → PENDING 삭제
  DB가 아직 죽어 있음 → 예외 → 2초 뒤 다시 (DB가 돌아오면 처리됨)
```

**멱등 — 유니크 제약 위반을 정상 케이스로 처리**
- 1차: `issue_requests`에 `requestId`가 이미 있으면 건너뛴다(Kafka 재전송, 커밋 전에 죽은 뒤의 재처리).
- 2차: 리밸런싱 직후처럼 같은 메시지를 두 스레드가 동시에 처리하면, 둘 다 1차 확인을 통과할 수 있다.
  나중에 커밋하는 쪽은 `coupon_issues (coupon_id, user_id)` 유니크 또는 `issue_requests` 기본키에서 막힌다.
  이때 **같은 requestId의 결과가 이미 있는지 다시 확인**해서, 있으면 "중복"으로 보고 정상 종료한다(FAILED로 만들거나 보상하지 않는다).
  결과가 없으면 진짜로 다른 요청이 먼저 발급한 경우라 `FAILED(COUPON_ALREADY_ISSUED)`다.
- `coupon_issues`와 `issue_requests(COMPLETED)`를 **한 트랜잭션**에 쓰기 때문에 "발급 행은 있는데 결과가 없는" 중간 상태가 없다. 그래서 위 판정이 정확하다.

**지수 백오프** (`app.kafka.consumer.retry`: 최대 3회, 0.5s 시작, ×2, 상한 5s)
- DB 순간 장애, 커넥션 고갈처럼 잠깐 기다리면 풀리는 오류를 위한 것이다. 고정 간격보다 장애 중인 DB에 주는 부담이 작다.
- `DefaultErrorHandler`는 메모리 안에서 되감아 재시도하므로 **재시도하는 동안 같은 파티션의 다음 메시지도 기다린다**.
  쿠폰별 처리 순서를 지키는 대신, 긴 장애에서는 그 파티션 전체가 멈춘다.
  (대안: `@RetryableTopic` 논블로킹 재시도 — 파티션은 막히지 않지만 순서가 깨진다. 이 프로젝트는 순서를 택했다)

**DLT와 FAILED 표시를 나눈 이유**
- 재시도가 모두 실패하는 주된 원인은 DB 장애다. 그 순간에는 DB에 FAILED도 쓸 수 없다.
- 그래서 메인 컨슈머는 **Kafka에 DLT로 넘기는 것까지만** 하고(Kafka는 살아 있으므로), 오프셋을 넘겨 다음 메시지를 처리한다.
- DLT 컨슈머가 DB가 돌아올 때까지 재시도하면서 **FAILED를 먼저 기록하고 보상**한다(5장의 "FAILED 먼저, 보상은 나중" 규칙과 같다).
- DLT 컨슈머는 결과가 이미 있으면 건너뛴다. 마지막 재시도가 "타임아웃이 났지만 실제로는 커밋된" 경우 COMPLETED를 FAILED로 덮지 않기 위해서다.
- DLT 메시지에는 원본과 예외 정보(`kafka_dlt-exception-fqcn` 등) 헤더가 남아서 원인 조사와 재처리 판단에 쓸 수 있다.

**오프셋 커밋 시점**
- `ack-mode=record`: 리스너가 **정상 반환한 뒤에만** 그 메시지의 오프셋을 커밋한다. 리스너는 DB 트랜잭션 커밋이 끝난 뒤에 반환한다 → **DB 저장 성공 후 커밋**.
- 예외면 커밋하지 않는다. 재시도가 소진돼도 **DLT 발행에 성공해야** 오프셋을 넘긴다. DLT 발행이 실패하면 같은 메시지를 다시 처리한다.
- `enable-auto-commit: false` — Kafka 클라이언트가 주기적으로 알아서 커밋(처리 전 커밋 가능)하지 않게 한다.

**검증** (`CouponIssueConsumerReliabilityTest`, 실제 Kafka / PostgreSQL / Redis 컨테이너)

DB 장애는 mock이 아니라 **실제로 일으켰다**. 테스트 중에 `issue_requests` 테이블 이름을 바꿔서 컨슈머의 모든 쿼리를 실패시키고, 되돌려서 복구한다.
오프셋은 Kafka AdminClient로 **컨슈머 그룹의 실제 커밋 오프셋**을 조회했다.

| 테스트 | 결과 |
|---|---|
| 같은 메시지를 Kafka로 두 번 발행 | 발급 1건, COMPLETED, Redis 재고 그대로. **사전 확인**에서 감지 |
| 같은 요청을 8개 스레드가 동시에 처리 | 발급 1건, 어느 스레드도 예외 없음, 중복 7건 모두 **`coupon_issues` 유니크 제약**에서 감지 → 정상 처리, 보상 없음 |
| 일시적 DB 장애 → 백오프 중 복구 | 실패 중에는 커밋 오프셋 ≤ 메시지 오프셋, 복구 후 COMPLETED, 그다음 커밋 = 오프셋 + 1, DLT 0건 |
| DB 장애 지속 → 재시도 소진 | 실패 시도 4번(최초 + 재시도 3번), DLT까지 3.5초 이상(백오프 합), DLT 메시지는 같은 파티션 + 예외 헤더, DLT 발행 후에만 커밋. DB가 죽어 있는 동안은 보상 안 함 → 복구되면 `FAILED(RETRY_EXHAUSTED)`, 재고 복구, Set 제거, PENDING 정리 |
| 형식이 깨진 메시지 | 실패 1번 후 재시도 없이 DLT, 원본 보존 |

> 측정하다 바로잡은 점: Spring Kafka의 재시도 리스너(`failedDelivery`)는 "재시도할 때"가 아니라 **실패할 때마다**(최초, 마지막 포함) 호출된다.
> 처음에는 이를 "재시도 횟수"로 세서 테스트가 실패했고, 지표 이름을 `failedDeliveries`로 바꿔 의미를 맞췄다(재시도 3회 = 실패 4회).

---

## 6. Redis ↔ DB 정합성 — 멱등 보상, 취소된 발행, 대조 점검

비동기 발급에서는 하나의 요청이 **Redis(판정), Kafka(전달), DB(기록)** 세 곳을 거친다. 셋을 하나의 트랜잭션으로 묶을 수 없어서,
어긋날 수 있는 지점마다 "되돌리기", "알아보기", "찾아내기"를 둔다.

### 6-1. 보상(복구)을 멱등하게 — 같은 요청은 한 번만 복구

같은 요청이 **여러 경로에서 보상될 수 있다**:
- 요청 경로: Kafka 발행 실패(또는 ack 타임아웃) → 보상 후 503
- 메인 컨슈머: 영구 실패(회원 없음 등) → FAILED 후 보상
- DLT 컨슈머: 재시도 소진 → FAILED 후 보상, DLT 메시지가 다시 전달되면 또 호출될 수 있음

기존 보상 Lua는 호출될 때마다 `INCR`을 실행했다. 그래서 ack 타임아웃으로 보상한 요청의 메시지가 실제로는 기록돼 있었고, 그 메시지가 DLT까지 가면 **재고가 두 번 풀렸다**.

```lua
-- coupon-issue-restore.lua (KEYS: stock, issued, restored / ARGV: userId, removeUser, restoreId)
if redis.call('SADD', KEYS[3], ARGV[3]) == 0 then return 0 end   -- 이미 복구한 requestId → 아무것도 안 함
(restored Set 에 재고 키와 같은 TTL)
if EXISTS stock then INCR stock end
if removeUser then SREM issued userId end
return 1
```

- `event:{id}:restored`(복구한 requestId Set)에 **먼저 기록**하고, 처음일 때만 `INCR`과 `SREM`을 실행한다. 기록과 복구가 한 스크립트라 원자적이다.
- 비동기 경로는 `restoreId = requestId`라서 경로가 달라도 같은 요청이면 한 번만 복구된다. 동기 경로(4장)는 그 자리에서 한 번만 보상하므로 매번 새 ID를 쓴다.
- `restored` Set은 **재적재(sync)해도 지우지 않는다**. 재적재 후에도 Kafka에 "취소된 요청" 메시지가 남아 있을 수 있기 때문이다(6-2).
- 반환값으로 "이번에 복구했는지 / 이미 복구됐는지"를 알 수 있어서 로그에 남긴다.

### 6-2. Redis 차감은 성공했는데 Kafka 발행이 실패한 경우

```
요청 경로: Lua SUCCESS → PENDING → send() ── 실패 / 5초 안에 ack 없음
   └→ 보상 Lua(restoreId = requestId): 재고 복구, Set에서 제거, restored에 requestId 기록 → PENDING 삭제 → 503
```

- **확실히 실패한 경우**(브로커 연결 실패, 직렬화 오류, `send()` 자체가 예외): 메시지가 없으니 보상으로 끝난다.
- **ack 타임아웃**은 "실패했는지 모른다"는 뜻이다. 브로커에는 이미 기록됐는데 응답만 늦었을 수 있다.
  이 메시지가 컨슈머에 도착하면 그대로 발급할 경우 두 가지 문제가 생긴다.
  - 사용자는 503을 받았는데 쿠폰이 생긴다
  - 보상으로 풀린 재고를 다른 사람이 받으면, 결국 재고가 한 개 더 나간 셈이 된다
- **해결**: 컨슈머가 처리하기 전에 `restored` Set을 확인한다. 이 requestId가 이미 복구(= 취소)된 요청이면 **발급하지 않고 `FAILED(PUBLISH_CANCELLED)`**로 기록한다.
  보상은 이미 했으므로 다시 하지 않는다.
- 결과: "503을 받은 요청은 발급되지 않는다"가 대부분의 경우에 지켜진다(예외는 6-4).

### 6-3. 대조 점검 — 이벤트 종료 후 Redis와 DB 비교

`CouponStockReconciler`가 쿠폰 하나를 대조한다. **고치지 않고, 불일치만 WARN 로그로 남긴다.**
자동 수정은 "어느 쪽이 맞는지" 판단이 필요해서 사람이 결정한다.

| 검사 | 정상이면 | 어긋나면 의심할 것 |
|---|---|---|
| Redis 남은 재고 + DB 발급 행 = 총 수량 | 같음 | 유령 발급(작음), 이중 복구(큼) |
| Redis 발급 Set 크기 = DB 발급 행 | 같음 | |
| Redis에만 있는 사용자 | 0명 | **유령 발급**: Lua 성공 후 발행 전에 서버 종료, 보상 실패 |
| DB에만 있는 사용자 | 0명 | Redis Set 유실, 컨슈머가 보상보다 먼저 처리한 발행 타임아웃(6-4) |
| `coupons.issued_quantity` = DB 발급 행 | 같음 | DB 내부 불일치 |

- **관리자 API**: `GET /api/admin/coupons/{id}/reconciliation` → 결과 JSON(문제 목록, 양쪽에만 있는 사용자 최대 20명). 언제든 실행할 수 있지만, 진행 중인 이벤트는 처리 중인 요청 때문에 잠깐 어긋날 수 있다(`eventEnded`로 표시).
- **스케줄러**: 10분마다(`app.coupon.reconcile.interval`) **종료 후 1분 ~ 7일** 지난 이벤트를 모두 대조한다.
  1분을 기다리는 건 컨슈머가 남은 메시지를 처리할 시간을 주기 위해서이고, 7일까지인 건 Redis 키가 종료 7일 후 만료되기 때문이다.
  `COUPON_RECONCILE_ENABLED=false`로 끌 수 있고, 테스트에서는 끈다.
- Redis 키가 없으면(적재 안 됨, 만료) 수량 비교를 건너뛰고 `redisLoaded=false`로 알린다.

### 6-4. 남아 있는 한계 (솔직한 기록)

| # | 상황 | 무엇이 어긋나나 | 지금 할 수 있는 것 | 근본 해결 방향 |
|---|---|---|---|---|
| 1 | **Lua 성공 직후, PENDING 기록이나 Kafka 발행 전에 서버가 죽음** (OOM, kill, 배포) | Redis: 재고 -1, Set에 사용자 있음 / DB·Kafka: 아무것도 없음 → **유령 발급**. 사용자는 응답을 못 받았고, 다시 신청하면 `DUPLICATE` | 대조 점검이 "Redis에만 있는 사용자"로 **찾아낸다**. 복구는 수동(해당 사용자 SREM + INCR, 또는 보상 쿠폰 지급) | Lua 안에서 Redis Stream에 `XADD`(차감과 기록을 원자적으로) → 이 구간 자체가 사라짐. 또는 PENDING을 Lua 안에서 기록하고, 오래된 PENDING을 워커가 정리 |
| 2 | PENDING 기록 후, 발행 전에 서버가 죽음 | 1과 같음 + PENDING이 TTL(1일) 동안 남음 | 1과 같음. PENDING 키로 "어떤 요청이었는지"까지 알 수 있다 | 오래된 PENDING 대조 워커 (DB에 결과가 없고 Kafka에도 없으면 보상) |
| 3 | 발행 ack 타임아웃인데 실제로는 기록됐고, **컨슈머가 요청 경로의 보상보다 먼저** 처리함 | 컨슈머: COMPLETED (restored 확인 시점에는 아직 기록 전) → 그 뒤 요청 경로가 보상(재고 +1, Set에서 제거) → 사용자는 503을 받았지만 쿠폰 있음, Redis 재고가 실제보다 1 많음 | 대조 점검이 "DB에만 있는 사용자", "남은 재고 + DB > 총 수량"으로 찾아낸다. **초과 발급은 DB 조건부 UPDATE가 최종 차단**한다(DB 기준으로 재고를 넘지 않음) | 요청 경로의 보상을 "DB에 결과가 없을 때만" 하도록 조건을 둔다(요청 경로에 DB 조회 1회 추가). 또는 타임아웃 시 503 대신 202를 주고 결과를 컨슈머에 맡긴다 |
| 4 | 보상 Lua 자체가 실패 (Redis 순간 장애) | 재고가 덜 풀린 채로 남음(**안전한 방향**) | 에러 로그 + 대조 점검("남은 재고 + DB < 총 수량") | 보상 작업을 재시도 큐에 넣기 |
| 5 | Redis 장애 / 데이터 유실 | 판정 불가 → 503 `STOCK_NOT_READY` | 트래픽을 막고 `stock/sync`로 DB 기준 재적재 | Redis 복제(Replica/Sentinel) + AOF 영속성 |
| 6 | 서버가 여러 대일 때 스케줄러 | 서버마다 같은 점검을 실행 (읽기 전용이라 해는 없음, 로그 중복) | — | ShedLock 등으로 한 대만 실행 |
| 7 | 대조 점검은 수만 명 규모를 가정 | `SMEMBERS` 한 번에 Set 전체를 읽음 | — | `SSCAN`으로 나눠 읽기, DB 쪽도 페이지 단위 |

> 정리하면: **초과 발급은 어떤 경우에도 DB 제약(조건부 UPDATE, 유니크, CHECK)이 마지막으로 막는다.**
> 남은 위험은 "덜 발급(유령 발급, 보상 실패)"과 "응답과 결과가 다름(3번)"이고, 지금은 **대조 점검으로 감지**하는 단계다.
> 1번(유령 발급)을 구조적으로 없애려면 차감과 기록을 원자적으로 묶어야 한다(Lua 안 `XADD` 또는 Redis 기반 outbox) — 다음 개선 후보.

### 6-5. 검증

| 테스트 | 확인한 것 |
|---|---|
| `RedisCouponStockTest` | 같은 restoreId로 3번 보상 → 재고는 한 번만 증가(반환 1, 0, 0), 다른 요청은 각각 반영, restored Set에 TTL, 재적재해도 restored 유지 |
| `AsyncCouponIssueFlowTest` | **발행 실패로 보상한 요청의 메시지가 실제로 도착** → 발급 안 함, `FAILED(PUBLISH_CANCELLED)`, 재고는 한 번만 복구 |
| | **발행 실패 보상 + DLT 처리 2번** → 재고 한 번만 복구, `FAILED(RETRY_EXHAUSTED)` |
| `AsyncCouponIssueRequesterTest` (mock) | 발행 실패/예외 → `restore(…, requestId)`로 보상 + PENDING 삭제 + 503 |
| `CouponStockReconcilerTest` | 정상 → 일치 / **유령 발급**(Lua만 성공) → "Redis에만 1명", 수량식 불일치 / DB에만 있는 발급 / DB 카운터 불일치 / Redis 미적재 / 정기 점검은 **종료된 이벤트만**, 불일치만 반환 / 관리자 API 200, 일반 사용자 403 |
| `CouponIssueConsumerReliabilityTest` | (5장) DLT → DB 복구 후 FAILED + 보상 — 멱등 보상으로 바뀐 뒤에도 통과 |

---

## 7. 동기 Redis vs Kafka 비동기 — 응답 시간과 완료 시간, 파티션/컨커런시

`AsyncIssueBenchmarkTest` (`./gradlew benchmark --tests "com.couponrush.coupon.AsyncIssueBenchmarkTest"`, 결과: `build/benchmark/coupon-issue-async.md`)

### 측정 조건

| 항목 | 값 |
|---|---|
| 환경 | 로컬 Windows + Docker Desktop, Testcontainers (Kafka 단일 브로커 `apache/kafka:3.9.1`, PostgreSQL 17, Redis 7.4) |
| 동시성 | 32 스레드, `CountDownLatch`로 동시 출발, HikariCP 풀 10 |
| 호출 | 동기: `RedisCouponIssuer.issue()` / 비동기: `AsyncCouponIssueRequester.request()` (HTTP·인증 제외) |
| **워밍업** | **있음** — 측정 전에 방식별로 재고 50 / 200명을 1번 실행해서 JIT, 커넥션 풀, Kafka Producer 메타데이터를 데움. 파티션/컨커런시를 바꾼 뒤에도 1번 (리밸런싱 직후 지연 제외) |
| 반복 | 시나리오별 **1회 측정** (이전 측정에서 회차 간 편차가 ±30% 안팎이었으므로 작은 차이는 해석하지 않는다) |
| Kafka | `acks=all`, idempotence, 키 = 쿠폰 ID, `ack-mode=record`, 메시지를 한 건씩 처리 (배치 아님) |
| 정합성 | 모든 실행에서 확인: DB 발급 = 재고, 카운터 = 발급 행, Redis 남은 재고 0, 예상 못한 예외 0 |

**측정한 두 가지 시간**
- **응답 시간** (사용자 체감): 요청 1건이 응답(동기 201 / 비동기 202)을 받기까지. p50 / p95 / p99 / 최대
- **전체 완료 시간**: 출발 신호부터 **모든 발급이 DB에 반영될 때까지**. 동기는 마지막 응답 = 완료. 비동기는 `issue_requests` COMPLETED 수가 재고와 같아질 때까지

### 7-1. 동기 Redis vs Kafka 비동기 (파티션 3 / 컨커런시 3)

| 시나리오 | 방식 | 응답 p50 | p95 | p99 | 최대 | 모든 응답 반환 | **전체 완료 (DB 반영)** |
|---|---|---:|---:|---:|---:|---:|---:|
| 재고 100 / 1,000명 | 동기 Redis (201) | 5 ms | 109 ms | 195 ms | 366 ms | 527 ms | **527 ms** |
| | Kafka 비동기 (202) | 4 ms | **26 ms** | **38 ms** | **44 ms** | **209 ms** | 1,961 ms |
| 재고 100 / 10,000명 | 동기 Redis (201) | 3 ms | 6 ms | 25 ms | 170 ms | 1,494 ms | **1,494 ms** |
| | Kafka 비동기 (202) | 4 ms | 8 ms | **18 ms** | **27 ms** | 1,494 ms | 2,292 ms |
| 재고 1,000 / 1,000명 | 동기 Redis (201) | 72 ms | 130 ms | 178 ms | 247 ms | 2,508 ms | **2,508 ms** |
| | Kafka 비동기 (202) | **19 ms** | **25 ms** | **27 ms** | **30 ms** | **620 ms** | 12,023 ms |

**해석**
- **사용자 응답은 비동기가 빠르고, 특히 꼬리 지연(p95~최대)이 크게 줄어든다.**
  - 재고 1000 / 1000명(전원 성공): p50 72 → 19ms(약 3.8배), p99 178 → 27ms(약 6.6배), 모든 응답 반환 2.5s → 0.6s(약 4배).
  - 동기 방식은 성공한 요청이 DB INSERT와 쿠폰 행 UPDATE를 기다리고, 그 행 락에 줄을 서서 꼬리가 길어진다. 비동기는 Redis Lua + Kafka ack만 기다린다.
  - 재고 100인 시나리오는 대부분이 소진 응답(Lua 한 번)이라 p50은 비슷하다. 차이는 성공 요청이 몰리는 p95 이상에서 난다.
- **전체 완료는 비동기가 느리다** (1000/1000에서 2.5s → 12.0s, 약 4.8배).
  컨슈머가 메시지를 **한 건씩** 처리하고(건마다 DB 4회 + Redis 1회 + 오프셋 커밋 1회), 이벤트 하나의 메시지는 **한 파티션 = 한 스레드**로 모이기 때문이다.
  동기 방식은 성공 요청 1000건을 32개 스레드가 나눠 쓴다(쿠폰 행 락에서 결국 줄은 서지만, 그 밖의 작업은 병렬로 진행된다).
- **정리**: 비동기는 "**사용자 응답을 DB에서 떼어 내는**" 데는 효과가 크지만, 지금 구조로는 "**DB 반영 처리량**"이 오히려 낮다.
  선착순 이벤트에서는 응답 속도와 꼬리 지연이 사용자 경험과 서버 안정성(스레드·커넥션 점유)에 직결되므로 비동기가 맞는 방향이고,
  DB 반영 속도는 아래 7-3의 배치 컨슈머로 따라잡아야 한다.

### 7-2. 컨슈머 파티션 / 컨커런시

전원 성공(모든 요청이 DB 쓰기) 상황에서 DB 반영 처리량을 비교했다. 파티션은 늘리기만 가능해서 3/1 → 3/3 → 6/6 순서로 측정했다.
분포는 Kafka 기본 파티셔너(`murmur2(키) % 파티션 수`)로 계산한 파티션별 메시지 수다.

| 파티션 | 컨커런시 | 시나리오 | 전체 완료 | 처리량 | 파티션 분포 |
|---:|---:|---|---:|---:|---|
| 3 | 1 | 이벤트 1개 (재고 1000 / 1000명) | 20,011 ms | 50건/s | 파티션 2에 전부 |
| 3 | 3 | 이벤트 1개 | 14,602 ms | 68건/s | 파티션 0에 전부 |
| 6 | 6 | 이벤트 1개 | 16,176 ms | 62건/s | 파티션 1에 전부 |
| 3 | 1 | **이벤트 6개 동시** (각 재고 200 / 200명, 총 1,200건) | 26,345 ms | 46건/s | {0=600, 1=400, 2=200} |
| 3 | 3 | 이벤트 6개 동시 | 14,278 ms | 84건/s | {0=400, 1=600, 2=200} |
| 6 | 6 | 이벤트 6개 동시 | **7,328 ms** | **164건/s** | {0=400, 1=200, 3=400, 4=200} |

**해석**
- **이벤트 1개: 파티션과 컨커런시를 늘려도 빨라지지 않는다** (14~20초, 파티션 1개당 초당 50~70건).
  키가 쿠폰 ID라서 1,000건이 모두 한 파티션, 한 스레드로 간다. 이 구간의 차이(3/1이 조금 느림)는 1회 측정 편차 범위 안이라 원인을 단정하지 않는다.
- **이벤트 여러 개: 늘린 만큼 빨라진다.** 3/1 → 3/3에서 약 1.8배, → 6/6에서 약 3.6배.
  단, **완료 시간은 "메시지가 가장 많이 몰린 파티션"이 결정한다**.
  - 3/3: 파티션 1에 600건(이벤트 3개)이 몰려 그 파티션이 끝날 때까지 기다렸다.
  - 6/6: 해시 충돌로 **파티션 2개(2, 5)는 비어 있었고**, 최대 400건짜리 파티션이 끝을 결정했다.
  - 파티션당 처리 속도는 어느 설정에서나 초당 약 40~55건으로 비슷하다(건당 약 20ms). 결국 "건당 처리 비용"이 병목이다.
- **컨커런시 < 파티션**(3/1)이면 스레드 하나가 여러 파티션을 번갈아 읽어서 사실상 직렬이 된다. **컨커런시 > 파티션**은 남는 스레드가 놀 뿐이다(측정하지 않음).
- **파티션 수를 정하는 기준**: "동시에 열리는 이벤트 수"보다 **넉넉하게** 잡는다. 같으면 해시 충돌로 일부 파티션에 몰린다.
  서버를 여러 대 띄우면 컨슈머 그룹이 파티션을 나눠 갖는다(전체 스레드 수 ≤ 파티션 수).

### 7-3. 다음 개선 — 배치 컨슈머 (제안)

측정 결과, 비동기 방식의 남은 병목은 **이벤트 하나 안에서의 "건당 처리 비용"**이다(파티션 하나에서 초당 약 50건).
파티션을 늘리는 방법으로는 이벤트 하나를 빠르게 만들 수 없다(키 = 쿠폰 ID를 유지하는 한).

- **배치 리스너**: 한 번에 수백 건을 받아 `coupon_issues` 묶음 INSERT, 쿠폰별 `issued_quantity + N` 한 번, `issue_requests` 묶음 INSERT, 오프셋 커밋 한 번
  → 건당 왕복이 묶음당 왕복으로 바뀐다. 멱등 처리는 `ON CONFLICT DO NOTHING`과 requestId 조회로 묶음 단위로 한다.
- 이 방식은 키 = 쿠폰 ID 설계와 잘 맞는다. 한 쿠폰의 메시지가 한 스레드에 모여 있어서, 묶음 하나에 같은 쿠폰이 많이 들어간다(`+N` 한 번으로 끝남).
- 기대치: 파티션 하나의 처리량이 지금의 초당 50건에서 수백~수천 건으로 오를 것으로 예상한다(측정해서 확인해야 함).

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
