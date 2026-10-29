---
type: guide
title: RLS を持たない DB（MySQL など）での代替手段
description: このサンプルが頼っている PostgreSQL 固有の機能を整理し、MySQL・MariaDB のように RLS を持たない DB でテナントを守る方法（アプリケーションで条件を自動で足す、ビューで絞る、物理的に分ける）を比べる。そのまま使える部品と作り直しが要る部品、セッション変数がプールに残る危険と対策もまとめる。
tags: [guide, mysql, mariadb, multi-tenancy, alternatives, rls]
verified:
  - by: openwiki/0.7.2
    at: 2026-10-29T22:35:43.342Z
sources:
  - id: openwiki-source-aa9087ccfc3521e4e266024e
    resource: repo://db/init/01_roles.sql
  - id: openwiki-source-ae9507c4a442eab54c70e7da
    resource: repo://db/init/02_schema.sql
  - id: openwiki-source-30c0f5ae480b178275a17c19
    resource: repo://src/main/java/com/example/rlssample/tenant/TenantAwareTransactionManager.java
  - id: openwiki-source-0f66bbd7322788d6430980c4
    resource: repo://src/main/java/com/example/rlssample/tenant/TenantContext.java
  - id: openwiki-source-10bda4bd95dee902f5665545
    resource: repo://src/main/java/com/example/rlssample/tenant/TenantFilter.java
  - id: openwiki-source-5ce0de725bd181b053719e17
    resource: repo://src/main/java/com/example/rlssample/user/AccessScopeResolver.java
  - id: openwiki-source-2bd85f9faf1fb7e8210d7fd0
    resource: repo://src/main/resources/mapper/OrderMapper.xml
generated: { by: "claude-code", at: "2026-10-29T22:35:43.342Z" }
---

# RLS を持たない DB（MySQL など）での代替手段

このサンプルの DB 側のアクセス制御は、PostgreSQL の Row Level Security（RLS）でできています。MySQL などの RLS を持たない DB に移すと、その仕組みを別の方法で作り直す必要があります。このページは、何が PostgreSQL に依存していて、代わりに何を選べるかを整理したものです。

> このページの MySQL・MariaDB・各ライブラリの話は、**このリポジトリには含まれていない** 一般的な情報で、執筆時点のものです。採用する前に、各製品の公式ドキュメントで確かめてください。

## このサンプルが頼っている PostgreSQL の機能

| 機能 | 使っている場所 | 役割 |
|---|---|---|
| `CREATE POLICY` | `db/init/02_schema.sql` | 行ごとに「見える」「書ける」条件を DB に持たせる |
| `AS RESTRICTIVE` | 各テーブルの `tenant_isolation` | テナントの壁を、ほかのポリシーと AND で必ず効かせる |
| `ENABLE` / `FORCE ROW LEVEL SECURITY` | テナント所有の4テーブル | テーブル所有者にも RLS を効かせる |
| `set_config(..., true)` と `current_setting` | `TenantAwareTransactionManager` とヘルパー関数 | テナントとユーザーを、トランザクションの中だけ有効な設定として DB に渡す |
| `BYPASSRLS` | `app_bypass` ロール | バッチ・運用用に RLS を素通りさせる（デモでは「RLS がない状態」の再現） |

このうち、とくに代わりを見つけにくいのは次の2つです。

- **DB が SQL に条件を足すこと**（`CREATE POLICY`）。アプリケーションのコードを経由しない SQL にも効く
- **トランザクションの終わりで設定が消えること**（`set_config` の第3引数 `true` = `SET LOCAL`）。コネクションプールに値が残らない

## MySQL・MariaDB には RLS がない

執筆時点で、MySQL と MariaDB には PostgreSQL の `CREATE POLICY` に当たる、テーブルの行レベルのセキュリティ機能はありません。AWS のブログも「MySQL（Aurora MySQL を含む）は、テーブルの行レベルのセキュリティをネイティブではサポートしていない」としたうえで、ビューとトリガーで代わりにする方法を紹介しています。

参考までに、RLS に当たる機能を持つ DB もあります。SQL Server と Azure SQL の Row-Level Security（セキュリティポリシー）、Oracle の Virtual Private Database（VPD）などです。これらに移すなら、このサンプルと同じ「DB 側でアクセス制御を強制する」考え方をそのまま使えます。

## 代替手段

### A. アプリケーションで条件を自動で足す

SQL を DB に送る前に、アプリケーションが `tenant_id = ?` の条件を自動で足します。

- **MyBatis-Plus の `TenantLineInnerInterceptor`**: 実行前の SQL を JSqlParser で解析し、`tenant_id` の条件（INSERT なら列と値）を足す
- **自作の MyBatis `Interceptor`**: 素の MyBatis には SQL を書き換える割り込み口しかないので、SQL の解析まで自分で作ることになり、大きな労力がかかる
- **Hibernate の `@TenantId`**: エンティティの列にテナントを持たせ、Hibernate が条件を足す

`docs/STORY.md` のコラム「MyBatis にも同じような機能はないのか」に、MyBatis-Plus と RLS の詳しい比較があります。要点は、**条件を足すのがアプリケーションか DB か** の違いです。アプリケーションが足す方式では、MyBatis を通らない SQL（`JdbcTemplate`、手作業の SQL、別のアプリケーション）には効きません。また、アノテーションや設定1つで対象から外せてしまうので、レビューで必ず確認する必要があります。

### B. ビューで絞る

アプリケーション用の DB ユーザーには元のテーブルへの権限を与えず、テナントで絞ったビューだけを GRANT します。

```sql
-- 例（このリポジトリには含まれていない）
CREATE FUNCTION current_tenant_id() RETURNS BIGINT DETERMINISTIC NO SQL
  RETURN @app_tenant_id;

CREATE SQL SECURITY DEFINER VIEW v_orders AS
  SELECT * FROM orders WHERE tenant_id = current_tenant_id();
```

MySQL のビューの定義では、ユーザー変数（`@app_tenant_id`）を直接参照できません。そのため、変数を返すストアド関数をはさむのが定番の書き方です。

- 良いところ: アプリケーション用のユーザーがビューしか触れなければ、MyBatis を通らない SQL にも効く
- 難しいところ: 書き込みの扱いです。単純なビューなら更新可能ビューと `WITH CHECK OPTION` で絞れる場合もありますが、制約が多く、AWS のブログではトリガーと組み合わせています。テーブルを増やすたびにビュー・関数・権限をそろえる手間もかかります
- 「テナントの中の権限」（自分の行だけ、見てよい部署だけ）まで条件に入れると、ビューが複雑になります

### C. 物理的に分ける

テナントごとに DB（MySQL では DB とスキーマは同じもの）を分け、リクエストごとに接続先を切り替えます。Spring では `AbstractRoutingDataSource` を使い、`TenantContext` のテナントで接続先を選ぶ形になります。

- 良いところ: 他テナントのデータは、そもそも同じ接続から見えない。WHERE の書き忘れも IDOR も、テナントの壁については起きない
- 難しいところ: テナントの数だけ DB・接続プール・マイグレーションが増える。テナントをまたぐ集計が難しい
- テナントの **中** の権限（一般・管理職・管理者）は守れないので、A などと組み合わせる必要がある

### 比べると

| 観点 | A. アプリケーションで自動で足す | B. ビューで絞る | C. 物理的に分ける |
|---|---|---|---|
| WHERE の書き忘れ・IDOR（テナントの壁） | ✅ 防げる | ✅ 防げる | ✅ 防げる |
| テナントの中の権限 | ⚠️ 自分で条件を足す | ⚠️ ビューが複雑になる | ❌ 別の方法が要る |
| MyBatis を通らない SQL | ❌ 素通り | ✅ 効く（ビューしか触れなければ） | ✅ 接続が分かれている |
| 書き込みの守り | ✅ INSERT にも列を足せる | ⚠️ 難しい（トリガーなど） | ✅ |
| 運用の負担 | 小（ライブラリの導入とレビュー） | 中（ビュー・関数・権限の保守） | 大（DB とプールがテナントの数だけ） |

## このリポジトリの部品はどこまで使えるか

### そのまま使えるもの

| 部品 | 理由 |
|---|---|
| `TenantFilter` | テナントを JWT のクレームで決め、ユーザーを `users` テーブルで決める処理は DB に依存しない |
| `TenantContext` / `UserContext` | ThreadLocal と `runAs` だけでできている。トランザクション途中の切り替えを禁止する考え方も同じように必要 |
| `AccessScopeResolver` | 「見てよい部署」を Java で算出する部分。再帰 CTE は MySQL 8.0 以降でも書ける |
| アプリケーション側の WHERE（`findAllByTenantAndScope` など） | RLS がない DB では、これが唯一の制御点になる。RLS があるとき以上に大事 |
| テストの考え方 | 2テナント以上のデータで「見えない・作れない・消せない」を確かめる。`Actor` で動く人を切り替える |

### 作り直しが要るもの

| 部品 | 理由 |
|---|---|
| RLS ポリシー（`02_schema.sql`） | MySQL に `CREATE POLICY` はない。A〜C のどれかで置き換える |
| `TenantAwareTransactionManager` | `set_config(..., true)` は PostgreSQL の関数。B の方式で変数を渡すなら、`SET @app_tenant_id = ?` などに書き換え、後で説明する後始末が必要 |
| `app_user` / `app_bypass` のロール分け | `BYPASSRLS` はない。B ならビューだけに権限を持つユーザーと、元のテーブルに権限を持つユーザーに分ける |
| `bypass` プロファイルのデモ | 「RLS がない状態」との比較はできなくなる |

## 注意：セッション変数はプールに残る

PostgreSQL の `set_config(..., true)` は、トランザクションが終わると自動で消えます。このサンプルが `TenantAwareTransactionManager` の1か所で値を渡すだけで済んでいるのは、この性質のおかげです（[DB へのコンテキスト受け渡し](../request-lifecycle/db-session-context.md)）。

MySQL のユーザー変数（`SET @app_tenant_id = 1`）は **接続（セッション）が続く限り残ります**。トランザクションの終わりで消える仕組みはありません。コネクションプールは接続を使い回すので、後始末を忘れると **次のリクエストが前のテナント・前のユーザーとして動きます**。これは「見えない」側ではなく「漏れる」側の失敗です。

対策は次のとおりです。

- トランザクションの開始時に必ず全部の変数を設定し直す（設定しない変数を残さない）
- トランザクションの終わり（COMMIT でも ROLLBACK でも）に、必ず変数を消す。Spring なら TransactionManager の後始末（たとえば `doCleanupAfterCompletion`）で `SET @app_tenant_id = NULL` を流す
- 接続をプールに返すときに、セッションの状態を初期化できるか、使っているプールとドライバーの設定を確かめる
- 変数が NULL のときに 0 件になる（fail-closed）ように、関数とビューの条件を書く
- 「前のリクエストの値が残っていないこと」を確かめるテストを書く（同じ接続を続けて使い、2回目に値を設定しなかったら何も見えないこと）

## おすすめの組み合わせ

1. **テナントの決定は、このサンプルと同じにする。** `TenantFilter` のように、署名済み JWT のクレームでテナントを決め、ヘッダは選ぶだけにする（[テナントとユーザーの確定](../request-lifecycle/tenant-and-user-resolution.md)）
2. **1枚目（アプリケーション）:** 手書きの `WHERE tenant_id = ...` を残し、できれば MyBatis-Plus のような自動の条件追加も入れる。対象から外す設定はレビューで必ず確認する
3. **2枚目（DB）:** テナントの壁は、規模が許せば C（物理的に分ける）、そうでなければ B（ビュー）で作る。B を選ぶなら、セッション変数の後始末をテストで守る
4. **MyBatis を通らない経路**（バッチ、手作業の SQL）には、その経路でテナントをどう決めるかを別に決める

どの方式でも、**テナント ID そのものが偽物なら、偽物のテナントとして正しく動いてしまいます**。DB 側の制御点が RLS でもビューでも物理的な分離でも、「テナントが正しく決まること」は `TenantFilter` のような入口の守りでしか保証できません。これは `docs/STORY.md` の第6章（信頼の鎖）と同じ話です。
