package com.example.rlssample.org;

import com.example.rlssample.tenant.AccessScope;
import com.example.rlssample.tenant.Role;
import com.example.rlssample.user.AccessScopeResolver;
import com.example.rlssample.user.User;
import org.springframework.stereotype.Component;

import java.util.HashSet;

/**
 * 組織階層だけで「見てよい部署」を決める、自前の {@link AccessScopeResolver}（ADR.md の候補 A）。
 * <ul>
 *   <li>一般ユーザー・管理者 → なし（一般は自分のデータだけ。管理者の「全部」はロールで表す）</li>
 *   <li>管理職 → 自分の所属部署とその配下のすべて（部長なら部、事業部長なら事業部と配下の部）</li>
 * </ul>
 * 兼務・代理・期間限定が必要になったら、この実装を拡張するか、OpenFGA を使う実装に置き換える。
 */
@Component
public class OrganizationHierarchyScopeResolver implements AccessScopeResolver {

    private final OrganizationMapper organizationMapper;

    public OrganizationHierarchyScopeResolver(OrganizationMapper organizationMapper) {
        this.organizationMapper = organizationMapper;
    }

    @Override
    public AccessScope resolve(User user) {
        if (user.role() != Role.MANAGER) {
            return AccessScope.none();
        }
        return new AccessScope(new HashSet<>(organizationMapper.findSelfAndDescendantIds(user.orgId())));
    }
}
