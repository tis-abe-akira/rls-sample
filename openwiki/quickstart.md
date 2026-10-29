---
type: guide
title: クイックスタート
description: PostgreSQL の RLS と MyBatis で作ったマルチテナントの最小サンプルの入口。何を示すリポジトリか、技術スタック、ディレクトリ構成と、やりたいことごとにどのページ・ソースを見ればよいかをまとめる。
tags: [quickstart, overview, multi-tenancy, rls, navigation]
sources:
  - id: openwiki-source-2355f81d7cf522f8dbdaabd4
    resource: repo://pom.xml
  - id: openwiki-source-23775c3de52f3ab95a13cb8b
    resource: repo://README.md
generated: { by: "claude-code", at: "2026-10-29T20:48:55.949Z" }
verified:
  - by: openwiki/0.7.2
    at: 2026-10-29T20:48:55.949Z
---

# クイックスタート

**rls-sample** は、PostgreSQL の Row Level Security（RLS）でマルチテナントのデータを守る Spring Boot のサンプルです。「WHERE 句を書き忘れても、id だけで取りに来られても、他社のデータは返らない」を、コードとテストで体験できるように作られています。

テナント（企業）の中にも権限があり、一般ユーザーは自分のデータだけ、部長・事業部長は組織階層で配下の部署のデータ、管理者はテナント内の全員のデータを扱えます。組織階層の見える範囲は「判断はアプリ、強制は DB」で実現しています。

## 技術スタック

| 項目 | バージョン・内容 |
|---|---|
| 言語 | Java 21 |
| フレームワーク | Spring Boot 4.1.1（Web MVC、OAuth2 Resource Server） |
| 永続化 | MyBatis（mybatis-spring-boot-starter 4.0.0）、SQL は XML Mapper |
| DB | PostgreSQL 17（Docker Compose、`localhost:15432`） |
| 認証 | JWT（Bearer）。サンプルは HS256 の共有鍵、本番は IdP の公開鍵を想定 |
| ビルド | Maven |

## 最短で動かす

```bash
docker compose up -d
docker exec -i rls-sample-db psql -U app_user -d rls_sample < db/demo.sql   # SQL だけで RLS を体験
mvn test                                                                  # DB 起動が前提
mvn spring-boot:run
```

詳しい手順、JWT の発行、`bypass` プロファイルでの「RLS がない状態」の再現は [ローカルでの動かし方とデモ](operations/local-development.md) にあります。

## ディレクトリ構成

```
compose.yaml           PostgreSQL（rls-sample-db）
db/init/               接続ロール → スキーマと RLS ポリシー（本体）→ デモデータ
db/demo.sql            psql だけで挙動を見るスクリプト
scripts/token.sh       デモ用の JWT を発行する
docs/                  STORY（背景と設計の考え方）、AUTHORIZATION（処理の流れ）、ADR（設計判断）
src/main/java/com/example/rlssample/
  tenant/              認証設定、TenantFilter、ThreadLocal、TenantAwareTransactionManager
  user/                UserService、AccessScopeResolver、/api/me・/api/users
  org/                 組織階層から見てよい部署を算出する実装
  order/               受注・明細の Controller / Service / Mapper
  common/              例外と、404 / 403 のハンドラ
src/main/resources/    application.yml、bypass プロファイル、MyBatis の XML Mapper
src/test/java/...      実 DB に繋ぐ統合テストと、テスト用の Actor
```

## まず読むもの

1. [アーキテクチャ概要：多層防御とリクエストの流れ](architecture/overview.md) — 2段のポリシー、アプリと DB の二重チェック、1リクエストの流れ
2. [スキーマと RLS ポリシー](database/schema-and-rls-policies.md) — 守りの本体
3. [DB へのコンテキスト受け渡し](request-lifecycle/db-session-context.md) — アプリ側の本体

手を動かして仕組みを確かめたいなら、[チュートリアル：テナント所有のテーブルを1つ追加する](tutorials/add-tenant-owned-table.md) から始めるのがおすすめです。PostgreSQL 以外の DB で同じ守りを作りたいなら、[RLS を持たない DB（MySQL など）での代替手段](guides/databases-without-rls.md) を読んでください。

背景や設計の考え方は、リポジトリの `docs/STORY.md`（RLS の利点と限界、信頼の鎖、ベストプラクティス）、`docs/AUTHORIZATION.md`（シーケンス図）、`docs/ADR.md`（組織階層の判定をどこで行うか）にあります。

## やりたいことから探す

| やりたいこと | 見るページ | 主なソース |
|---|---|---|
| 全体の仕組みをつかむ | [アーキテクチャ概要](architecture/overview.md) | `README.md`、`docs/AUTHORIZATION.md` |
| テナントが決まる仕組み、401 / 400 / 403 の条件を知る | [テナントとユーザーの確定](request-lifecycle/tenant-and-user-resolution.md) | `tenant/TenantFilter.java`、`tenant/SecurityConfig.java`、`user/UserService.java` |
| テナントとユーザーが DB に届く仕組みを知る、非同期・バッチで使う | [DB へのコンテキスト受け渡し](request-lifecycle/db-session-context.md) | `tenant/TenantAwareTransactionManager.java`、`tenant/TenantContext.java`、`tenant/UserContext.java` |
| テーブルを追加して RLS をかける（手順を追って） | [チュートリアル：テナント所有のテーブルを1つ追加する](tutorials/add-tenant-owned-table.md) | `db/init/02_schema.sql`、`resources/mapper/OrderDetailMapper.xml`、`src/test/.../support/Actor.java` |
| ポリシーの全体像を知る | [スキーマと RLS ポリシー](database/schema-and-rls-policies.md) | `db/init/02_schema.sql` |
| MySQL など RLS を持たない DB で同じ守りを作る | [RLS を持たない DB（MySQL など）での代替手段](guides/databases-without-rls.md) | `tenant/TenantAwareTransactionManager.java`、`db/init/02_schema.sql`、`docs/STORY.md` |
| 部長・事業部長の範囲を変える、兼務・代理を足す、OpenFGA に置き換える | [組織階層による見える範囲](authorization/org-hierarchy-scope.md) | `user/AccessScopeResolver.java`、`org/OrganizationHierarchyScopeResolver.java`、`docs/ADR.md` |
| API を追加する、404 と 403 のどちらを返すか決める | [受注・ユーザー API と Mapper](domain/orders-and-users-api.md) | `order/OrderService.java`、`resources/mapper/*.xml`、`common/ApiExceptionHandler.java` |
| ローカルで動かす、JWT を作る、RLS なしと比べる | [ローカルでの動かし方とデモ](operations/local-development.md) | `compose.yaml`、`scripts/token.sh`、`application-bypass.yml` |
| テストを書く・読む | [テスト：越境できないことの確認](testing/rls-test-suite.md) | `src/test/.../support/Actor.java`、`OrgScopeAccessTest.java` |

## 押さえておきたい決まりごと

- アプリは必ず `app_user`（RLS が効くロール）で接続する。スーパーユーザーや `BYPASSRLS` のロールでは RLS が素通りする
- テナントの根拠は署名済み JWT のクレーム `tenant_ids` だけ。`X-Tenant-Id` ヘッダはその中から選ぶだけ
- ユーザー ID とロールは JWT ではなく、そのテナントの `users` テーブルから決める
- SQL は `@Transactional` の中で実行する。テナントとユーザーはトランザクション開始時に `SET LOCAL` で DB に入る
- トランザクションの途中で `runAs` によって別テナント・別ユーザーに切り替えない
- テナントの壁は必ず `AS RESTRICTIVE` で書く
- アプリ側の WHERE 句は消さない（多層防御）
- 他人の既存の行は 403 ではなく 404 にする

## 未実装のもの

README の「本番に向けて足すもの」と ADR の未決事項に、IdP の公開鍵での JWT 検証への切り替え、非同期処理・バッチへのコンテキストの伝播、兼務・代理・期間限定の権限、見てよい部署のキャッシュ、psql やバッチなどアプリを通らない経路で渡す範囲の決め方、テナントを横断する権限が挙がっています。
