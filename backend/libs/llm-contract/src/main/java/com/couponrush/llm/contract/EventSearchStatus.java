package com.couponrush.llm.contract;

/**
 * 검색 필터의 이벤트 진행 상태. coupon-api 의 CouponStatus 와 값 이름이 같아야 한다 (coupon-api CouponTest 가 검사).
 * <p>
 * 계약 모듈은 coupon-api 에 의존하지 않으므로 같은 목록을 따로 둔다 (모듈 간 공유는 메시지 계약만).
 */
public enum EventSearchStatus {
    /** 오픈 전 */
    UPCOMING,
    /** 진행 중 */
    OPEN,
    /** 종료 */
    ENDED
}
