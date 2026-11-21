package com.couponrush.mcpserver.event;

import com.couponrush.mcpserver.auth.Caller;
import com.couponrush.mcpserver.config.McpProperties;
import com.couponrush.mcpserver.couponapi.CouponApiClient;
import com.couponrush.mcpserver.couponapi.CouponApiDtos.CouponEvent;
import com.couponrush.mcpserver.couponapi.CouponApiDtos.MyCoupon;
import com.couponrush.mcpserver.couponapi.CouponApiDtos.SearchResult;
import com.couponrush.mcpserver.event.EventViews.EventView;
import com.couponrush.mcpserver.event.EventViews.MyCouponView;
import com.couponrush.mcpserver.event.EventViews.MyCouponsView;
import com.couponrush.mcpserver.event.EventViews.SearchView;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 이벤트 조회 — coupon-api 를 부르고 결과를 도구 결과 모양({@link EventViews})으로 바꾼다.
 * MCP 를 모른다 (도구 정의·인자 검사·isError 결과는 {@link com.couponrush.mcpserver.tool.EventTools} 의 일).
 * 조회만 한다 — 발급 기능은 없고, 결과마다 이벤트 페이지 url 을 넣어 발급은 사람이 화면에서 한다.
 * <p>
 * coupon-api 실패는 {@link com.couponrush.mcpserver.couponapi.CouponApiException} 그대로 올린다 (도구가 설명 문장으로 바꿈).
 */
@Service
public class EventQueryService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter KST_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(KST);
    private static final Map<String, String> STATUS_LABELS = Map.of("UPCOMING", "오픈 전", "OPEN", "진행 중", "ENDED", "종료");

    private final CouponApiClient couponApi;
    private final String frontendBaseUrl;

    public EventQueryService(CouponApiClient couponApi, McpProperties properties) {
        this.couponApi = couponApi;
        this.frontendBaseUrl = properties.frontendBaseUrl();
    }

    /**
     * @param query 공백을 정리한 질문 (도구가 길이·빈 값을 이미 검사)
     */
    public SearchView search(String query, Caller caller) {
        SearchResult result = couponApi.searchEvents(query, caller);
        boolean ai = "COMPLETED".equals(result.status());
        List<EventView> events = nullToEmpty(result.events()).stream().map(this::view).toList();
        return new SearchView(
                result.query(),
                ai ? "AI_FILTER" : "KEYWORD",
                ai ? result.filter() : null,
                ai ? List.of() : nullToEmpty(result.keywords()),
                ai ? null : result.fallbackReason(),
                events.size(),
                events);
    }

    public EventView getEvent(long eventId) {
        return view(couponApi.getEvent(eventId));
    }

    /** 누구의 쿠폰인지는 연결 인증(Caller)으로만 정해진다 — 인자로 회원을 받지 않는다. */
    public MyCouponsView getMyCoupons(Caller caller) {
        List<MyCouponView> coupons = couponApi.getMyCoupons(caller).stream().map(this::view).toList();
        return new MyCouponsView(coupons.size(), coupons);
    }

    private EventView view(CouponEvent event) {
        return new EventView(event.id(), event.name(), event.description(), event.status(),
                STATUS_LABELS.getOrDefault(event.status(), event.status()),
                event.remainingQuantity(), event.totalQuantity(), kst(event.issueStartAt()), kst(event.issueEndAt()),
                eventUrl(event.id()));
    }

    private MyCouponView view(MyCoupon coupon) {
        return new MyCouponView(coupon.issueId(), coupon.couponId(), coupon.couponName(), kst(coupon.issuedAt()),
                eventUrl(coupon.couponId()));
    }

    private String eventUrl(long eventId) {
        return frontendBaseUrl + "/events/" + eventId;
    }

    private static String kst(Instant instant) {
        return instant == null ? null : KST_TIME.format(instant) + " KST";
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
