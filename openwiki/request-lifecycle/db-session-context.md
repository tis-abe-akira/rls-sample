---
type: mechanism
title: DB へのコンテキスト受け渡し（ThreadLocal と SET LOCAL）
description: TenantContext / UserContext の ThreadLocal に入れたテナントとユーザーを、TenantAwareTransactionManager がトランザクション開始直後に set_config(..., true) で同じ接続へ渡す仕組み。値が残らない理由、未設定時の fail-closed、途中での切り替えを禁止する理由をまとめる。
tags: [transaction, threadlocal, set-local, spring, mybatis, fail-closed]
verified:
  - by: openwiki/0.7.2
    at: 2026-10-29T22:35:43.342Z
sources:
  - id: openwiki-source-30c0f5ae480b178275a17c19
    resource: repo://src/main/java/com/example/rlssample/tenant/TenantAwareTransactionManager.java
  - id: openwiki-source-ce4e806a8d5fb9bd2553fe74
    resource: repo://src/main/java/com/example/rlssample/tenant/TenantConfig.java
  - id: openwiki-source-0f66bbd7322788d6430980c4
    resource: repo://src/main/java/com/example/rlssample/tenant/TenantContext.java
  - id: openwiki-source-9045ef485dd28d0e546fea5f
    resource: repo://src/main/java/com/example/rlssample/tenant/UserContext.java
  - id: openwiki-source-b4fc34e2ebcad5a585a7a9f4
    resource: repo://src/test/java/com/example/rlssample/order/OrderRlsIntegrationTest.java
generated: { by: "claude-code", at: "2026-10-29T22:35:43.342Z" }
---

# DB へのコンテキスト受け渡し（ThreadLocal と SET LOCAL）

RLS のポリシーは `app.tenant_id` などのセッション設定を読みます。アプリケーション側でこの値を運ぶのが `TenantContext` / `UserContext`（ThreadLocal）で、DB の接続に届けるのが `TenantAwareTransactionManager` です。DB に値を渡す場所はこの1か所だけで、サービスや Mapper はテナントを意識しません。

## ThreadLocal：TenantContext と UserContext

| クラス | 保持するもの | 読み方 |
|---|---|---|
| `TenantContext` | `TenantId` | `current()`（Optional）、`require()`（未設定なら `IllegalStateException`） |
| `UserContext` | `CurrentUser` | `current()`、`require()`（未設定・**未解決** なら `IllegalStateException`） |

値をセットしてよいのは、リクエストの入口の `TenantFilter` と、バッチやテストで明示的に `runAs` を使う場合だけです。サービス層や Mapper は読むだけにします。

### runAs：必ず元に戻す

`runAs(値, action)` は、値をセットして `action` を実行し、`finally` で **前の値に戻す**（前が無ければ `remove()`）だけのメソッドです。`TenantFilter` は `TenantContext.runAs(tenant, () -> UserContext.runAs(user, chain::doFilter))` の形で後続を実行するので、リクエストが終われば例外が起きても ThreadLocal は空に戻り、スレッドプールの次のリクエストに値が残りません。入れ子にも対応しています。

### トランザクションの途中では切り替えさせない

`runAs` は、実際のトランザクションが進行中（`TransactionSynchronizationManager.isActualTransactionActive()`）で、かつ今と **違う** テナント・ユーザーを指定されると `IllegalStateException`（`Cannot switch tenant inside a transaction` / `Cannot switch user inside a transaction`）を投げます。

理由は、DB への設定が BEGIN のときに1回だけ行われるからです。途中で切り替えると、Java 側は新しいテナント、DB 側は古いテナント（または未設定）のまま動き、両者が食い違います。別のテナントとして処理したい場合は、トランザクションの外で `runAs` を呼び、その内側でトランザクションを始めます。同じテナント・同じユーザーでの `runAs` は、トランザクションの中でも許されます。

## TenantAwareTransactionManager：BEGIN 直後に SET LOCAL

`TenantAwareTransactionManager` は `JdbcTransactionManager` を継承し、`prepareTransactionalConnection` を上書きしています。このフックは Spring がトランザクションを開始し、`autoCommit=false` にした直後に、そのトランザクションの接続に対して呼びます。ここで次の SQL を1回発行します。

```sql
SELECT set_config('app.tenant_id',       ?, true),
       set_config('app.subject',         ?, true),
       set_config('app.user_id',         ?, true),
       set_config('app.role',            ?, true),
       set_config('app.visible_org_ids', ?, true)
```

| 設定 | 値の出どころ | 例 |
|---|---|---|
| `app.tenant_id` | `TenantId#asSettingValue()` | `1` |
| `app.subject` | `CurrentUser#subject()`（JWT の `sub`） | `ken` |
| `app.user_id` | `CurrentUser#id()` | `8` |
| `app.role` | `CurrentUser#role()` の名前 | `MANAGER` |
| `app.visible_org_ids` | `AccessScope#asSettingValue()` | `{2}` |

値はすべて `PreparedStatement` のパラメータとして渡すので、SQL に文字列を連結していません。

第3引数の `true` は `SET LOCAL` と同じ意味で、設定はそのトランザクションの中だけ有効です。そのため、

- `@Transactional` なメソッドの中の MyBatis の SQL すべてに、同じテナントとユーザーが効く
- COMMIT / ROLLBACK で設定が消えるので、コネクションプールに値が残らない（`SET` で設定すると、プールの次の利用者が別テナント・別ユーザーとして動いてしまう）
- AOP の順序（`@Order`）を気にする必要がない

MyBatis は Spring の管理するトランザクション（同じ `Connection`）に参加するので、Mapper の SQL はこの設定済みの接続で実行されます。

### 差し替え：TenantConfig

Spring Boot の自動設定は、`PlatformTransactionManager` が未定義のときだけ作られます。`TenantConfig` が `TenantAwareTransactionManager` を `transactionManager` Bean として定義しているので、自動設定のものと置き換わり、`@Transactional` も `TransactionTemplate` もこれを使います。

## 未設定のときは fail-closed

| 状態 | TransactionManager の動き | RLS の結果 |
|---|---|---|
| テナントが未設定 | `set_config` を発行しない（DEBUG ログのみ） | `current_tenant_id()` が NULL になり、テナント所有の行は 0 件。INSERT はエラー |
| テナントのみ設定、ユーザーが未設定 | テナント以外を空文字で設定 | 自分の行も管理者も成り立たず 0 件 |
| ユーザーが未解決（`CurrentUser.unresolved`） | `subject` だけ設定し、`user_id`・`role`・`visible_org_ids` は空文字 | `users` の自分の行だけ見える（ユーザー解決のための状態） |
| `@Transactional` の外で Mapper を呼ぶ | フックが呼ばれない | 0 件（漏れはしないが気付きにくい） |

空文字は、DB 側のヘルパー関数が `NULLIF` で NULL・`false`・空配列に変換します（[スキーマと RLS ポリシー](../database/schema-and-rls-policies.md)）。

「ユーザーが未解決」の状態は、`TenantFilter` がユーザーを解決するときに意図して作ります。`users_select` ポリシーは `subject` で自分の行を判定するので、この状態でも自分の行だけは引けます（[テナントとユーザーの確定](../request-lifecycle/tenant-and-user-resolution.md)）。

## 注意点

- 1回の HTTP リクエストの中で、トランザクションは少なくとも2回始まる。1回目は `TenantFilter` のユーザー解決（未解決のユーザー）、2回目以降は業務処理（解決済みのユーザー）。それぞれの BEGIN で、その時点の ThreadLocal の値が設定される
- 非同期処理やバッチに ThreadLocal は自動では伝わらない。別スレッドで処理するときは、`runAs` で明示的にテナントとユーザーを渡す
- テストメソッドに `@Transactional` を付けると、`runAs` でテナントを設定する前にトランザクションが始まり、何も見えないテストになる（[テスト：越境できないことの確認](../testing/rls-test-suite.md)）
