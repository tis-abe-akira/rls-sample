package com.example.rlssample.user;

import com.example.rlssample.common.ForbiddenException;
import com.example.rlssample.tenant.CurrentUser;
import com.example.rlssample.tenant.CurrentUserResolver;
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

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 未解決の {@link CurrentUser}（subject だけ）から、ユーザーIDとロールを解決する。
     * DB には app.subject が届いているので、RLS により「自分の行」だけが見える。
     */
    @Override
    public Optional<CurrentUser> resolve() {
        String subject = UserContext.current()
                .orElseThrow(() -> new IllegalStateException("Subject is not set for the current thread"))
                .subject();
        return userMapper.findBySubject(subject)
                .map(user -> new CurrentUser(user.subject(), user.id(), user.role()));
    }

    /** テナント内のユーザー一覧。管理者だけ（RLS でも一般ユーザーには自分の行しか見えない）。 */
    public List<User> listUsers() {
        if (!UserContext.require().isAdmin()) {
            throw new ForbiddenException("only administrators can list users");
        }
        return userMapper.findAllByTenant(TenantContext.require().value());
    }
}
