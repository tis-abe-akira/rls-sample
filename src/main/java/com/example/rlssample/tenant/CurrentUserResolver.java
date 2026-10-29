package com.example.rlssample.tenant;

import java.util.Optional;

/**
 * テナント確定後に、JWT の subject から「テナントの中の誰か」（ユーザーIDとロール）を解決する。
 * <p>
 * {@link TenantContext} と未解決の {@link CurrentUser} がセットされた状態で呼ばれる。
 * 実装は users テーブルを引く（{@code UserService}）。そのテナントに登録されていなければ空を返す。
 */
public interface CurrentUserResolver {

    Optional<CurrentUser> resolve();
}
