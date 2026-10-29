# ADR: 組織階層に応じた「見える範囲」をどこで判定するか

- ステータス: **採用（Accepted）**。決定1〜3のうち、3は「自前の実装（候補 A）」まで実装済み
- 日付: 2026-10-29（提案・採用）
- ブランチ: `feature/org-hierarchy`

## 背景

現在のサンプルは、RLS で次の2段の守りを実現しています（[STORY.md](STORY.md) 第8章）。

- テナントの壁（RESTRICTIVE ポリシー）: 他テナントのデータは絶対に見えない
- テナントの中の権限（PERMISSIVE ポリシー）: 一般ユーザーは自分のデータだけ、管理者はテナント内の全データ

ここに、組織の上下関係（事業部 → 部）による見える範囲を加えたいという要件が出てきました。

| ユーザー | 立場 | 見える範囲 |
|---|---|---|
| A | 一般 | 自分のデータだけ |
| B | 部長 | 部員のデータ |
| C | 事業部長 | 事業部のメンバー（配下の部を含む）のデータ |
| D | テナント管理者 | テナント内の全データ |

この4パターンだけなら、RLS のポリシーで表現できます。たとえば部署に ltree のパスを持たせれば、次の1行で済みます。

```sql
OR EXISTS (SELECT 1 FROM organizations o
            WHERE o.id = orders.org_id AND o.path <@ current_app_scope_path())
```

しかし、今は想定していなくても、次のような仕様が**今後増える可能性が高い**と考えています。

- **兼務**: 1人が複数の部署に所属する、複数の部署の部長を兼ねる
- **代理**: 部長の不在時に、課長や別の部長が代わりに見られる
- **期間限定の権限**: 休暇中だけ、異動の引き継ぎ期間だけ、といった有効期限付きの権限
- **例外的な共有**: 特定の案件だけ、別部署の担当者にも見せる

これらをすべて RLS のポリシー（SQL）で表現すると、ポリシーが複雑になり、テストもレビューも難しくなります。ポリシーの変更には DDL とデプロイが必要で、組織改編のたびに DB の変更が発生するのも避けたいところです。

## 決定のポイント

1. **テナントの壁は絶対に崩さない**。他テナントのデータが見えないことは、どんな認可の仕組みになっても DB で保証したい
2. **WHERE の書き忘れに強いままでいたい**。RLS を入れた最大の理由（守るべき場所を「すべての SQL」から「数か所の関所」に絞る）を失いたくない
3. **兼務・代理・期間限定を、DB のスキーマ変更なしで足せるようにしたい**
4. **一覧の絞り込みが効率よくできること**。「見てよい受注の一覧」を、MyBatis の SQL で効率よく取れる必要がある（1件ずつ「見てよいか」を問い合わせるのでは遅すぎる）
5. 運用の負担（サーバーの追加、データの同期）が見合うこと
6. Java / Spring Boot から使えること

## 検討した選択肢

### 選択肢1: RLS に全振りする

組織の階層、兼務、代理、期間をすべてテーブルと RLS ポリシーで表現します。

- 👍 新しい部品が要らない。DB を直接触る経路（バッチ、psql）にも効く
- 👎 仕様が増えるたびにポリシーが複雑になる。ポリシーのテストとデバッグが難しい
- 👎 ポリシーの変更は DDL。認可ルールの変更がスキーマ変更と同じ重さになる
- 👎 ポリシーのサブクエリが行ごとに評価され、性能の見積もりが難しくなる

### 選択肢2: アプリの認可に全部移す（RLS はテナントの壁だけ）

見える範囲の判定はアプリで行い、Mapper の WHERE 句で絞ります。RLS はテナントの壁だけを守ります。

- 👍 認可ルールを Java（または認可エンジン）で柔軟に書ける
- 👎 **テナントの中では、WHERE の書き忘れがそのまま「見えてはいけない人に見える」事故になる**。RLS を入れた理由の半分を失う

### 選択肢3: 「判断はアプリ、強制は DB」のハイブリッド（推奨）

見える範囲の**判断**（誰がどの部署のデータを見られるか）はアプリ側の認可の仕組みで行い、その結果だけを DB に渡して、RLS が**強制**します。

```
① アプリ（認可の仕組み）が「このユーザーが見てよい部署の一覧」を算出する
     bob   → []                     （一般: 自分のデータだけ）
     部長   → [第一営業部]
     事業部長 → [営業事業部, 第一営業部, 第二営業部]
     管理者  → （全部。既存の app.role = ADMIN で表す）

② TenantAwareTransactionManager が、その結果を他の値と一緒に SET LOCAL で DB に渡す
     SELECT set_config('app.visible_org_ids', '{12,13,14}', true)

③ RLS は「渡された部署に属するデータか」だけを見る
     CREATE POLICY ... USING (
           user_id = current_app_user_id()
        OR current_app_is_admin()
        OR org_id = ANY (current_app_visible_org_ids())
     );
```

- 👍 テナントの壁（RESTRICTIVE）はそのまま。他テナントは引き続き DB が絶対に見せない
- 👍 WHERE の書き忘れにも強いまま。どの SQL にも「見てよい部署か」の条件が DB で付く
- 👍 兼務・代理・期間限定が増えても、変わるのは①の算出ロジックだけ。RLS のポリシーは「渡された一覧に入っているか」から変わらない
- 👍 一覧の絞り込みは `org_id = ANY(...)` なので、インデックスが効く
- 👎 「見てよい部署の一覧」をリクエストごとに算出するコストがかかる（キャッシュで軽減できる）
- 👎 部署の数が非常に多いテナントでは、配列が大きくなる（数百〜数千程度なら問題になりにくい）
- 👎 判断が DB の外にあるので、DB を直接触る経路（psql、バッチ）は、別途その経路の範囲を決める必要がある

**ポイントは、認可を「部署の単位」で行うことです。** 受注1件ずつを認可エンジンに問い合わせるのではなく、「見てよい部署」を求めて DB に渡します。部署の数は受注の数よりはるかに少ないので、どの認可エンジンを使っても一覧の絞り込みが現実的なコストで済みます。

## 「見てよい部署」を算出する仕組みの候補

選択肢3の①を担う仕組みの候補です。

### A. 自前の `AccessScopeResolver`（Java + 組織テーブル）

組織の階層（ltree や閉包テーブル）、兼務、代理の情報を自分たちのテーブルに持ち、Java のコードで「見てよい部署」を求めます。Spring Security の `@PreAuthorize` や `PermissionEvaluator` と組み合わせることもできます。

- 👍 新しいサーバーもライブラリも要らない。組織データは今の DB にあるので、同期の問題がない
- 👍 今の4パターンなら、数十行で書ける
- 👎 兼務・代理・期間・例外共有が増えると、結局は自前の認可エンジンを育てることになる
- 👎 ルールがコードに埋まるので、「誰がなぜ見えるのか」を説明する仕組みも自分で作る必要がある

### B. OpenFGA（Zanzibar 型 / ReBAC）

Google の社内認可システム「Zanzibar」の論文をもとにしたオープンソースの認可サーバーです[^zanzibar]。2025年10月に CNCF の Incubating プロジェクトになりました[^openfga-cncf]。Java の SDK があります[^openfga-java]。

「誰が、何に対して、どんな関係にあるか」を**関係（タプル）**として登録し、その関係の連鎖で権限を判定します（ReBAC: Relationship-Based Access Control）。今回の要件は、たとえば次のように書けます。

```
model
  schema 1.1

type user

type tenant
  relations
    define admin: [user]

type organization
  relations
    define tenant: [tenant]
    define parent: [organization]
    define member: [user]
    define manager: [user]
    # 部長は自分の部、事業部長は「親の部署で見られる人」として配下の部も見られる。管理者は全部
    define can_view_orders: manager or can_view_orders from parent or admin from tenant
```

```
# 登録する関係（タプル）の例
organization:第一営業部 # parent  @ organization:営業事業部
user:部長B             # manager @ organization:第一営業部
user:事業部長C          # manager @ organization:営業事業部
```

「事業部長 C が見られる部署の一覧」は、`ListObjects(user:事業部長C, can_view_orders, organization)` で取れます。兼務は関係を2つ登録するだけ、代理は `manager` に代理人を足すだけで、モデルの変更は最小限です。期限付きの権限は、条件付きの関係（Conditional Relationship Tuples）で表せます。

- 👍 兼務・代理・例外共有のような「関係」で表せる要件に非常に強い。モデルの変更でルールを足せる
- 👍 「なぜ見えるのか」を関係の連鎖として説明できる。複数のサービスで同じ認可を共有できる
- 👎 **認可サーバーを1つ運用する必要がある**（ストアに PostgreSQL などを使う）
- 👎 **組織や所属のデータを OpenFGA にも登録し続ける必要がある**（DB と OpenFGA の二重管理）。組織改編や異動のたびに、両方を整合させる仕組み（Outbox パターンなど）が要る
- 👎 `ListObjects` の性能は、モデルの複雑さや関係の数によって大きく変わる[^openfga-listobjects]。部署の単位で使うなら問題になりにくい

### C. SpiceDB（Zanzibar 型 / ReBAC）

OpenFGA と同じく Zanzibar をもとにした認可データベースです。AuthZed 社が開発し、Java のクライアントがあります[^spicedb-java]。考え方は OpenFGA とほぼ同じで、一覧の絞り込みには `LookupResources` を使います。

- 👍 Zanzibar の考え方（一貫性の制御など）に忠実で、大規模な実績がある
- 👎 OpenFGA と同じく、サーバーの運用とデータの二重管理が必要
- 👎 `LookupResources` には過去に、結果が欠ける不具合の報告がある[^spicedb-cve]。採用するならバージョンの管理に注意が必要

### D. Cerbos（ポリシー型 / ABAC）

YAML でポリシーを書き、別プロセス（PDP: Policy Decision Point）で判定するオープンソースの認可エンジンです。Java の SDK があります。

Zanzibar 型との大きな違いは、**関係のデータを Cerbos に登録しない**ことです。判定のたびに、アプリが「ユーザーの属性」と「データの属性」を渡します（たとえば「このユーザーが管理する部署のパス」と「この受注の部署のパス」）。一覧の絞り込みには `PlanResources` を使い、条件の構文木（AST）を受け取ってアプリが SQL に変換します[^cerbos-plan]。

- 👍 データの二重管理がない。組織データは今の DB に置いたまま
- 👍 ポリシーがコード（YAML）としてレビュー・テストできる
- 👎 「このユーザーが管理する部署」「代理で見られる部署」といった属性は、結局アプリが計算して渡す必要がある。兼務・代理の複雑さの一部はアプリに残る
- 👎 `PlanResources` の結果を SQL に変換する部品は、MyBatis 向けには用意されていないので、自前で書く必要がある

### E. その他（今回は候補から外したもの）

| 候補 | 外した理由 |
|---|---|
| **Cedar**（AWS が開発したポリシー言語。Amazon Verified Permissions のエンジン） | Java のバインディングはあるが、Rust 本体の新機能に遅れて追従する[^cedar-java]。一覧の絞り込みに使う部分評価（Partial Evaluation）の Java 対応がまだ限定的 |
| **jCasbin**（ライブラリ型。ACL / RBAC / ABAC） | アプリに組み込めて手軽で、テナント（ドメイン）ごとのロールや、ロールの階層も表せる[^casbin]。ただし「配下の部署を全部」のような組織ツリーの探索や、期限付きの権限は自分で組み立てる必要があり、A（自前）との差が小さい |
| **Oso**（ライブラリ型） | オープンソースのライブラリは 2023年12月に非推奨になり[^oso]、現在の主力は SaaS の Oso Cloud。自前でホストできる組み込み型としては選びにくい |
| **OPA**（汎用のポリシーエンジン。Rego） | インフラの認可（Kubernetes など）では定番だが、アプリのデータの認可と一覧の絞り込みには、Partial Evaluation の結果を SQL に変換する仕組みを作り込む必要がある |

### 候補の比較

| 観点 | A. 自前 | B. OpenFGA | C. SpiceDB | D. Cerbos |
|---|---|---|---|---|
| 追加で運用するもの | なし | 認可サーバー | 認可サーバー | PDP（サイドカーでも可） |
| 組織データの二重管理 | なし | **あり** | **あり** | なし |
| 兼務・代理・例外共有 | コードで書く | **関係を足すだけ** | **関係を足すだけ** | 属性をアプリで計算 |
| 期限付きの権限 | コードで書く | 条件付きの関係 | 期限付きの関係 | ポリシーの条件 |
| 「見てよい部署の一覧」 | SQL で求める | `ListObjects` | `LookupResources` | `PlanResources` → 変換 |
| 「なぜ見えるか」の説明 | 自分で作る | 関係の連鎖で説明できる | 関係の連鎖で説明できる | ポリシーの判定結果 |
| 学習コスト | 低 | 中（ReBAC の考え方） | 中〜高 | 中 |
| Java 対応 | ― | 公式 SDK | 公式クライアント（gRPC） | 公式 SDK |

## 決定（提案）

1. **テナントの壁は、引き続き RLS（RESTRICTIVE ポリシー）で守る。** どの認可の仕組みを採用しても変えない
2. **組織階層による見える範囲は、選択肢3「判断はアプリ、強制は DB」で実現する。** アプリが「見てよい部署の一覧」を算出し、`app.visible_org_ids` として DB に渡し、RLS が `org_id = ANY(...)` で強制する
3. **「見てよい部署の一覧」を算出する部分は、インターフェース（`AccessScopeResolver`）として切り出す。** 最初は **A. 自前の実装**で今の4パターンを実現し、兼務・代理・期間限定の要件が具体化した時点で **B. OpenFGA** への置き換えを PoC で評価する

OpenFGA を第一候補にする理由は、兼務・代理・例外共有といった今後の要件が、いずれも「関係」として自然に表せるからです。また CNCF の Incubating プロジェクトであり、ベンダーに依存しにくい点も評価しました。一方で、組織データの二重管理は無視できない負担なので、要件が具体化する前に先回りして導入することはしません。インターフェースを切っておけば、置き換えても RLS のポリシーと Mapper は変わりません。

## この決定による影響

- `orders` に `org_id`（受注を作った時点の名義人の部署）を持たせる必要がある。名義人が異動しても、受注は元の部署のデータのままになる
- DB に渡す値が1つ増える（`app.visible_org_ids`）。第6章の信頼の鎖はそのまま当てはまり、特に SQL インジェクションで `app.visible_org_ids` を書き換えられると、テナント内の任意の部署のデータが見える（RESTRICTIVE があるので他テナントには届かない）
- 「見てよい部署の一覧」は、リクエストごと（トランザクションごと）に算出する。性能が問題になれば、ユーザー単位で短時間キャッシュする。ただしキャッシュすると、異動や権限の変更がすぐには反映されなくなる
- psql やバッチなど、アプリを通らない経路では、その経路で渡す範囲を別途決める必要がある

## 実装時に決めたこと

提案の時点で未決だった事項は、自前の実装（候補 A）を作る際に次のように決めました。

| 事項 | 決定 | 理由 |
|---|---|---|
| 部署の階層の持ち方 | `organizations.parent_id` と、アプリ側の再帰 CTE | RLS のポリシーは階層をたどらず、渡された一覧と比べるだけになった。階層をたどるのはリクエストごとに1回なので、ltree のような PostgreSQL 専用の型や、閉包テーブルのメンテナンスは要らない |
| 管理職の表し方 | ロールに `MANAGER` を追加し、「自分の所属部署とその配下」を範囲にする | 今の4パターンには十分。兼務・代理が入ったら、`AccessScopeResolver` の実装を変える（RLS は変えない） |
| 管理職の権限 | 範囲の中は参照も更新もできる（代理作成も含む） | 管理者と同じ扱いで、範囲だけが狭い。ポリシーを SELECT と書き込みで分けずに済む |
| 組織図の見え方 | テナント内の全員が組織図（`organizations`）を見られる。変更は管理者だけ | 組織図は社内で公開されている前提。`AccessScopeResolver` も階層をたどるのに使う |

実装の構成は次のとおりです。

| 部品 | 役割 |
|---|---|
| `AccessScope`（tenant パッケージ） | 「見てよい部署」の一覧。`app.visible_org_ids` の値（例 `{1,2,3}`）に変換する |
| `AccessScopeResolver`（user パッケージ） | 算出のインターフェース。将来 OpenFGA の実装に置き換える差し込み口 |
| `OrganizationHierarchyScopeResolver`（org パッケージ） | 自前の実装。管理職なら、所属部署とその配下を再帰 CTE で求める |
| `UserService.resolve` | JWT の `sub` から users の行を引き、ロールと `AccessScope` を解決する |
| `TenantAwareTransactionManager` | `app.visible_org_ids` を他の値と一緒に `SET LOCAL` で渡す |
| RLS ポリシー `owner_or_scope_or_admin` | `user_id = 自分 OR org_id = ANY(見てよい部署) OR 管理者` |

`OrgScopeAccessTest` と `OrganizationHierarchyScopeResolverTest` が、一般・部長・事業部長・管理者の見え方と、異動しても過去の受注が元の部署のデータのままであることを確認しています。

## 未決事項

- 兼務・代理・期間限定の具体的な要件。OpenFGA の PoC を始める判断材料になる
- 「見てよい部署の一覧」のキャッシュの要否と有効期間（今はリクエストごとに算出している）
- psql やバッチなど、アプリを通らない経路で渡す範囲の決め方

## 参考

[^zanzibar]: Zanzibar: Google's Consistent, Global Authorization System（USENIX ATC 2019）。Google ドライブや YouTube などの権限判定を支える仕組みとして公開された論文で、OpenFGA や SpiceDB はこの考え方をもとにしています。
[^openfga-cncf]: [OpenFGA becomes a CNCF incubating project](https://www.cncf.io/blog/2025/11/11/openfga-becomes-a-cncf-incubating-project/) / [OpenFGA Project](https://openfga.dev/project)
[^openfga-java]: [OpenFGA Fine-Grained News - October 2025](https://openfga.dev/blog/fine-grained-news-2025-10)（Java SDK 0.9.2 のリリース）
[^openfga-listobjects]: [Perform a list objects call | OpenFGA](https://openfga.dev/docs/getting-started/perform-list-objects)
[^spicedb-java]: [authzed/authzed-java](https://github.com/authzed/authzed-java)
[^spicedb-cve]: [CVE-2025-65111](https://v2.cvefeed.io/vuln/detail/CVE-2025-65111)（1.47.1 で修正）
[^cerbos-plan]: [Filtering resources by permission | Cerbos](https://docs.cerbos.dev/cerbos/latest/recipes/filtering-resources)
[^cedar-java]: [cedar-policy/cedar-java](https://awesome.ecosyste.ms/projects/github.com%2Fcedar-policy%2Fcedar-java) / [Cedar Java partial evaluation の例](https://github.com/cedar-policy/cedar-examples/tree/release/4.10.x/cedar-java-partial-evaluation)
[^casbin]: [apache/casbin-jcasbin](https://github.com/apache/casbin-jcasbin) / [RBAC overview | Casbin](https://casbin.apache.org/docs/rbac-overview)
[^oso]: [osohq/oso](https://www.github.com/osohq/oso)
