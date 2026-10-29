package com.example.rlssample.user;

import com.example.rlssample.tenant.CurrentUser;
import com.example.rlssample.tenant.Role;
import com.example.rlssample.tenant.TenantContext;
import com.example.rlssample.tenant.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** 今のリクエストが「どのテナントの、誰として」動いているか */
    @GetMapping("/me")
    public Me me() {
        CurrentUser user = UserContext.require();
        List<Long> visibleOrganizationIds = user.scope().organizationIds().stream().sorted().toList();
        return new Me(TenantContext.require().value(), user.id(), user.subject(), user.role(), visibleOrganizationIds);
    }

    @GetMapping("/users")
    public List<User> list() {
        return userService.listUsers();
    }

    /** visibleOrganizationIds: 自分のデータ以外に見てよい部署（AccessScopeResolver の算出結果） */
    public record Me(long tenantId, long userId, String subject, Role role, List<Long> visibleOrganizationIds) {
    }
}
