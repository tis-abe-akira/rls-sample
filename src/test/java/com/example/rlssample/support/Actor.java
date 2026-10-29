package com.example.rlssample.support;

import com.example.rlssample.tenant.AccessScope;
import com.example.rlssample.tenant.CurrentUser;
import com.example.rlssample.tenant.Role;
import com.example.rlssample.tenant.TenantContext;
import com.example.rlssample.tenant.TenantId;
import com.example.rlssample.tenant.UserContext;

import java.util.function.Supplier;

/**
 * テストで「どのテナントの、誰として動くか」を表す。ID は db/init/03_data.sql の投入順に合わせている。
 * <p>
 * 本番では TenantFilter が JWT・users テーブル・AccessScopeResolver から決める値を、テストでは直接指定する。
 * 管理職の scope が AccessScopeResolver の算出結果と一致することは OrganizationHierarchyScopeResolverTest で確認している。
 * <p>
 * 組織: 営業事業部(1) ─┬─ 第一営業部(2) / 第二営業部(3)、管理本部(4)、製造事業部(5) ── 第一製造部(6)
 */
public record Actor(TenantId tenantId, CurrentUser user) {

    public static final TenantId A_SHOJI = TenantId.of(1);
    public static final TenantId B_KOGYO = TenantId.of(2);

    public static final long SALES_DIVISION = 1;
    public static final long SALES_DEPT_1 = 2;
    public static final long SALES_DEPT_2 = 3;
    public static final long ADMIN_HQ = 4;

    /** A商事の管理者（管理本部） */
    public static final Actor ALICE = new Actor(A_SHOJI, new CurrentUser("alice", 1L, Role.ADMIN, AccessScope.none()));
    /** A商事の一般ユーザー（第一営業部） */
    public static final Actor BOB = new Actor(A_SHOJI, new CurrentUser("bob", 2L, Role.GENERAL, AccessScope.none()));
    /** A商事の一般ユーザー（第二営業部） */
    public static final Actor CAROL = new Actor(A_SHOJI, new CurrentUser("carol", 3L, Role.GENERAL, AccessScope.none()));
    /** A商事 第一営業部の部長 */
    public static final Actor KEN = new Actor(A_SHOJI,
            new CurrentUser("ken", 8L, Role.MANAGER, AccessScope.of(SALES_DEPT_1)));
    /** A商事 営業事業部の事業部長 */
    public static final Actor MIKA = new Actor(A_SHOJI,
            new CurrentUser("mika", 9L, Role.MANAGER, AccessScope.of(SALES_DIVISION, SALES_DEPT_1, SALES_DEPT_2)));
    /** B工業の管理者 */
    public static final Actor DAVE = new Actor(B_KOGYO, new CurrentUser("dave", 5L, Role.ADMIN, AccessScope.none()));
    /** B工業の一般ユーザー */
    public static final Actor ERIN = new Actor(B_KOGYO, new CurrentUser("erin", 6L, Role.GENERAL, AccessScope.none()));

    /** このテナント・ユーザーとして処理を実行する */
    public <T> T run(Supplier<T> action) {
        return TenantContext.runAs(tenantId, () -> UserContext.runAs(user, action));
    }

    public void run(Runnable action) {
        run(() -> {
            action.run();
            return null;
        });
    }

    public long userId() {
        return user.id();
    }
}
