# OpenWiki の方針（rls-sample）

この Wiki を生成・更新するエージェントへの指示です。OpenWiki はこのファイルを書き換えません。
Claude Code などの連携から生成するときは、`openwiki_begin` が返す `wikiGoal` にこのファイルの内容が入るので、計画を立てる前に必ず読んでください。

## 読者と目的

- 読者は、このサンプルを読んで自分のシステムにマルチテナントの守りを入れたい開発者と、このリポジトリを扱う AI エージェント
- 目的は「なぜこの形なのか」と「どこを触れば何が変わるか」を、ソースとテストを根拠に説明すること
- `docs/STORY.md`・`docs/AUTHORIZATION.md`・`docs/ADR.md` の内容を書き写さない。要点だけ書き、詳しくはそちらへ誘導する

## 書き方

- 日本語で書く。クラス名・メソッド名・SQL・設定キーなどの識別子は原文のまま
- 1文を短くする。体言止めや箇条書きだけの説明にせず、理由（なぜそうするか）を添える
- コードの事実は必ずソースで確かめる。行番号や ID を推測で書かない
- 「アプリ」ではなく「アプリケーション」と書く（「アプリ」はスマホアプリを連想させ、誤読の原因になる）
- 「関所」のような比喩は使わず、「制御点」「アクセス制御」などの専門用語で書く
- 根拠のない数（「数百本の SQL」など）や、決めつける言い方（「現実的ではない」「必ず事故が起こる」など）を避ける
- リポジトリにないもの（例・提案・他の DB の話）は、「このリポジトリには含まれていない」と分かるように書く。Claims にはしない

## ページ構成

既存のページ構成は保つ。次の2ページを追加し、`quickstart.md` の「やりたいことから探す」の表にも行を足す。

### 1. チュートリアル：テナント所有のテーブルを1つ追加する

- パス: `/openwiki/tutorials/add-tenant-owned-table.md`
- 題材: 受注に紐づく「コメント」（`order_comments`）のような、小さなテナント所有テーブルを1つ足す。題材はこのリポジトリの既存の部品だけで作れる範囲にする
- Step by Step で、各ステップに「何をするか」「なぜ必要か」「忘れると何が起きるか」を書く。最低限、次の流れを含める
  1. テーブル定義: `tenant_id` を持たせ、外部キーを `(tenant_id, 親の id)` の複合にする
  2. `ENABLE` と `FORCE ROW LEVEL SECURITY`
  3. `AS RESTRICTIVE` のテナントの壁と、PERMISSIVE の権限（親の受注が見えるか、で決める `order_details` と同じ形）
  4. `GRANT`
  5. デモデータ（既存の ID をずらさないよう末尾に追加する）と、DB の作り直し
  6. Mapper と Service（`@Transactional` の中で使う。リクエストに `tenant_id` を含めない）
  7. テスト: 2テナント以上で「見えない・作れない・消せない」を確かめる。`Actor` を使い、テストに `@Transactional` を付けない
  8. README にある「RLS が有効になっていないテナント所有テーブル」の検出クエリで確認する
- コード例は「チュートリアル用の例で、リポジトリには含まれていない」と明記する。例の書き方は既存の `02_schema.sql`・Mapper XML・テストに合わせる
- 最後に、つまずきやすい点（`FORCE` の付け忘れ、テナントの壁を PERMISSIVE で書く、子テーブルに `tenant_id` がない、テストに `@Transactional`）をまとめる
- Claims は、チュートリアルが前提にしている既存の仕組み（`order_details` のポリシーの形、検出クエリ、`Actor` など）について書く

### 2. RLS を持たない DB（MySQL など）での代替手段

- パス: `/openwiki/guides/databases-without-rls.md`
- 前提として、PostgreSQL のどの機能にこのサンプルが依存しているかを最初に整理する（`CREATE POLICY`、`AS RESTRICTIVE`、`FORCE ROW LEVEL SECURITY`、`set_config(..., true)`、`BYPASSRLS`）
- MySQL・MariaDB にネイティブの RLS がないことを書く。執筆時点の情報であることと、公式ドキュメントで確かめるよう添える。参考に、RLS を持つ DB（SQL Server、Oracle の VPD など）にも一言触れてよい
- 代替手段を比較する。少なくとも次を含め、それぞれ「WHERE の書き忘れ・IDOR を防げるか」「MyBatis を通らない SQL に効くか」「運用の負担」を比べる
  - アプリケーション側で条件を自動で足す: MyBatis-Plus の `TenantLineInnerInterceptor`、自作の MyBatis `Interceptor`、Hibernate の `@TenantId`（`docs/STORY.md` のコラムと矛盾させない）
  - ビューで絞る: セッション変数を返すストアド関数を使ったビューだけをアプリケーションのユーザーに GRANT する方法。書き込みの扱いが難しいことも書く
  - 物理的に分ける: テナントごとの DB / スキーマと、Spring の `AbstractRoutingDataSource` による接続の切り替え
- このリポジトリの部品のうち、DB を変えてもそのまま使えるもの（`TenantFilter`、`TenantContext` / `UserContext`、`AccessScopeResolver`、アプリケーション側の WHERE、テストの考え方）と、作り直しが要るもの（RLS ポリシー、`TenantAwareTransactionManager`）を分けて書く
- 特に注意として、MySQL のセッション変数には `SET LOCAL` に当たる「トランザクションの終わりで消える」仕組みがないので、コネクションプールに値が残るリスクと、その対策（トランザクションの終わりで必ず消す、接続を返すときに初期化する）を書く
- 推奨の組み合わせを最後にまとめる。どれも「テナントが正しく決まること」が前提で、そこは DB に関係なく `TenantFilter` の守りが要ることを強調する
- Claims は、このリポジトリが PostgreSQL 固有の機能に依存している箇所と、DB に依存しない部品の箇所について書く。他の DB やライブラリの仕様は Claims にしない
