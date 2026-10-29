---
type: runbook
title: ローカルでの動かし方とデモ
description: Docker Compose で PostgreSQL を起動し、psql のデモ・テスト・アプリ起動・JWT を使った API 呼び出しを試す手順。bypass プロファイルで「RLS がない状態」を再現する方法と、デモデータの ID もまとめる。
tags: [operations, local-development, docker-compose, demo, jwt]
verified:
  - by: openwiki/0.7.2
    at: 2026-10-29T20:28:49.941Z
sources:
  - id: openwiki-source-e201e686a785f09b6d899f0b
    resource: repo://compose.yaml
  - id: openwiki-source-3a35905b29fcda507ba489b8
    resource: repo://db/demo.sql
  - id: openwiki-source-aa9087ccfc3521e4e266024e
    resource: repo://db/init/01_roles.sql
  - id: openwiki-source-6683b82fd292f25acf3a7cd0
    resource: repo://db/init/03_data.sql
  - id: openwiki-source-56200b2ec4846ecd793d0b93
    resource: repo://scripts/token.sh
  - id: openwiki-source-6af5c113a2187996939f4fc3
    resource: repo://src/main/resources/application-bypass.yml
  - id: openwiki-source-08039bc60652747dc83eb8ca
    resource: repo://src/main/resources/application.yml
generated: { by: "claude-code", at: "2026-10-29T20:28:49.941Z" }
---

# ローカルでの動かし方とデモ

## 前提

- Java 21、Maven（`pom.xml` は Spring Boot 4.1.1 の parent を使う。Maven Wrapper は含まれていない）
- Docker（PostgreSQL 17 のコンテナを使う）
- `openssl`（`scripts/token.sh` が JWT の署名に使う）

## DB の起動と作り直し

```bash
docker compose up -d
```

`compose.yaml` は `postgres:17` を `rls-sample-db` という名前で起動し、ホストの `15432` 番ポートに公開します。`POSTGRES_USER` の `owner` はスーパーユーザー兼テーブル所有者で、DDL 専用です。`./db/init` が `/docker-entrypoint-initdb.d` に読み取り専用でマウントされ、DB が空のときだけ `01_roles.sql` → `02_schema.sql` → `03_data.sql` の順に実行されます。

初期化スクリプトは DB が空のときしか流れないので、スキーマやデモデータを変えたら DB を作り直します。

```bash
docker compose down -v && docker compose up -d
```

ロールやポリシーの中身は [スキーマと RLS ポリシー](../database/schema-and-rls-policies.md) を参照してください。

## psql だけで RLS を体験する

```bash
docker exec -i rls-sample-db psql -U app_user -d rls_sample < db/demo.sql
```

`db/demo.sql` は、アプリがトランザクション開始時に行う `set_config('app.*', ..., true)` を手で打ち、次の15ステップを順に見せます。

- 何も設定しないと WHERE なしでも 0 件、テナントだけ設定してもユーザー未解決なら 0 件（fail-closed）
- 管理者 alice でも B工業の行は id 指定（IDOR）でも UPDATE / DELETE でも 0 件
- 一般ユーザー bob は受注・明細・users とも自分の分だけ。他人名義の INSERT や他人の受注への明細追加は `WITH CHECK` 違反
- 部長 ken（`{2}`）・事業部長 mika（`{1,2,3}`）は `app.visible_org_ids` の範囲だけ。範囲の外の部署の受注は作れない
- トランザクションが終わると設定が消えて再び 0 件

## テストとアプリの起動

```bash
mvn test              # DB が起動している前提
mvn spring-boot:run   # localhost:8080
```

テストはすべて実際の DB に接続します。内容は [テスト：越境できないことの確認](../testing/rls-test-suite.md) を参照してください。

`application.yml` の主な設定です。

| 設定 | 値 | 意味 |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:15432/rls_sample` | Compose の DB |
| `spring.datasource.username` | `app_user` | RLS が効くアプリ用ロール。`owner` で繋ぐと RLS は素通りする |
| `app.jwt.secret` | デモ用の HS256 共有鍵（32 バイト以上） | `SecurityConfig` の `JwtDecoder` と `scripts/token.sh` が同じ値を使う |
| `mybatis.mapper-locations` | `classpath:mapper/*.xml` | SQL 本体の場所 |
| `logging.level` | `OrderMapper` と `TenantAwareTransactionManager` を DEBUG | 発行 SQL と、トランザクション開始時の `set_config` をログで確認できる |

## JWT を発行して API を呼ぶ

```bash
BOB=$(scripts/token.sh bob 1)          # sub=bob, tenant_ids=[1]
TARO=$(scripts/token.sh taro 1 2)      # 2社に所属
curl -s localhost:8080/api/me -H "Authorization: Bearer $BOB"
curl -s localhost:8080/api/orders/leaky -H "Authorization: Bearer $TARO" -H 'X-Tenant-Id: 2'
```

`scripts/token.sh SUBJECT TENANT_ID [TENANT_ID...]` は、`sub`・`tenant_ids`・`iat`・`exp`（1時間後）を持つ HS256 の JWT を出力します。鍵は環境変数 `JWT_SECRET`、なければ `application.yml` と同じ既定値です。ユーザー ID とロールはトークンに入れず、アプリが `users` テーブルで決めます。subject とテナント ID の形式はスクリプトが検証します。

本番ではトークンは IdP が発行し、`SecurityConfig` の `JwtDecoder` Bean を消して `spring.security.oauth2.resourceserver.jwt.issuer-uri` を設定します（[テナントとユーザーの確定](../request-lifecycle/tenant-and-user-resolution.md)）。

README の「API で試す」に、一般・管理者・部長・事業部長・テナントの壁・登録のそれぞれの `curl` 例があります。

## 「RLS がなかったら」を再現する

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=bypass
```

`application-bypass.yml` は接続ロールを `app_bypass`（`BYPASSRLS`）に変えるだけで、コードは同じです。この状態で `/api/orders/leaky` を呼ぶと他社・他人の受注が、`/api/orders/3` を一般ユーザーで呼ぶと他社の受注が返ります。デフォルトプロファイルと比べると、RLS が何を守っているかが分かります。

## デモデータ

`03_data.sql` が投入する2社分のデータです。テストはこの ID を前提にしています。

```
A商事(1): 営業事業部(1) ─┬─ 第一営業部(2)
                         └─ 第二営業部(3)
          管理本部(4)
B工業(2): 製造事業部(5) ── 第一製造部(6)
```

| テナント | subject（users.id） | 所属 | ロール |
|---|---|---|---|
| A商事 | alice (1) | 管理本部 | ADMIN |
| A商事 | bob (2) | 第一営業部 | GENERAL |
| A商事 | carol (3) | 第二営業部 | GENERAL |
| A商事 | taro (4) | 第一営業部 | GENERAL |
| A商事 | ken (8) | 第一営業部 | MANAGER（部長） |
| A商事 | mika (9) | 営業事業部 | MANAGER（事業部長） |
| B工業 | dave (5) | 製造事業部 | ADMIN |
| B工業 | erin (6) | 第一製造部 | GENERAL |
| B工業 | taro (7) | 製造事業部 | ADMIN |

同じ `taro` でも、A商事では一般ユーザー、B工業では管理者です。

| 受注 id | テナント | 名義人 | 部署 |
|---|---|---|---|
| 1 | A商事 | bob | 第一営業部 |
| 2 | A商事 | carol | 第二営業部 |
| 3 | B工業 | erin | 第一製造部 |
| 4 | B工業 | dave | 製造事業部 |
| 5 | A商事 | alice | 管理本部 |
| 6 | A商事 | ken | 第一営業部 |
