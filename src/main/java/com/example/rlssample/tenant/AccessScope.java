package com.example.rlssample.tenant;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 「自分のデータ以外に、どの部署のデータを見てよいか」。
 * <p>
 * 判断（組織階層をたどる、将来は兼務・代理・期間限定も考慮する）はアプリの AccessScopeResolver が行い、
 * 結果だけを {@code app.visible_org_ids} として DB に渡す。DB の RLS は「渡された一覧に入っているか」だけを見る（ADR.md）。
 * 管理者の「全部見える」はロールで表すので、ここには含めない。
 */
public record AccessScope(Set<Long> organizationIds) {

    private static final AccessScope NONE = new AccessScope(Set.of());

    public AccessScope {
        organizationIds = Set.copyOf(organizationIds);
    }

    public static AccessScope none() {
        return NONE;
    }

    public static AccessScope of(Long... organizationIds) {
        return new AccessScope(Set.of(organizationIds));
    }

    public boolean isEmpty() {
        return organizationIds.isEmpty();
    }

    /** PostgreSQL の配列リテラル（例: {1,2,3}）。set_config に渡す */
    public String asSettingValue() {
        return organizationIds.stream().sorted().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }
}
