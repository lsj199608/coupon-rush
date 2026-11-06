package com.couponrush.llmservice.client;

import org.junit.jupiter.api.Test;

import static com.couponrush.llmservice.client.LlmClientConfig.Provider.ANTHROPIC;
import static com.couponrush.llmservice.client.LlmClientConfig.Provider.FAKE;
import static com.couponrush.llmservice.client.LlmClientConfig.choose;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmClientConfigTest {

    @Test
    void 기본은_fake() {
        assertThat(choose("fake", "")).isEqualTo(FAKE);
        assertThat(choose(null, null)).isEqualTo(FAKE);
        assertThat(choose("fake", "sk-ant-real")).as("키가 있어도 fake 를 고르면 fake").isEqualTo(FAKE);
    }

    @Test
    void anthropic_인데_키가_없으면_fake_로_동작한다() {
        assertThat(choose("anthropic", "")).isEqualTo(FAKE);
        assertThat(choose("anthropic", "   ")).isEqualTo(FAKE);
        assertThat(choose("anthropic", null)).isEqualTo(FAKE);
    }

    @Test
    void anthropic_과_키가_있으면_anthropic() {
        assertThat(choose("anthropic", "sk-ant-test")).isEqualTo(ANTHROPIC);
        assertThat(choose(" Anthropic ", "sk-ant-test")).isEqualTo(ANTHROPIC);
    }

    @Test
    void 모르는_값은_기동을_막는다() {
        assertThatThrownBy(() -> choose("openai", "key")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> choose("antropic", "key")).as("오타").isInstanceOf(IllegalArgumentException.class);
    }
}
