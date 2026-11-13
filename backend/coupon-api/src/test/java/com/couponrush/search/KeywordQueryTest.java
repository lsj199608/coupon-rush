package com.couponrush.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordQueryTest {

    @Test
    void 공백으로_나누고_조사와_일반어를_뺀다() {
        assertThat(KeywordQuery.keywordsOf("커피를 할인 쿠폰 보여줘"))
                .containsExactly("커피", "할인");
    }

    @Test
    void 조사를_빼면_한_글자가_되는_단어는_그대로_둔다() {
        assertThat(KeywordQuery.keywordsOf("가을 이벤트의 치킨이")).containsExactly("가을", "치킨");
    }

    @Test
    void 문장부호_한_글자_중복을_뺀다() {
        assertThat(KeywordQuery.keywordsOf("  커피!  커피?  a  \"치킨\"  ")).containsExactly("커피", "치킨");
    }

    @Test
    void 영문은_소문자로() {
        assertThat(KeywordQuery.keywordsOf("STARBUCKS Coffee")).containsExactly("starbucks", "coffee");
    }

    @Test
    void 단어는_최대_5개_각_50자() {
        assertThat(KeywordQuery.keywordsOf("하나 둘둘 셋셋 넷넷 다섯 여섯")).hasSize(5).doesNotContain("여섯");
        assertThat(KeywordQuery.keywordsOf("가".repeat(80)).get(0)).hasSize(50);
    }

    @Test
    void 남는_단어가_없으면_빈_목록() {
        assertThat(KeywordQuery.keywordsOf("쿠폰 보여줘")).isEmpty();
    }
}
