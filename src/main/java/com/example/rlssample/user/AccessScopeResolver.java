package com.example.rlssample.user;

import com.example.rlssample.tenant.AccessScope;

/**
 * ユーザーが「自分のデータ以外に、どの部署のデータを見てよいか」を算出する。
 * <p>
 * 判断はここ（アプリ）で行い、結果は {@code app.visible_org_ids} として DB に渡されて RLS が強制する（ADR.md）。
 * 兼務・代理・期間限定の権限が増えたときに変わるのは、このインターフェースの実装だけ。
 * 将来は OpenFGA の ListObjects を使う実装に置き換えることを想定している。
 * <p>
 * テナントと（未解決の）ユーザーがセットされたトランザクションの中で呼ばれる。
 */
public interface AccessScopeResolver {

    AccessScope resolve(User user);
}
