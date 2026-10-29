package com.example.rlssample.support;

import com.example.rlssample.tenant.CurrentUser;
import com.example.rlssample.tenant.Role;
import com.example.rlssample.tenant.TenantContext;
import com.example.rlssample.tenant.TenantId;
import com.example.rlssample.tenant.UserContext;

import java.util.function.Supplier;

/**
 * テストで「どのテナントの、誰として動くか」を表す。ID は db/init/03_data.sql の投入順に合わせている。
 * <p>
 * 本番では TenantFilter が JWT と users テーブルから決める値を、テストでは直接指定する。
 */
public record Actor(TenantId tenantId, CurrentUser user) {

    public static final TenantId A_SHOJI = TenantId.of(1);
    public static final TenantId B_KOGYO = TenantId.of(2);

    public static final Actor ALICE = new Actor(A_SHOJI, new CurrentUser("alice", 1L, Role.ADMIN));
    public static final Actor BOB = new Actor(A_SHOJI, new CurrentUser("bob", 2L, Role.GENERAL));
    public static final Actor CAROL = new Actor(A_SHOJI, new CurrentUser("carol", 3L, Role.GENERAL));
    public static final Actor DAVE = new Actor(B_KOGYO, new CurrentUser("dave", 5L, Role.ADMIN));
    public static final Actor ERIN = new Actor(B_KOGYO, new CurrentUser("erin", 6L, Role.GENERAL));

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
