package com.couponrush.global.db;

import com.couponrush.IntegrationTest;
import com.couponrush.user.OAuth2Provider;
import com.couponrush.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 스키마는 Flyway 가 만들고 Hibernate 는 validate 만 한다.
 * (컨텍스트가 뜬 것 자체가 엔티티 ↔ 마이그레이션 스키마가 일치한다는 뜻)
 */
@IntegrationTest
class FlywayMigrationTest {

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 마이그레이션이_모두_성공적으로_적용된다() {
        List<Boolean> results = jdbcTemplate.queryForList(
                "SELECT success FROM flyway_schema_history WHERE version IS NOT NULL", Boolean.class);

        assertThat(results).isNotEmpty().allMatch(Boolean.TRUE::equals);
    }

    /**
     * enum 에 값을 추가하고 CHECK 제약 마이그레이션을 빠뜨리면 여기서 실패한다.
     * (로컬 DB 에서 'violates check constraint' 로 처음 발견하는 일을 막기 위함)
     */
    @Test
    void provider_CHECK_제약이_OAuth2Provider_enum_값과_일치한다() {
        assertThat(allowedValues("users_provider_check"))
                .containsExactlyInAnyOrderElementsOf(names(OAuth2Provider.values()));
    }

    @Test
    void role_CHECK_제약이_Role_enum_값과_일치한다() {
        assertThat(allowedValues("users_role_check"))
                .containsExactlyInAnyOrderElementsOf(names(Role.values()));
    }

    @Test
    void 발급요청_status_CHECK_제약이_IssueRequestStatus_enum_값과_일치한다() {
        assertThat(allowedValues("ck_issue_requests_status"))
                .containsExactlyInAnyOrderElementsOf(names(com.couponrush.coupon.request.IssueRequestStatus.values()));
    }

    @Test
    void 허용되지_않은_provider_는_DB_가_거부한다() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO users (provider, provider_id, nickname, role, created_at, updated_at)
                VALUES ('FACEBOOK', 'x', 'nick', 'USER', now(), now())
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** CHECK (provider IN ('A','B')) 정의에서 'A', 'B' 를 뽑아낸다 */
    private List<String> allowedValues(String constraintName) {
        String definition = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?",
                String.class, constraintName);
        return java.util.regex.Pattern.compile("'([^']+)'").matcher(definition).results()
                .map(m -> m.group(1))
                .toList();
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
