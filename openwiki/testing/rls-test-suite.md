---
type: testing
title: テスト：越境できないことの確認
description: 実際の PostgreSQL に接続する5つのテストクラスが、テナントの壁・テナント内の権限・組織階層の範囲・入口でのテナントとユーザーの確定をどう確かめているか。テスト用 Actor の使い方と、テストに @Transactional を付けない理由をまとめる。
tags: [testing, integration-test, rls, spring-boot-test, junit]
verified:
  - by: openwiki/0.7.2
    at: 2026-10-29T20:28:49.941Z
sources:
  - id: openwiki-source-b4fc34e2ebcad5a585a7a9f4
    resource: repo://src/test/java/com/example/rlssample/order/OrderRlsIntegrationTest.java
  - id: openwiki-source-b823e8e08b7b68930a5702d6
    resource: repo://src/test/java/com/example/rlssample/order/OrderRoleAccessTest.java
  - id: openwiki-source-f7a6629511ed92b57f2a95dd
    resource: repo://src/test/java/com/example/rlssample/order/OrgScopeAccessTest.java
  - id: openwiki-source-0fcc579c8ca35b4522510079
    resource: repo://src/test/java/com/example/rlssample/org/OrganizationHierarchyScopeResolverTest.java
  - id: openwiki-source-e7dba39fd3249c85dfcc11d5
    resource: repo://src/test/java/com/example/rlssample/support/Actor.java
  - id: openwiki-source-40348177f850b343b14dbfb5
    resource: repo://src/test/java/com/example/rlssample/tenant/TenantFilterWebTest.java
generated: { by: "claude-code", at: "2026-10-29T20:28:49.941Z" }
---

# テスト：越境できないことの確認

テストの目的は「越境できないこと」を確かめることです。正しい SQL で正しい結果が返ることに加えて、**WHERE を書き忘れた SQL、id だけの SQL、アプリのチェックをすり抜けて直接流した SQL でも、見えてはいけない行が見えない・作れない** ことを確認します。

## 前提

- すべて `@SpringBootTest` で、実際の DB に接続する。事前に `docker compose up -d` で DB を起動しておく（[ローカルでの動かし方とデモ](../operations/local-development.md)）
- ID は `db/init/03_data.sql` の投入順で採番された値（organizations 1〜6、users 1〜9、orders 1〜6）を前提にしている。デモデータを変えたら DB を作り直し、テストの期待値も見直す
- 作成したデータはテストの中で削除し、異動のように既存データを変えるテストは `finally` で元に戻している

## テストクラス

| テスト | 確認していること |
|---|---|
| `OrderRlsIntegrationTest` | テナントの壁。**管理者でも** 他社は見えない・変えられない、fail-closed、トランザクション途中の切り替え禁止 |
| `OrderRoleAccessTest` | テナントの中の権限。一般ユーザーは自分の分だけ、管理者はテナント内の全員分 |
| `OrgScopeAccessTest` | 組織階層の範囲。部長・事業部長の見え方、代理作成、範囲の外への `WITH CHECK`、異動後の受注 |
| `OrganizationHierarchyScopeResolverTest` | 見てよい部署の算出。本番と同じ経路で解決した結果と `Actor` の一致、再帰 CTE にもテナントの壁が効くこと |
| `TenantFilterWebTest` | 入口。MockMvc で 401 / 400 / 403、テナントごとのロール、`/api/me`、HTTP 経由の見え方 |

### OrderRlsIntegrationTest（テナントの壁）

主に管理者（alice / dave）で実行し、ロールの権限ではなく RESTRICTIVE ポリシーで守られていることを確かめます。

- 一覧、WHERE なしの一覧、明細を直接 SELECT しても自社分だけ
- 他社の受注 id を指定した取得・更新・削除・明細追加は `OrderNotFoundException` になり、対象の行は消えずに残る
- 他社の `tenant_id` で INSERT すると `row-level security policy` 違反、自社の `tenant_id` で他社ユーザー名義の受注を作ると外部キー違反
- テナントもユーザーも未設定、テナントだけ設定、トランザクションの外で Mapper を呼ぶ、のいずれも 0 件
- トランザクションの途中で別テナントに切り替えると `IllegalStateException`、同じ相手での `runAs` は許される

### OrderRoleAccessTest（一般と管理者）

- 一般ユーザー（bob）の一覧・WHERE なしの一覧・明細は自分の分だけ。他人（carol）の受注の取得・更新・削除・明細追加は 404
- 他人名義の作成はアプリが 403（`ForbiddenException`）、Mapper を直接呼んでも DB の `WITH CHECK` が拒否する
- 一般ユーザーのユーザー一覧はアプリが 403、`findAllByTenant` を直接流しても自分の行だけ
- 管理者は他人の受注を取得・更新・削除・明細追加でき、他人名義でも作成できる。ユーザー一覧は自社の6人

### OrgScopeAccessTest（部長・事業部長）

クラスの Javadoc に、ロールごとに見える受注の期待値がまとまっています。

| ユーザー | 見える受注 |
|---|---|
| bob（一般） | 1 |
| ken（部長・第一営業部） | 1, 6 |
| mika（事業部長・営業事業部） | 1, 2, 6 |
| alice（管理者） | 1, 2, 5, 6 |

ほかに、WHERE なしの一覧でも範囲の中だけになること、部長は範囲の外（第二営業部）の受注に触れず事業部長は触れること、事業部長でも配下でない部署（管理本部）の受注は見えないこと、範囲の中での更新・明細追加・削除・代理作成、範囲の外の代理作成が 403 になり DB でも拒否されること、ユーザー一覧が範囲のメンバーだけになることを確かめます。

異動のテストでは、bob を第二営業部に移しても、過去の受注 1 は第一営業部の部長 ken から見え続け、bob 自身からも見え、ken のユーザー一覧からは bob が外れることを確認します。異動は管理者（alice）として `JdbcTemplate` で `users` を更新し、`finally` で元に戻します。

## テスト用の Actor

`support/Actor` は「どのテナントの、誰として動くか」を表す record です。本番では `TenantFilter` が JWT・`users`・`AccessScopeResolver` から決める値を、テストでは定数として直接持ちます。

```java
List<Order> orders = KEN.run(orderService::listOrders);
```

`run` は `TenantContext.runAs(tenantId, () -> UserContext.runAs(user, action))` を呼ぶだけです。`ALICE`・`BOB`・`CAROL`・`KEN`・`MIKA`・`DAVE`・`ERIN` と、テナント（`A_SHOJI`・`B_KOGYO`）、部署 ID（`SALES_DIVISION`・`SALES_DEPT_1`・`SALES_DEPT_2`・`ADMIN_HQ`）が定義されています。

管理職の `scope` を手で書いているので、それが本番の算出結果と一致することを `OrganizationHierarchyScopeResolverTest` が確かめています（`UserService.resolve` で解決した `CurrentUser` と `Actor` の値を比較）。

## テストに @Transactional を付けない

テストメソッドに `@Transactional` を付けると、テスト開始時点、つまり `runAs` でテナントを設定する **前** にトランザクションが始まります。DB への設定は BEGIN のときに1回だけなので、テナントが設定されず、何も見えないテストになってしまいます。さらに、そのトランザクションの中で `runAs` で別の相手に切り替えると例外になります（[DB へのコンテキスト受け渡し](../request-lifecycle/db-session-context.md)）。

そのため、テストは必ず `Actor#run` の **内側** でトランザクションを始めます。サービスのメソッドはそれ自体が `@Transactional` なので、そのまま呼べます。

## 「アプリのチェックをすり抜けた」を再現する

DB の守りだけを確かめたいときは、サービスを通さずに Mapper を直接呼びます。Mapper にはトランザクションがないので、`TransactionTemplate` で包みます。

```java
BOB.run(() -> tx.execute(status ->
        orderMapper.insert(A_SHOJI.value(), CAROL.userId(), 3L, "なりすまし")));
// → DataAccessException、root cause のメッセージに "row-level security policy"
```

`tx.execute` を外すと、トランザクションの外で SQL が流れて 0 件になります。これは「`@Transactional` の付け忘れ」を検知するテスト（`outsideTransaction_nothingIsVisible`）でわざと使っています。

## TenantFilterWebTest（入口）

`@AutoConfigureMockMvc` で HTTP 経由の挙動を確かめます。トークンは `app.jwt.secret` を使って `NimbusJwtEncoder` で発行し、別の鍵で署名したトークンや `tenant_ids` のないトークンも作れます。ステータスの一覧は [テナントとユーザーの確定](../request-lifecycle/tenant-and-user-resolution.md) と [受注・ユーザー API と Mapper](../domain/orders-and-users-api.md) を参照してください。
