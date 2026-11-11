package com.couponrush.llmservice.client;

import com.couponrush.llm.contract.EventSearchFilter;
import com.couponrush.llm.contract.EventSearchStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * fake 제공자의 자연어 검색: 정해진 표현만 알아듣는 <b>규칙 기반</b> 변환. 실제 모델의 이해력이 아니라 흐름 확인용이다.
 * 같은 질문 + 같은 날짜면 항상 같은 조건 (테스트·캐시 확인이 쉽게).
 * <ul>
 *   <li>상태: "진행 중" → OPEN, "오픈 예정"·"시작 전" → UPCOMING, "종료된"·"끝난" → ENDED</li>
 *   <li>재고: "남은"·"재고" → availableOnly</li>
 *   <li>기간: 오늘 / 내일 / 이번 주 / 다음 주 / 이번 달. "시작"·"오픈"·"열리는"이 있으면 발급 시작 범위, 아니면 발급 종료 범위</li>
 *   <li>나머지 단어 → keywords (조사·일반어 제거, 최대 5개·각 50자)</li>
 * </ul>
 */
final class FakeEventSearch {

    /** 상태·재고·기간을 나타내는 표현 — 조건으로 바꾼 뒤 keywords 에서는 지운다 (긴 표현부터) */
    private static final List<String> OPEN = List.of("받을 수 있는", "진행 중인", "진행중인", "진행 중", "진행중");
    private static final List<String> UPCOMING = List.of("오픈 예정", "시작 전", "곧 열리는", "다가오는", "예정");
    private static final List<String> ENDED = List.of("종료된", "끝난", "지난");
    private static final List<String> AVAILABLE = List.of("남아 있는", "남아있는", "남은", "재고 있는", "재고");
    private static final List<String> START_SIDE = List.of("시작하는", "시작되는", "오픈하는", "열리는", "시작", "오픈");
    private static final List<String> END_SIDE = List.of("마감되는", "끝나는", "종료되는", "마감");

    private static final Set<String> STOPWORDS = Set.of(
            "쿠폰", "이벤트", "혜택", "것", "거", "뭐", "뭐야", "뭐가", "있는", "있어", "있나요", "있니", "중",
            "알려줘", "보여줘", "찾아줘", "목록", "좀", "다", "전부", "모든", "주세요");
    /** 단어 끝에서 떼어 낼 조사 (긴 것부터). 떼고 나서 2글자 이상 남을 때만 뗀다 ("차이"의 "이"는 그대로) */
    private static final List<String> PARTICLES = List.of("으로", "에서", "은", "는", "이", "가", "을", "를", "의", "에", "로", "도", "만", "요");

    private FakeEventSearch() {
    }

    static EventSearchFilter extract(String query, LocalDate today) {
        String text = " " + query.strip() + " ";

        Set<EventSearchStatus> statuses = EnumSet.noneOf(EventSearchStatus.class);
        if (containsAny(text, OPEN)) statuses.add(EventSearchStatus.OPEN);
        if (containsAny(text, UPCOMING)) statuses.add(EventSearchStatus.UPCOMING);
        if (containsAny(text, ENDED)) statuses.add(EventSearchStatus.ENDED);
        boolean availableOnly = containsAny(text, AVAILABLE);

        LocalDate[] period = period(text, today);
        Instant from = period == null ? null : KstDates.startOf(period[0]);
        Instant to = period == null ? null : KstDates.startOf(period[1]);
        boolean startSide = containsAny(text, START_SIDE);

        List<String> keywords = keywords(text);
        return startSide
                ? new EventSearchFilter(keywords, statuses, from, to, null, null, availableOnly)
                : new EventSearchFilter(keywords, statuses, null, null, from, to, availableOnly);
    }

    /** [첫날, 끝 다음 날) — 없으면 null */
    private static LocalDate[] period(String text, LocalDate today) {
        LocalDate monday = KstDates.mondayOf(today);
        if (containsAny(text, List.of("다음 주", "다음주"))) return new LocalDate[]{monday.plusWeeks(1), monday.plusWeeks(2)};
        if (containsAny(text, List.of("이번 주", "이번주"))) return new LocalDate[]{monday, monday.plusWeeks(1)};
        if (containsAny(text, List.of("이번 달", "이번달"))) {
            LocalDate first = today.withDayOfMonth(1);
            return new LocalDate[]{first, first.plusMonths(1)};
        }
        if (text.contains("내일")) return new LocalDate[]{today.plusDays(1), today.plusDays(2)};
        if (text.contains("오늘")) return new LocalDate[]{today, today.plusDays(1)};
        return null;
    }

    private static List<String> keywords(String text) {
        String rest = text;
        for (List<String> phrases : List.of(OPEN, UPCOMING, ENDED, AVAILABLE, START_SIDE, END_SIDE,
                List.of("다음 주", "다음주", "이번 주", "이번주", "이번 달", "이번달", "내일", "오늘"))) {
            for (String phrase : phrases) {
                rest = rest.replace(phrase, " ");
            }
        }
        Set<String> words = new LinkedHashSet<>();
        for (String token : rest.split("[\\s,.!?·~\"'()]+")) {
            String word = stripParticle(token);
            if (word.isEmpty() || PARTICLES.contains(word) || STOPWORDS.contains(word)) {
                continue;
            }
            words.add(word.length() <= EventSearchFilter.KEYWORD_MAX ? word : word.substring(0, EventSearchFilter.KEYWORD_MAX));
            if (words.size() == EventSearchFilter.KEYWORD_COUNT_MAX) {
                break;
            }
        }
        return new ArrayList<>(words);
    }

    private static String stripParticle(String token) {
        for (String particle : PARTICLES) {
            if (token.endsWith(particle) && token.length() - particle.length() >= 2) {
                return token.substring(0, token.length() - particle.length());
            }
        }
        return token;
    }

    private static boolean containsAny(String text, List<String> phrases) {
        return phrases.stream().anyMatch(text::contains);
    }
}
