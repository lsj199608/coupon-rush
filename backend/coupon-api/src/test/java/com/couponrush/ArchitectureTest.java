package com.couponrush;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 패키지 의존 방향 규칙. 사람이 기억해서 지키던 설계 규칙을 테스트로 고정한다 (어기면 빌드 실패).
 * <p>
 * 아직 검사하지 않는 것: 패키지 순환 전체. coupon ↔ coupon.issue/stock/search/dto, user ↔ auth.jwt 순환이 남아 있다
 * (CLAUDE.md "Redis 발급" 패키지 메모 — 도메인을 coupon/domain 으로 내리면 없앨 수 있음). 정리하면 slices().should().beFreeOfCycles() 를 추가한다.
 * <p>
 * 모든 규칙은 대상 클래스가 하나도 없으면 실패한다 (ArchUnit 기본값) — 패키지 이름이 바뀌면 규칙이 조용히 무력해지지 않는다.
 */
@AnalyzeClasses(packages = "com.couponrush", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** CLAUDE.md 규칙 "쿠폰 발급 핵심 경로에는 LLM 호출을 절대 넣지 않는다"의 구조적 보장. coupon 은 LLM·검색·챗봇 기능의 존재를 모른다 */
    @ArchTest
    static final ArchRule 쿠폰_패키지는_LLM_과_자연어_검색과_챗봇에_의존하지_않는다 = noClasses()
            .that().resideInAPackage("com.couponrush.coupon..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.couponrush.llm..", "com.couponrush.search..", "com.couponrush.chatbot..")
            .because("발급 경로에 LLM 을 넣지 않는다. 검색 조건도 coupon 패키지 표현(CouponSearchCondition)으로 받는다");

    /** 2026-11-16 정리의 요점: 재고는 발급 전용이 아니다. 형제 기능이 발급 구현 내부에 의존하지 않는다 */
    @ArchTest
    static final ArchRule 재고와_대조_점검은_발급_구현에_의존하지_않는다 = noClasses()
            .that().resideInAnyPackage("com.couponrush.coupon.stock..", "com.couponrush.coupon.reconcile..")
            .should().dependOnClassesThat().resideInAPackage("com.couponrush.coupon.issue..")
            .because("재고 저장소는 목록 조회·대조 점검·발급이 함께 쓰는 아래 층이다");

    /** 의존 방향: search·chatbot → llm, search → coupon. llm 은 요청·결과를 다루는 기반이라 기능(검색·챗봇·쿠폰)을 모른다 */
    @ArchTest
    static final ArchRule LLM_요청_패키지는_검색과_챗봇과_쿠폰을_모른다 = noClasses()
            .that().resideInAPackage("com.couponrush.llm..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.couponrush.search..", "com.couponrush.chatbot..", "com.couponrush.coupon..");

    /** 메시지 계약(llm-contract)은 LLM 을 쓰는 패키지만 안다 — 회원·인증·쿠폰에 퍼지지 않게 */
    @ArchTest
    static final ArchRule LLM_계약은_llm_search_chatbot_만_쓴다 = noClasses()
            .that().resideOutsideOfPackages("com.couponrush.llm..", "com.couponrush.search..", "com.couponrush.chatbot..")
            .should().dependOnClassesThat().resideInAPackage("com.couponrush.llm.contract..");

    /** 에러 공통 계층은 가장 아래 — 기능 패키지를 참조하면 모든 기능이 서로 엮인다 */
    @ArchTest
    static final ArchRule 에러_공통_계층은_기능_패키지에_의존하지_않는다 = noClasses()
            .that().resideInAPackage("com.couponrush.global.error..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.couponrush.auth..", "com.couponrush.user..", "com.couponrush.coupon..",
                    "com.couponrush.llm..", "com.couponrush.search..", "com.couponrush.chatbot..",
                    "com.couponrush.webchat..");

    /** 챗봇은 검색 패키지를 직접 쓰지 않는다 — 챗봇의 검색은 C2 에서 mcp-server 도구(search_events)를 거친다 */
    @ArchTest
    static final ArchRule 챗봇은_검색_패키지를_직접_쓰지_않는다 = noClasses()
            .that().resideInAPackage("com.couponrush.chatbot..")
            .should().dependOnClassesThat().resideInAPackage("com.couponrush.search..");

    /** 채팅방 티켓(D2)은 회원 정보만 쓴다 — 쿠폰·LLM·검색·챗봇과 엮이지 않게 (채팅은 발급과 무관한 기능) */
    @ArchTest
    static final ArchRule 채팅_티켓은_쿠폰_LLM_검색_챗봇에_의존하지_않는다 = noClasses()
            .that().resideInAPackage("com.couponrush.webchat..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.couponrush.coupon..", "com.couponrush.llm..", "com.couponrush.search..", "com.couponrush.chatbot..");

    /** 채팅 티켓 계약(web-chat-contract)은 webchat 패키지만 안다 */
    @ArchTest
    static final ArchRule 채팅_티켓_계약은_webchat_만_쓴다 = noClasses()
            .that().resideOutsideOfPackage("com.couponrush.webchat..")
            .should().dependOnClassesThat().resideInAPackage("com.couponrush.webchat.contract..");
}
