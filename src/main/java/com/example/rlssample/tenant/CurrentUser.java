package com.example.rlssample.tenant;

import java.util.Objects;

/**
 * 今のリクエストを処理している「テナントの中の誰か」。
 * <p>
 * JWT から分かるのは {@code subject}（IdP 上の誰か）だけで、ユーザーID・ロール・見てよい部署は
 * テナントを確定したあとに users テーブルと組織階層から解決する。解決前は {@code id} / {@code role} / {@code scope} が null。
 *
 * @param subject JWT の sub
 * @param id      users.id（解決前は null）
 * @param role    users.role（解決前は null）
 * @param scope   自分のデータ以外に見てよい部署（解決前は null）
 */
public record CurrentUser(String subject, Long id, Role role, AccessScope scope) {

    public CurrentUser {
        Objects.requireNonNull(subject, "subject");
        boolean anyNull = id == null || role == null || scope == null;
        boolean allNull = id == null && role == null && scope == null;
        if (anyNull && !allNull) {
            throw new IllegalArgumentException("id, role and scope must be all set or all null");
        }
    }

    /** users テーブルで自分の行を引く前の状態。DB には subject だけが届く。 */
    public static CurrentUser unresolved(String subject) {
        return new CurrentUser(subject, null, null, null);
    }

    public boolean isResolved() {
        return id != null;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
