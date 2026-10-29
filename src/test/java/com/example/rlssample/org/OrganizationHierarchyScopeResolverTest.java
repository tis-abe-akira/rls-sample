package com.example.rlssample.org;

import com.example.rlssample.support.Actor;
import com.example.rlssample.tenant.CurrentUser;
import com.example.rlssample.tenant.TenantContext;
import com.example.rlssample.tenant.UserContext;
import com.example.rlssample.user.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.stream.Stream;

import static com.example.rlssample.support.Actor.ALICE;
import static com.example.rlssample.support.Actor.A_SHOJI;
import static com.example.rlssample.support.Actor.BOB;
import static com.example.rlssample.support.Actor.DAVE;
import static com.example.rlssample.support.Actor.KEN;
import static com.example.rlssample.support.Actor.MIKA;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「見てよい部署」の算出（ADR.md の選択肢3の①）を確認する。
 * 本番と同じ経路（UserService.resolve → OrganizationHierarchyScopeResolver）で解決し、
 * テスト用の {@link Actor} の値と一致することも確かめる。
 */
@SpringBootTest
class OrganizationHierarchyScopeResolverTest {

    @Autowired
    UserService userService;

    @Autowired
    OrganizationMapper organizationMapper;

    @Autowired
    TransactionTemplate tx;

    static Stream<Actor> actors() {
        return Stream.of(ALICE, BOB, KEN, MIKA, DAVE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("actors")
    @DisplayName("JWT の sub から解決したユーザーID・ロール・見てよい部署が、期待どおり（Actor の値と一致）")
    void resolvesExpectedUser(Actor expected) {
        CurrentUser resolved = TenantContext.runAs(expected.tenantId(), () ->
                UserContext.runAs(CurrentUser.unresolved(expected.user().subject()), () -> userService.resolve().orElseThrow()));

        assertThat(resolved).isEqualTo(expected.user());
    }

    @Test
    @DisplayName("部長は自分の部だけ、事業部長は事業部と配下の部、一般ユーザーと管理者は「なし」")
    void scopeFollowsHierarchy() {
        assertThat(KEN.user().scope().organizationIds()).containsExactlyInAnyOrder(Actor.SALES_DEPT_1);
        assertThat(MIKA.user().scope().organizationIds())
                .containsExactlyInAnyOrder(Actor.SALES_DIVISION, Actor.SALES_DEPT_1, Actor.SALES_DEPT_2)
                .doesNotContain(Actor.ADMIN_HQ);
        assertThat(BOB.user().scope().isEmpty()).isTrue();
        assertThat(ALICE.user().scope().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("階層をたどる再帰 CTE にもテナントの壁が効く: A商事として B工業の部署を起点にしても何も返らない")
    void hierarchyQueryIsTenantIsolated() {
        List<Long> ids = TenantContext.runAs(A_SHOJI, () ->
                tx.execute(status -> organizationMapper.findSelfAndDescendantIds(5)));

        assertThat(ids).isEmpty();
    }
}
