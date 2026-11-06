package com.couponrush.auth.oauth2;

import com.couponrush.user.Role;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LoginUserTest {

    @Test
    void 제공자_응답에_null_값이_있어도_생성된다() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sub", "g1");
        attributes.put("picture", null);

        LoginUser loginUser = new LoginUser(1L, Role.USER, attributes);

        assertThat(loginUser.getAttributes()).containsEntry("picture", null);
        assertThat(loginUser.getName()).isEqualTo("1");
        assertThat(loginUser.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
    }
}
