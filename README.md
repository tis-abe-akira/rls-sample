# rls-sample — PostgreSQL RLS × MyBatis マルチテナント最小サンプル

Spring Boot 4.1.1 / MyBatis (mybatis-spring-boot-starter 4.0.0) / PostgreSQL 17 (Docker) / Java 21

「WHERE句を書き忘れても、id だけで取りに来られても、他社のデータは返らない」を体験するためのサンプルです。
テナントの中でも、**一般ユーザーは自分のデータだけ、部長・事業部長は組織階層で配下の部署のデータ、管理者はテナント内の全員のデータ**を扱えるようにしています。
組織階層の見える範囲は「判断はアプリ、強制は DB」で実現しています（[ADR.md](docs/ADR.md)）。

## 構成

```
compose.yaml                     PostgreSQL (localhost:15432)
db/init/01_roles.sql             接続ロールの分離（owner / app_user / app_bypass）
db/init/02_schema.sql            テーブル定義・RLSポリシー ← ★ここが本体
db/init/03_data.sql              A商事(1) / B工業(2) の組織・ユーザー・受注・明細
db/demo.sql                      psql だけで挙動を見るスクリプト
scripts/token.sh                 デモ用の JWT を発行するスクリプト
src/main/java/.../tenant/
  TenantId                       テナントIDの値オブジェクト
  TenantContext / UserContext    現在のテナント / ユーザー（ThreadLocal）
  CurrentUser / Role             テナントの中の「誰か」（ユーザーIDとロールと見てよい部署）
  AccessScope                    見てよい部署の一覧（app.visible_org_ids として DB に渡す）
  SecurityConfig                 JWT（Bearer トークン）の検証と TenantFilter の配置
  TenantFilter                   JWT とヘッダでテナントを、users テーブルでユーザーを確定
  TenantAwareTransactionManager  BEGIN直後に set_config を発行 ← ★アプリ側の本体
  TenantConfig                   上記を TransactionManager として登録
src/main/java/.../user/          ユーザー（/api/me, /api/users）と AccessScopeResolver（インターフェース）
src/main/java/.../org/           組織。OrganizationHierarchyScopeResolver（自前の AccessScopeResolver）
src/main/java/.../order/         受注・明細の Controller / Service / Mapper
src/main/resources/mapper/       正しいSQLと「よくある危ないSQL」
src/test/.../OrderRlsIntegrationTest   2テナント越境テスト（テナントの壁）
src/test/.../OrderRoleAccessTest       一般ユーザー / 管理者のテスト（テナントの中の権限）
src/test/.../OrgScopeAccessTest        部長 / 事業部長のテスト（組織階層による見える範囲）
src/test/.../OrganizationHierarchyScopeResolverTest   見てよい部署の算出
src/test/.../TenantFilterWebTest       JWT・ヘッダ・users テーブルによるテナントとユーザーの確定
```

## ドキュメント

| ドキュメント | 内容 |
|---|---|
| [docs/STORY.md](docs/STORY.md) | 背景、RLS の利点と限界、設計のベストプラクティス |
| [docs/AUTHORIZATION.md](docs/AUTHORIZATION.md) | 認可の処理の流れ（主要なクラスの連携をシーケンス図で説明） |
| [docs/ADR.md](docs/ADR.md) | 組織階層の見える範囲を「判断はアプリ、強制は DB」にした設計判断 |

## データモデル

```
companies ─┬─ organizations  (id, tenant_id, parent_id → organizations, name)   事業部 → 部 の階層
           ├─ users          (id, tenant_id, org_id → organizations, subject, name, role: GENERAL / MANAGER / ADMIN)
           ├─ orders         (id, tenant_id, user_id → users, org_id → organizations, title)
           └─ order_details  (id, tenant_id, order_id → orders, item, quantity, unit_price)
```

- 子テーブル（`order_details`）にも `tenant_id` を持たせて RLS をかける
- 外部キーは `(tenant_id, 親の id)` の複合にして、テナントをまたぐ関連を DB が作らせない
- 同じ人（同じ JWT の `sub`）でも、テナントごとに `users` の行とロールを持つ
- `orders.org_id` は受注を作った時点の名義人の部署。名義人が異動しても、受注は元の部署のデータのまま

```
A商事: 営業事業部(1) ─┬─ 第一営業部(2)
                      └─ 第二営業部(3)
       管理本部(4)
B工業: 製造事業部(5) ── 第一製造部(6)
```

| テナント | ユーザー（id） | 所属 | ロール | 見える受注 |
|---|---|---|---|---|
| A商事 (1) | alice (1) | 管理本部 | 管理者 | A商事の全部 |
| A商事 (1) | mika (9) | 営業事業部 | **事業部長**（MANAGER） | 営業事業部と配下の部 |
| A商事 (1) | ken (8) | 第一営業部 | **部長**（MANAGER） | 第一営業部 |
| A商事 (1) | bob (2) / taro (4) | 第一営業部 | 一般 | 自分の分 |
| A商事 (1) | carol (3) | 第二営業部 | 一般 | 自分の分 |
| B工業 (2) | dave (5) | 製造事業部 | 管理者 | B工業の全部 |
| B工業 (2) | erin (6) | 第一製造部 | 一般 | 自分の分 |
| B工業 (2) | taro (7) | 製造事業部 | **管理者**（同じ taro でもテナントごとにロールが違う） | B工業の全部 |

## 動かし方

```bash
docker compose up -d

# 1) まず SQL だけで RLS を体験
docker exec -i rls-sample-db psql -U app_user -d rls_sample < db/demo.sql

# 2) テスト（DBが起動している前提）
mvn test

# 3) アプリ起動
mvn spring-boot:run
```

スキーマやデモデータを変えたときは、DB を作り直します（初期化スクリプトは DB が空のときしか流れません）。

```bash
docker compose down -v && docker compose up -d
```

## API で試す

### 誰として動くか

1. **テナント**は JWT のクレーム `tenant_ids`（所属テナントの一覧）で決まります。`X-Tenant-Id` ヘッダはその中からどれを使うかを選ぶだけで、それ自体は信用しません
2. **ユーザーIDとロール**は、そのテナントの `users` テーブルを JWT の `sub` で引いて決めます（トークンには入れません）

| 状況 | 結果 |
|---|---|
| トークンなし・署名が不正・期限切れ | 401 |
| `tenant_ids` に入っていないテナントを `X-Tenant-Id` で指定 | **403**（なりすまし。WARN ログに記録） |
| 所属が複数なのに `X-Tenant-Id` なし | 400 |
| そのテナントの `users` に登録がない | 403 |

```bash
BOB=$(scripts/token.sh bob 1)        # A商事の一般ユーザー
ALICE=$(scripts/token.sh alice 1)    # A商事の管理者
TARO=$(scripts/token.sh taro 1 2)    # A商事では一般、B工業では管理者

curl -s localhost:8080/api/me -H "Authorization: Bearer $BOB"
# → {"tenantId":1,"userId":2,"subject":"bob","role":"GENERAL"}
```

### 一般ユーザーと管理者

```bash
# 一般ユーザー: WHERE句を書き忘れた実装でも、自分の受注だけ
curl -s localhost:8080/api/orders/leaky -H "Authorization: Bearer $BOB"

# 管理者: WHERE句を書き忘れた実装でも、A商事の全員の受注だけ（B工業は出ない）
curl -s localhost:8080/api/orders/leaky -H "Authorization: Bearer $ALICE"

# 一般ユーザーが同じ会社の他人の受注(id=2 はキャロルの受注)を取得 → 404
curl -i localhost:8080/api/orders/2 -H "Authorization: Bearer $BOB"

# 管理者は他人の受注も明細付きで取得・更新できる
curl -s localhost:8080/api/orders/1 -H "Authorization: Bearer $ALICE"
curl -s -X PUT localhost:8080/api/orders/1 -H "Authorization: Bearer $ALICE" \
     -H 'Content-Type: application/json' -d '{"title":"A商事 ノートPC調達（ボブ・承認済み）"}'

# 一般ユーザーが他人名義で受注を作る → 403
curl -i -X POST localhost:8080/api/orders -H "Authorization: Bearer $BOB" \
     -H 'Content-Type: application/json' -d '{"userId":3,"title":"なりすまし"}'

# 一般ユーザーがユーザー一覧 → 403 / 管理者 → A商事の6人
curl -i localhost:8080/api/users -H "Authorization: Bearer $BOB"
curl -s localhost:8080/api/users -H "Authorization: Bearer $ALICE"

# 同じ taro でも、B工業を選ぶと管理者として B工業の全員の受注が見える
curl -s localhost:8080/api/orders/leaky -H "Authorization: Bearer $TARO" -H 'X-Tenant-Id: 2'
```

### 部長・事業部長（組織階層）

```bash
KEN=$(scripts/token.sh ken 1)      # 第一営業部の部長
MIKA=$(scripts/token.sh mika 1)    # 営業事業部の事業部長

# /me に、アプリが算出した「見てよい部署」が出る → ken は [2]、mika は [1,2,3]
curl -s localhost:8080/api/me -H "Authorization: Bearer $MIKA"

# WHERE句を書き忘れた実装でも、範囲の中だけ（ken: 第一営業部 / mika: 事業部と配下の部）
curl -s localhost:8080/api/orders/leaky -H "Authorization: Bearer $KEN"
curl -s localhost:8080/api/orders/leaky -H "Authorization: Bearer $MIKA"

# 部長は範囲の外（第二営業部のキャロルの受注 id=2）は見えない → 404。事業部長は見える
curl -i localhost:8080/api/orders/2 -H "Authorization: Bearer $KEN"
curl -s localhost:8080/api/orders/2 -H "Authorization: Bearer $MIKA"

# 部長は部員（ボブ）の名義で代理作成できる。範囲の外（キャロル）の名義では 403
curl -s -X POST localhost:8080/api/orders -H "Authorization: Bearer $KEN" \
     -H 'Content-Type: application/json' -d '{"userId":2,"title":"ボブの代理発注"}'
curl -i -X POST localhost:8080/api/orders -H "Authorization: Bearer $KEN" \
     -H 'Content-Type: application/json' -d '{"userId":3,"title":"範囲外"}'

# ユーザー一覧: 部長は部員、事業部長は事業部のメンバー
curl -s localhost:8080/api/users -H "Authorization: Bearer $KEN"
```

### テナントの壁

```bash
# なりすまし: A商事のユーザーがヘッダで B工業を指定 → 403
curl -i localhost:8080/api/orders/leaky -H "Authorization: Bearer $ALICE" -H 'X-Tenant-Id: 2'

# IDOR: 管理者でも、B工業の受注(id=3)は取得・削除できない → 404（消えない）
curl -i localhost:8080/api/orders/3 -H "Authorization: Bearer $ALICE"
curl -i -X DELETE localhost:8080/api/orders/3 -H "Authorization: Bearer $ALICE"
```

### 登録

```bash
# tenant_id はリクエストに含めない（サーバー側で決める）。userId を省略すると自分名義
curl -s -X POST localhost:8080/api/orders -H "Authorization: Bearer $BOB" \
     -H 'Content-Type: application/json' \
     -d '{"title":"ボブの消耗品","details":[{"item":"ボールペン","quantity":10,"unitPrice":100}]}'

# 明細の追加（親の受注が見えなければ 404）
curl -s -X POST localhost:8080/api/orders/1/details -H "Authorization: Bearer $BOB" \
     -H 'Content-Type: application/json' -d '{"item":"USBハブ","quantity":3,"unitPrice":2500}'
```

`scripts/token.sh` は `application.yml` の `app.jwt.secret`（HS256 の共有鍵）で署名するデモ用のスクリプトです。
本番ではトークンは IdP が発行し、アプリは IdP の公開鍵で検証します（`SecurityConfig` の `JwtDecoder` を消して
`spring.security.oauth2.resourceserver.jwt.issuer-uri` を設定するだけです）。

### 「RLS が無かったら」を再現する

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=bypass
curl -s localhost:8080/api/orders/leaky -H "Authorization: Bearer $BOB"   # ← B工業の行も、他人の行も出る
curl -s localhost:8080/api/orders/3     -H "Authorization: Bearer $BOB"   # ← 他社の受注が読める
```

`BYPASSRLS` を持つロールで接続しているだけで、コードは同じです。
今の現場の状態がこれ、RLS を入れた状態がデフォルトプロファイル、という比較になります。

## 仕組み（1リクエストの流れ）

1. Spring Security が JWT の署名と有効期限を検証する
2. `TenantFilter` がクレームとヘッダからテナントを確定し、そのテナントの `users` を `sub` で引いてユーザーIDとロールを確定する。管理職なら `AccessScopeResolver` が組織階層から「見てよい部署」を算出する。これらを `TenantContext` / `UserContext` に入れる
3. `@Transactional` なサービスメソッドが呼ばれ、トランザクション開始
4. `TenantAwareTransactionManager#prepareTransactionalConnection` が、同じ接続で
   `app.tenant_id` / `app.subject` / `app.user_id` / `app.role` / `app.visible_org_ids` を `set_config(..., true)` で設定
5. MyBatis の SQL はそのまま実行。RLS ポリシーが暗黙に条件を付加する
   - **RESTRICTIVE**（テナントの壁）: `tenant_id = current_tenant_id()` … どのポリシーにも AND で掛かる
   - **PERMISSIVE**（テナントの中の権限）: `user_id = 自分 OR org_id = ANY(見てよい部署) OR 管理者`。DB は階層をたどらず、渡された一覧と比べるだけ
   - 明細は「親の受注が見えるか」で決まる
6. COMMIT / ROLLBACK で設定は自動的に消える（コネクションプールに残らない）

## ハマりどころ（このサンプルで対処済み）

| 落とし穴 | 何が起きるか | 対処 |
|---|---|---|
| アプリがスーパーユーザーで接続 | RLS が一切効かない | `app_user` を分ける。Spring Boot の Docker Compose 連携は `POSTGRES_USER`（スーパーユーザー）で繋ぐので使っていない |
| テーブル所有者で接続 | `FORCE` なしだと素通り | `ALTER TABLE ... FORCE ROW LEVEL SECURITY` |
| `SET`（セッション単位）で設定 | プールで次のリクエストに値が残り、**別テナント・別ユーザーとして動く** | `set_config(..., true)` = `SET LOCAL` |
| 一度 SET LOCAL した接続 | 以後 `current_setting` が NULL ではなく `''` を返し `''::bigint` でエラー | `NULLIF(..., '')` を関数に閉じ込める |
| テナントの壁を PERMISSIVE で書く | あとから足した PERMISSIVE ポリシーと OR で効き、**他テナントが見える** | テナントの壁は `AS RESTRICTIVE` |
| 子テーブルに `tenant_id` がない | 子テーブルを直接 SELECT されると全件見える | `order_details` にも `tenant_id` と RLS。外部キーは `(tenant_id, order_id)` の複合 |
| `@Transactional` の付け忘れ | テナント未設定 → 0件（気付きにくいが漏れはしない） | サービスクラスに `@Transactional`。テストで検知 |
| トランザクションの途中で `runAs` で別テナント・別ユーザーに切り替える | Java 側だけ切り替わり、DB 側は前の値のまま動く | `runAs` が例外を投げる。`runAs` → その内側でトランザクション |
| テストに `@Transactional` を付ける | テナント設定前にトランザクションが始まる | テストでは付けない（`runAs` を使う） |

## アプリ側の WHERE 句は消してよい？

消さないことを推奨します（多層防御）。このサンプルも、一般ユーザーなら `tenant_id` と `user_id`、管理職なら `tenant_id` と「自分または見てよい部署」、管理者なら `tenant_id` で絞っています。

- `bypass` ロールで動くバッチ・管理機能では RLS が効かない
- RLS 設定ミス（ポリシー付け忘れのテーブル）があってもアプリ側で止まる
- `tenant_id` 条件があるほうがインデックスを使った実行計画になりやすい

## 本番に向けて足すもの

- JWT の検証を共有鍵から **IdP の公開鍵**（`issuer-uri`）に切り替える
- テナント所有テーブル全部に同じ形のポリシーを付ける（RESTRICTIVE のテナントの壁 + PERMISSIVE の権限）
- 「RLS が有効になっていないテナント所有テーブル」を検出するチェック:
  ```sql
  SELECT c.relname
    FROM pg_class c
    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id'
   WHERE c.relkind = 'r' AND (NOT c.relrowsecurity OR NOT c.relforcerowsecurity);
  ```
- 非同期処理・バッチへの `TenantContext` / `UserContext` 伝播（`runAs` で明示的に）
- 兼務・代理・期間限定の権限（`AccessScopeResolver` の実装を拡張するか、OpenFGA に置き換える。[ADR.md](docs/ADR.md)）
- テナントを横断する Root 権限（未実装）
