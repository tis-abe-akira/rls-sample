---
type: api
title: 受注・ユーザー API と Mapper
description: /api/orders・/api/me・/api/users のエンドポイント、ロールに応じた SQL の選び方、代理作成のチェック、わざと残した危ない SQL（WHERE の書き忘れ・id だけの IDOR）、404 と 403 の使い分けをまとめる。
tags: [api, orders, users, mybatis, idor, error-handling]
verified:
  - by: openwiki/0.7.2
    at: 2026-10-29T20:28:49.941Z
sources:
  - id: openwiki-source-b9e9b19f2fe6a305c2bc92f3
    resource: repo://src/main/java/com/example/rlssample/common/ApiExceptionHandler.java
  - id: openwiki-source-aee78bfd91b694a05866bc6c
    resource: repo://src/main/java/com/example/rlssample/common/ForbiddenException.java
  - id: openwiki-source-b8cbb7ba727848e5d6d9bfd6
    resource: repo://src/main/java/com/example/rlssample/order/OrderController.java
  - id: openwiki-source-4ca7fd0aac5bf27aa390648e
    resource: repo://src/main/java/com/example/rlssample/order/OrderMapper.java
  - id: openwiki-source-b8c7cc9e838049926d8aac28
    resource: repo://src/main/java/com/example/rlssample/order/OrderNotFoundException.java
  - id: openwiki-source-d14c00b0c82b0bb4d07c161d
    resource: repo://src/main/java/com/example/rlssample/order/OrderService.java
  - id: openwiki-source-9f8966252bd0d05b22917150
    resource: repo://src/main/java/com/example/rlssample/user/UserController.java
  - id: openwiki-source-4e5e59f568f3afb24ad07145
    resource: repo://src/main/java/com/example/rlssample/user/UserService.java
  - id: openwiki-source-2bd85f9faf1fb7e8210d7fd0
    resource: repo://src/main/resources/mapper/OrderMapper.xml
  - id: openwiki-source-f7a6629511ed92b57f2a95dd
    resource: repo://src/test/java/com/example/rlssample/order/OrgScopeAccessTest.java
generated: { by: "claude-code", at: "2026-10-29T20:28:49.941Z" }
---

# 受注・ユーザー API と Mapper

業務の API は受注（`order` パッケージ）とユーザー（`user` パッケージ）の2つです。どちらも、テナントとユーザーは `TenantFilter` が決めて ThreadLocal に入れ済みで、サービスは `TenantContext.require()` / `UserContext.require()` で読むだけです（[テナントとユーザーの確定](../request-lifecycle/tenant-and-user-resolution.md)）。

## エンドポイント

| メソッド | パス | サービス | 内容 |
|---|---|---|---|
| GET | `/api/orders` | `OrderService#listOrders` | ロールに応じて正しく絞った一覧 |
| GET | `/api/orders/leaky` | `OrderService#listOrdersLeaky` | WHERE 句を書き忘れた一覧（RLS の効果を見るためのもの） |
| GET | `/api/orders/{id}` | `OrderService#getOrder` | 受注1件と明細。id だけで取得する |
| POST | `/api/orders` | `OrderService#createOrder` | 受注を明細ごと作成（201）。`userId` を省略すると自分名義 |
| PUT | `/api/orders/{id}` | `OrderService#updateTitle` | タイトルを更新。id だけで更新する |
| POST | `/api/orders/{id}/details` | `OrderService#addDetail` | 明細を追加（201） |
| DELETE | `/api/orders/{id}` | `OrderService#deleteOrder` | 受注を削除（204）。明細は CASCADE で消える |
| GET | `/api/me` | `UserController#me` | 今「どのテナントの、誰として」動いているか |
| GET | `/api/users` | `UserService#listUsers` | ユーザー一覧（管理職・管理者のみ） |

リクエストには `tenantId` を含めません（`CreateOrderRequest` は `userId`・`title`・`details` だけ）。テナントは必ずサーバー側で決め、明細の `tenant_id` と `order_id` もサーバーが設定します。

`/api/me` は `tenantId`・`userId`・`subject`・`role` に加えて、`AccessScopeResolver` が算出した `visibleOrganizationIds`（昇順）を返します。部長・事業部長の範囲を確かめるのに使えます。

## RLS は @Transactional の中でだけ効く

`OrderService` はクラスに `@Transactional`、`UserService` はクラスに `@Transactional(readOnly = true)` を付けています。テナントとユーザーの設定はトランザクション開始時に DB に入るので、トランザクションの外で Mapper を呼ぶと何も見えません。クラス単位で付けているのは、メソッドごとの付け忘れを防ぐためです。参照系のメソッドには個別に `readOnly = true` を付けています。

## 一覧：ロールに応じて Mapper を選ぶ（多層防御の1枚目）

`listOrders` は、管理者なら `findAllByTenant(tenantId)`、それ以外なら `findAllByTenantAndScope(tenantId, userId, scope)` を呼びます。`findAllByTenantAndScope` は `tenant_id` と `(user_id = 自分 OR org_id IN (見てよい部署))` で絞り、一般ユーザーのように範囲が空なら `OR org_id IN ...` を付けません。

RLS も同じ条件を DB で足すので、アプリの WHERE が正しければ RLS は何も削らず、書き忘れても結果は同じになります。

## わざと残した危ない SQL

`OrderMapper` と `OrderDetailMapper` には、「現場によくある書き方」がそのまま入っています。RLS があれば、どれも他社のデータや見てはいけない人のデータを返しません。

| Mapper のメソッド | 問題 | RLS があるときの結果 |
|---|---|---|
| `OrderMapper#findAllWithoutTenantFilter` | WHERE 句の書き忘れ | 自分が見てよい行だけ |
| `OrderDetailMapper#findAllWithoutTenantFilter` | 子テーブルを直接、WHERE なしで SELECT | 見える受注の明細だけ |
| `OrderMapper#findById` | id だけで取得（IDOR） | 見えない行は 0 件 → 404 |
| `OrderMapper#updateTitle` | id だけで更新（IDOR） | 見えない行は 0 件更新 → 404 |
| `OrderMapper#deleteById` | id だけで削除（IDOR） | 見えない行は 0 件削除 → 404 |

`bypass` プロファイル（`BYPASSRLS` ロールで接続）で同じ API を呼ぶと、これらがそのまま他社・他人のデータを返します（[ローカルでの動かし方とデモ](../operations/local-development.md)）。

`UserMapper` も同じで、`findBySubject` と `findById` には `tenant_id` 条件がなく、テナントと範囲は RLS が絞ります。

## 作成：代理作成のチェック（アプリ）と WITH CHECK（DB）

`createOrder(userId, title, details)` の流れです。

1. 名義人は `userId`、省略したら自分
2. 一般ユーザーが他人を指定したら 403（`ForbiddenException`）
3. `UserMapper#findById` で名義人を引く。RLS により、範囲の外のユーザーは見つからないので 403
4. 自分以外の名義で、管理者でもなく、名義人の部署が自分の範囲に入っていなければ 403
5. 受注を `(tenant_id, 名義人, 名義人の今の部署, title)` で INSERT し、明細を続けて INSERT する

受注の `org_id` には名義人の **その時点の** 所属部署を記録します。名義人が後で異動しても、受注は元の部署のデータのままです（[組織階層による見える範囲](../authorization/org-hierarchy-scope.md)）。

アプリのチェックをすり抜けて Mapper を直接呼んでも、`orders` と `order_details` の `WITH CHECK` が最後の砦になり、他人名義・範囲の外の部署・見えない受注への明細は `row-level security policy` 違反で拒否されます。他テナントのユーザー名義は、複合外部キーの違反になります。

## 更新・削除・明細の追加

- `updateTitle` と `deleteOrder` は id だけで実行し、更新・削除件数が 0 なら `OrderNotFoundException`（404）
- `addDetail` は先に `findById` で親の受注を引き、見えなければ 404。見えれば親の `tenant_id` で明細を INSERT する
- 管理職は範囲の中の受注を、管理者はテナント内のすべての受注を、参照と同じように更新・削除・明細追加できる

## ユーザー一覧

`UserService#listUsers` は、管理者なら `findAllByTenant`、範囲が空でない管理職なら `findAllByOrganizations`（範囲の部署のメンバー）を返し、それ以外は 403 です。アプリのチェックをすり抜けて `findAllByTenant` を流しても、RLS の `users_select` により一般ユーザーには自分の行、部長には部員だけが見えます。

## 404 と 403 の使い分け

`ApiExceptionHandler` が、`OrderNotFoundException` を 404、`ForbiddenException` を 403 の `ProblemDetail` に変換します。

| ステータス | いつ返すか | 判定する場所 |
|---|---|---|
| 401 | トークンなし・署名が不正・期限切れ | Spring Security |
| 400 | 所属が複数なのに `X-Tenant-Id` がない、ヘッダが数値でない | `TenantFilter` |
| 403 | 所属していないテナントを指定、そのテナントの users に登録がない | `TenantFilter` |
| 403 | 一般ユーザーが他人名義で作成、範囲の外のメンバー名義で作成、一般ユーザーがユーザー一覧を取得 | `OrderService`、`UserService` |
| 404 | 他社・他人・範囲の外の **既存の行** を id で指定 | RLS で行が見えず、アプリが「存在しない」として返す |

403 を返すのは **自分の権限についての情報** だけです。他人の既存の行に 403 を返すと「その id の行は存在する」と相手に教えてしまうので、すべて 404 にしています。

## 確認しているテスト

`OrderRlsIntegrationTest`（テナントの壁）、`OrderRoleAccessTest`（一般と管理者）、`OrgScopeAccessTest`（部長・事業部長）、`TenantFilterWebTest`（HTTP 経由のステータス）が、上の挙動を確かめています。詳しくは [テスト：越境できないことの確認](../testing/rls-test-suite.md) を参照してください。
