package com.example.rlssample.tenant;

import java.util.Objects;

/**
 * 今のリクエストを処理している「テナントの中の誰か」。
 * <p>
 * JWT から分かるのは {@code subject}（IdP 上の誰か）だけで、ユーザーIDとロールは
 * テナントを確定したあとに users テーブルから解決する。解決前は {@code id} と {@code role} が null。
 *
 * @param subject JWT の sub
 * @param id      users.id（解決前は null）
 * @param role    users.role（解決前は null）
 */
public record CurrentUser(String subject, Long id, Role role) {

    public CurrentUser {
        Objects.requireNonNull(subject, "subject");
        if ((id == null) != (role == null)) {
            throw new IllegalArgumentException("id and role must be both set or both null");
        }
    }

    /** users テーブルで自分の行を引く前の状態。DB には subject だけが届く。 */
    public static CurrentUser unresolved(String subject) {
        return new CurrentUser(subject, null, null);
    }

    public boolean isResolved() {
        return id != null;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
