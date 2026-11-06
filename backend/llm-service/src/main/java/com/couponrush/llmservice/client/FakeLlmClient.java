package com.couponrush.llmservice.client;

import com.couponrush.llm.contract.EventCopyCandidate;
import com.couponrush.llm.contract.EventCopyInput;
import com.couponrush.llm.contract.LlmResultMessage;
import com.couponrush.llmservice.LlmServiceProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 가짜 제공자: 네트워크·API 키·비용 없이 입력으로부터 <b>항상 같은</b> 후보 3개를 만든다.
 * 기본 제공자이자 모든 자동 테스트의 제공자 — 실제 모델의 품질이 아니라 "흐름"(요청 → 폴링 → 결과 → 폼 채우기)을 확인하기 위한 것.
 * 토큰 수도 글자 수로 흉내 내서 (5단계) 예산 로직을 실제처럼 검증할 수 있게 한다.
 */
@Component
public class FakeLlmClient implements LlmClient {

    public static final String MODEL = "fake";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("M월 d일 HH:mm");

    private final LlmServiceProperties properties;

    public FakeLlmClient(LlmServiceProperties properties) {
        this.properties = properties;
    }

    @Override
    public LlmCompletion generateEventCopy(EventCopyInput input) {
        pause();
        String keywords = input.keywords();
        String quantity = "선착순 " + String.format("%,d", input.totalQuantity()) + "명";
        String period = period(input.issueStartAt(), input.issueEndAt());

        List<EventCopyCandidate> candidates = switch (input.tone()) {
            case FRIENDLY -> List.of(
                    candidate(keywords, "가볍게 들러 보세요! " + quantity + "에게 드리는 작은 선물이에요." + period),
                    candidate(quantity + " · " + keywords, "기다리셨죠? " + keywords + " 이벤트가 열려요." + period),
                    candidate("오늘의 혜택: " + keywords, "부담 없이 받아 가세요. 수량이 끝나면 종료돼요."));
            case URGENT -> List.of(
                    candidate("[" + quantity + "] " + keywords, "딱 " + String.format("%,d", input.totalQuantity())
                            + "명만! 놓치면 다시 없는 기회입니다." + period),
                    candidate("마감 임박 " + keywords, "수량 소진 시 즉시 종료됩니다. 지금 바로 받으세요." + period),
                    candidate(keywords + " — 지금이 마지막 기회", quantity + ", 서두르세요."));
            case PREMIUM -> List.of(
                    candidate("단 " + String.format("%,d", input.totalQuantity()) + "분께만, " + keywords,
                            "엄선한 혜택을 한정된 분들께만 준비했습니다." + period),
                    candidate("Exclusive · " + keywords, quantity + " 한정으로 제공되는 특별한 혜택입니다."),
                    candidate(keywords + " 프리미엄 에디션", "특별한 순간을 위한 한정 혜택." + period));
            case PLAYFUL -> List.of(
                    candidate(keywords + " 대작전!", "누가 먼저 잡을까? " + quantity + " 레이스 시작!" + period),
                    candidate("두근두근 " + keywords, "광클 준비됐나요? " + quantity + "만 성공!"),
                    candidate(keywords + " 쟁탈전", "빠른 손가락만 살아남는다! 수량 소진 시 끝." + period));
        };

        // 실제 모델처럼 "입력이 길수록, 출력이 길수록" 토큰이 늘게 (한국어 대략 글자 2개 ≈ 1토큰)
        int inputTokens = 150 + keywords.length() / 2;
        int outputTokens = candidates.stream().mapToInt(c -> c.name().length() + c.description().length()).sum() / 2;
        return new LlmCompletion(candidates.subList(0, LlmResultMessage.CANDIDATE_COUNT), MODEL, inputTokens, outputTokens);
    }

    @Override
    public String cacheIdentity() {
        return "fake:v1";
    }

    private static EventCopyCandidate candidate(String name, String description) {
        return new EventCopyCandidate(fit(name, EventCopyCandidate.NAME_MAX), fit(description, EventCopyCandidate.DESCRIPTION_MAX));
    }

    /** 키워드가 길어도(최대 200자) 후보가 계약 길이를 넘지 않게 자른다 */
    private static String fit(String text, int max) {
        String trimmed = text.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max - 1) + "…";
    }

    private static String period(Instant start, Instant end) {
        if (start == null || end == null) {
            return "";
        }
        return " (" + PERIOD.format(start.atZone(KST)) + " ~ " + PERIOD.format(end.atZone(KST)) + ")";
    }

    private void pause() {
        long millis = properties.fake().delay().toMillis();
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
