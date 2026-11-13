package com.couponrush.search;

import com.couponrush.llm.contract.EventSearchFilter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * LLM 없이 질문 → 검색 단어 (키워드 검색 fallback). 뜻을 해석하지 않는다 — "이번 주"·"진행 중" 같은 조건은 버리고 단어만 남긴다.
 * <ul>
 *   <li>공백으로 나누고 앞뒤 문장부호 제거</li>
 *   <li>끝의 조사 하나 제거 ("커피를" → "커피") — 남는 글자가 2자 이상일 때만 ("이벤트의" → "이벤트", "가을" 은 그대로)</li>
 *   <li>1글자, 일반어(쿠폰·이벤트·보여줘…: 거의 모든 이벤트에 맞거나 아무것도 안 맞는 말) 제외</li>
 *   <li>중복 제거, 최대 {@value EventSearchFilter#KEYWORD_COUNT_MAX}개, 각 {@value EventSearchFilter#KEYWORD_MAX}자 (LLM 조건과 같은 상한)</li>
 * </ul>
 * 단어가 하나도 안 남으면 빈 목록 = 조건 없음(최근 이벤트).
 */
final class KeywordQuery {

    private static final Pattern EDGE_PUNCTUATION = Pattern.compile("^[\\p{Punct}\\p{IsPunctuation}]+|[\\p{Punct}\\p{IsPunctuation}]+$");
    private static final List<String> PARTICLES = List.of("에서", "으로", "은", "는", "이", "가", "을", "를", "의", "에", "도", "만", "로");
    private static final Set<String> STOPWORDS = Set.of(
            "쿠폰", "이벤트", "보여줘", "보여", "찾아줘", "찾아", "알려줘", "알려", "있어", "있나요", "있는", "어떤", "뭐", "거", "것");

    private KeywordQuery() {
    }

    static List<String> keywordsOf(String query) {
        Set<String> keywords = new LinkedHashSet<>();
        for (String raw : query.strip().split("\\s+")) {
            String word = stripParticle(EDGE_PUNCTUATION.matcher(raw).replaceAll("").toLowerCase(Locale.ROOT));
            if (word.length() < 2 || STOPWORDS.contains(word)) {
                continue;
            }
            keywords.add(word.length() > EventSearchFilter.KEYWORD_MAX ? word.substring(0, EventSearchFilter.KEYWORD_MAX) : word);
            if (keywords.size() == EventSearchFilter.KEYWORD_COUNT_MAX) {
                break;
            }
        }
        return List.copyOf(keywords);
    }

    private static String stripParticle(String word) {
        for (String particle : PARTICLES) {
            if (word.endsWith(particle) && word.length() - particle.length() >= 2) {
                return word.substring(0, word.length() - particle.length());
            }
        }
        return word;
    }
}
