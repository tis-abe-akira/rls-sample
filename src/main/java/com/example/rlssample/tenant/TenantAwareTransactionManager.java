package com.example.rlssample.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;

/**
 * トランザクション開始直後に、その接続へ「今のテナントとユーザー（と見てよい部署）」を教える TransactionManager。
 * <p>
 * {@code prepareTransactionalConnection} は Spring が BEGIN 直後（autoCommit=false にした後）に
 * 呼ぶフックなので、ここで {@code set_config(..., true)}（= SET LOCAL）を発行すると:
 * <ul>
 *   <li>@Transactional なメソッド内の MyBatis のSQLすべてにテナントとユーザーが効く</li>
 *   <li>COMMIT / ROLLBACK で設定が消えるので、コネクションプールに値が残らない</li>
 *   <li>AOP の順序（@Order）を気にする必要がない</li>
 * </ul>
 * テナントが未設定の場合は何もしない。そのとき RLS のポリシーは NULL と比較することになり、
 * 結果は 0 件（INSERT はエラー）＝ fail-closed になる。ユーザーが未設定・未解決の場合は空文字を設定し、
 * やはり「自分の行」も「管理者」も成り立たないので 0 件になる。
 */
public class TenantAwareTransactionManager extends JdbcTransactionManager {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareTransactionManager.class);

    private static final String SET_CONTEXT_SQL = """
            SELECT set_config('app.tenant_id',       ?, true),
                   set_config('app.subject',         ?, true),
                   set_config('app.user_id',         ?, true),
                   set_config('app.role',            ?, true),
                   set_config('app.visible_org_ids', ?, true)""";

    public TenantAwareTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition)
            throws SQLException {
        super.prepareTransactionalConnection(con, definition);

        Optional<TenantId> tenantId = TenantContext.current();
        if (tenantId.isEmpty()) {
            log.debug("No tenant in context: RLS will hide all tenant-owned rows in this transaction");
            return;
        }
        Optional<CurrentUser> user = UserContext.current();
        String subject = user.map(CurrentUser::subject).orElse("");
        String userId = user.map(CurrentUser::id).map(String::valueOf).orElse("");
        String role = user.map(CurrentUser::role).map(Role::name).orElse("");
        String visibleOrgIds = user.map(CurrentUser::scope).map(AccessScope::asSettingValue).orElse("");

        try (PreparedStatement ps = con.prepareStatement(SET_CONTEXT_SQL)) {
            ps.setString(1, tenantId.get().asSettingValue());
            ps.setString(2, subject);
            ps.setString(3, userId);
            ps.setString(4, role);
            ps.setString(5, visibleOrgIds);
            ps.execute();
        }
        log.debug("set_config tenant_id={} subject={} user_id={} role={} visible_org_ids={}",
                tenantId.get().value(), subject, userId, role, visibleOrgIds);
    }
}
