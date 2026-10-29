package com.example.rlssample.user;

import com.example.rlssample.common.ForbiddenException;
import com.example.rlssample.tenant.CurrentUser;
import com.example.rlssample.tenant.CurrentUserResolver;
import com.example.rlssample.tenant.Role;
import com.example.rlssample.tenant.TenantContext;
import com.example.rlssample.tenant.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class UserService implements CurrentUserResolver {

    private final UserMapper userMapper;
    private final AccessScopeResolver accessScopeResolver;

    public UserService(UserMapper userMapper, AccessScopeResolver accessScopeResolver) {
        this.userMapper = userMapper;
        this.accessScopeResolver = accessScopeResolver;
    }

    /**
     * 未解決の {@link CurrentUser}（subject だけ）から、ユーザーID・ロール・見てよい部署を解決する。
     * DB には app.subject が届いているので、RLS により「自分の行」だけが見える。
     */
    @Override
    public Optional<CurrentUser> resolve() {
        String subject = UserContext.current()
                .orElseThrow(() -> new IllegalStateException("Subject is not set for the current thread"))
                .subject();
        return userMapper.findBySubject(subject)
                .map(user -> new CurrentUser(user.subject(), user.id(), user.role(), accessScopeResolver.resolve(user)));
    }

    /**
     * ユーザー一覧。管理者はテナント内の全員、管理職は見てよい部署のメンバー、一般ユーザーは 403。
     * RLS でも、一般ユーザーには自分の行しか見えない。
     */
    public List<User> listUsers() {
        CurrentUser me = UserContext.require();
        long tenantId = TenantContext.require().value();
        if (me.isAdmin()) {
            return userMapper.findAllByTenant(tenantId);
        }
        if (me.role() == Role.MANAGER && !me.scope().isEmpty()) {
            return userMapper.findAllByOrganizations(tenantId, me.scope().organizationIds());
        }
        throw new ForbiddenException("only managers and administrators can list users");
    }
}
