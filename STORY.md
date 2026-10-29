# STORY — WHERE句を書き忘れても、他社のデータは返らない

PostgreSQL の Row Level Security（RLS）[^rls] でマルチテナント[^multitenant]のデータを守る方法、その利点と限界、そして設計のベストプラクティスを、このリポジトリのコードを使ってひとつの物語として説明します。

手順やコマンドの一覧は [README.md](README.md) にあります。この文書では「なぜそうするのか」と「どこまで守れて、どこから守れないのか」を説明します。

---

## 序章　ある日の障害報告

> 「A商事の担当者から、受注一覧に B工業の受注が混ざっているという問い合わせがありました」

原因を調べると、ある画面の Mapper に次の SQL がありました。

```xml
<select id="findAllWithoutTenantFilter" resultMap="orderMap">
    SELECT id, tenant_id, user_id, title
      FROM orders
     ORDER BY id
</select>
```

`WHERE tenant_id = #{tenantId}` が抜けています。レビューでも見落とされ、テストデータが1社分しかなかったためテストも通っていました。

翌月には別の問い合わせが来ます。

> 「URL の末尾の数字を書き換えたら、他社の受注詳細が見えた」

```xml
<select id="findById" resultMap="orderMap">
    SELECT id, tenant_id, user_id, title FROM orders WHERE id = #{id}
</select>
```

id だけで1件を取得していたためです。これは IDOR（Insecure Direct Object Reference）[^idor] と呼ばれる典型的な脆弱性です。

どちらも、**1本の SQL に条件を1つ書き忘れただけで、他社のデータが見えてしまう**という同じ構造の事故です。このリポジトリの `OrderMapper.xml` には、この2つの「危ない SQL」をそのまま残してあります。

## 第1章　レビューでは守りきれない

再発防止策としてまず挙がるのは、次のような対策です。

- レビューのチェックリストに「tenant_id 条件があるか」を追加する
- 静的解析で `FROM orders` を含むのに `tenant_id` を含まない SQL を検出する
- 全 Mapper を棚卸しする

どれも必要な対策ですが、共通の弱点があります。**守るべき対象が「すべての SQL が正しいこと」になる**点です。Mapper が数百本あるなら、数百か所すべてが毎回正しくなければなりません。新しく入ったメンバーが書いた1本、深夜の障害対応で急いで書いた1本、JOIN の中のサブクエリの1本。どれか1本が漏れるだけで事故になります。

確率の問題として、全員がいつも正しく書き続けることは期待できません。そこで発想を変えます。

## 第2章　DB に関所を置く ── RLS という発想

アプリの SQL がどう書かれていても、**DB 自身が「今の接続はどのテナントか」を知っていて、そのテナントの行しか返さない**ようにします。これが RLS です。

`db/init/02_schema.sql` の本体はたったこれだけです。

```sql
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE orders FORCE  ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON orders
  USING      (tenant_id = current_tenant_id())   -- 見える行（SELECT / UPDATE / DELETE）
  WITH CHECK (tenant_id = current_tenant_id());  -- 書き込める行（INSERT / UPDATE）
```

（これは考え方を示すための最小形です。今のこのサンプルは、テナントの中の権限も加えた「2段構え」のポリシーになっています。第8章で説明します。）

ポリシーを付けると、PostgreSQL はこのテーブルに対するすべてのクエリに `tenant_id = current_tenant_id()` という条件を暗黙に追加します[^policy]。序章の2本の SQL は、DB の中では次のように実行されます。

```sql
SELECT ... FROM orders ORDER BY id;          -- → WHERE tenant_id = 1 が付く
SELECT ... FROM orders WHERE id = 3;         -- → AND tenant_id = 1 が付く（B工業の id=3 は 0 件）
```

`current_tenant_id()` は、接続ごとの設定値 `app.tenant_id` を読む関数です[^guc]。

```sql
CREATE FUNCTION current_tenant_id() RETURNS bigint LANGUAGE sql STABLE AS $$
  SELECT NULLIF(current_setting('app.tenant_id', true), '')::bigint
$$;
```

テナントが設定されていないと NULL になり、`tenant_id = NULL` は常に偽なので **0 件** になります。設定を忘れたときに「全部見える」ではなく「何も見えない」側に倒れる、fail-closed[^failclosed] の設計です。`NULLIF(..., '')` が必要な理由は脚注[^nullif]で説明します。

## 第3章　アプリから DB にテナントを届ける

DB 側の準備ができたら、残る問題は **「今のリクエストはどのテナントか」を、SQL を流す接続に教える方法** です。このサンプルでは、次の順番でテナントが運ばれます。

```
HTTP リクエスト（Authorization: Bearer <JWT>、必要なら X-Tenant-Id）
  │
  ▼
Spring Security ………… JWT の署名と有効期限を検証（不正なら 401）
  │
  ▼
TenantFilter ……………… ① JWT のクレーム tenant_ids とヘッダを突き合わせてテナントを確定（所属外なら 403）
  │                      ② そのテナントの users テーブルを JWT の sub で引き、ユーザーIDとロールを確定
  │                        （登録がなければ 403）
  │                      ③ TenantContext / UserContext（ThreadLocal）に入れる
  ▼
@Transactional の OrderService
  │
  ▼
TenantAwareTransactionManager#prepareTransactionalConnection
  │   BEGIN の直後、同じ接続で                         ← true = SET LOCAL
  │   SELECT set_config('app.tenant_id', '1',     true),
  │          set_config('app.subject',   'bob',   true),
  │          set_config('app.user_id',   '2',     true),
  │          set_config('app.role',      'GENERAL', true)
  ▼
MyBatis の SQL（そのまま実行。RLS が条件を足す）
  │
  ▼
COMMIT / ROLLBACK …………… SET LOCAL の値は自動的に消える
```

### 2つのプールの間を、テナントが安全に通り抜ける

このアプリにはプールが2つあり、どちらも「前の利用者の状態が残る」危険を持っています。

| プール | 中身 | 残ると何が起きるか | このサンプルでの対処 |
|---|---|---|---|
| **Tomcat のリクエスト処理スレッドプール**[^threadpool] | リクエストを処理するスレッド。使い終わったスレッドは次のリクエストに再利用される | ThreadLocal にテナントが残っていると、次のリクエストが**前の人のテナント**で動く | `TenantContext.runAs` が `finally` で必ず元の値に戻す（`remove`） |
| **HikariCP のコネクションプール**[^hikari] | DB 接続。使い終わった接続は次のトランザクションに再利用される | セッション単位の `SET` だと、次のトランザクションに**前の人のテナント**が残る | `set_config(..., true)`（= `SET LOCAL`）でトランザクション終了時に消す |

テナントはスレッドにも接続にも「トランザクションの間だけ」乗り、終わったら必ず降ります。これがこの設計の要です。なお、このサンプルは `@Async` などのアプリ独自のスレッドプールは使っていません（別スレッドに処理を渡す場合の注意は第6章②）。

### なぜ TransactionManager に仕込むのか

`set_config` を発行する場所の候補は、AOP、MyBatis の Interceptor、DataSource のラッパーなどいくつかあります。このサンプルが `JdbcTransactionManager#prepareTransactionalConnection` を選んだ理由は次のとおりです[^txhook]。

- Spring が **BEGIN 直後に同じ接続で** 必ず呼ぶフックなので、そのトランザクション内のすべての SQL に確実に効く
- `@Transactional` の AOP と別の AOP の実行順序（`@Order`）を気にしなくてよい
- MyBatis はこの TransactionManager が管理する接続に参加するので、Mapper 側は何も変えなくてよい

Spring Boot の自動設定は、TransactionManager の Bean が定義されていない場合にだけ作られます。そのため `TenantConfig` で Bean を定義するだけで、テナント対応版に置き換わります。

## 第4章　体験する ── 3つの確認

| 手順 | 何を確認しているか |
|---|---|
| `psql ... < db/demo.sql` | **DB 単体で** RLS が効くこと。WHERE なし、IDOR、他社行の UPDATE/DELETE、他社 tenant_id での INSERT、一般ユーザーと管理者の見え方の違い、明細が親の受注に連動すること、トランザクション終了後に設定が消えること |
| `mvn test` | **アプリ経由で** 同じ守りが効くこと。TransactionManager が `set_config` を流し、WHERE を書き忘れた Mapper でも自社分（一般ユーザーなら自分の分）しか返らないこと。`@Transactional` の外では何も見えないこと。JWT とヘッダ、users テーブルからテナントとユーザーが正しく決まること |
| `bypass` プロファイルで起動 | **RLS が無かったら** どうなるか。コードは同じで、BYPASSRLS ロールで接続するだけで他社データが見える |

3つ目の比較が、現場に説明するときに一番効きます。「今の現場の状態」と「RLS を入れた状態」の差は、接続するロールの違いだけです。

## 第5章　RLS の利点

### 1. 守るべき場所を「数百か所」から「数か所」に絞れる

RLS の最大の価値はここにあります。守るべきものが「すべての SQL が正しいこと」から、次の**数か所の関所が正しいこと**に変わります。

- テナントを決める場所（`TenantFilter`）
- テナントを運ぶ仕組み（`TenantContext`）
- テナントを接続に設定する場所（`TenantAwareTransactionManager`）
- DB の設定（ロール、ポリシー）

数百本の Mapper をレビューする代わりに、この数か所を厳しくレビューし、機械的なチェックで守ります。

### 2. 失敗しても「漏れる」のではなく「見えない」側に倒れる

テナントの設定を忘れたとき、`@Transactional` を付け忘れたとき、別スレッドに処理を渡したときは、どれも 0 件になります。気付きにくいバグにはなりますが、情報漏えいにはなりません。「全部見える」より「何も見えない」のほうが、事故としてはずっと軽く済みます。

### 3. 書き込みも守れる

`WITH CHECK` により、他社の `tenant_id` を持つ行を INSERT することも、UPDATE で自社の行を他社に付け替えることもできません。読み取りだけでなく、データの改ざんやなりすまし登録も DB が拒否します。

### 4. 既存コードをほぼ変えずに導入できる

Mapper の SQL は1行も変える必要がありません。必要なのは、DB のポリシーと、TransactionManager の差し替えと、テナントを決めるフィルタだけです。既存システムに後から入れる防御として現実的です。

### 5. アプリ以外の経路も守れる

同じ `app_user` ロールで接続する限り、別のアプリ、アドホックな SQL、将来追加される別言語のサービスにも同じポリシーが効きます。

## 第6章　信頼の鎖 ── 前提と、それが破られたときの抜け道

ここからが本題です。最初に、RLS の性質を一文で押さえておきます。

> **RLS が守るのは「SQL の書き間違い」までです。「誰として動いているか」の間違いは守れません。**

DB は渡されたテナント ID を忠実に使うだけです。そのため、ID が偽物なら、偽物のテナントのデータを正しく絞り込んで返してしまいます。

守りは「テナントが決まってから DB に届くまで」の鎖になっていて、その各段階が抜け道の候補です。

### ① テナントが正しく決まること（信頼の起点）

**前提**：テナント ID は認証済みの情報（JWT のクレームやサーバー側のセッション）から取る。そのユーザーがそのテナントに所属していることも確認済みである。

**抜け道**：リクエストヘッダ、クエリパラメータ、リクエストボディの `companyId` など、**クライアントが送ってくる値を信用すること**。

たとえば `X-Tenant-Id` ヘッダの値をそのままテナントとして使うと、ヘッダを書き換えるだけで誰でも他社になれます。

```bash
curl -s localhost:8080/api/orders -H 'X-Tenant-Id: 2'   # ヘッダを信用する実装なら、誰でも B工業になれる
```

RLS から見ると、これは「B工業の正規のアクセス」にしか見えません。**これは RLS では絶対に守れない穴で、しかも一番影響が大きい穴です。**

**このサンプルの守り方**：テナントの根拠は、署名を検証した JWT[^jwt] のクレーム `tenant_ids`（そのユーザーが所属するテナントの一覧）だけです。`X-Tenant-Id` ヘッダは「所属テナントの中からどれを使うか」を選ぶためだけに使い、`TenantFilter` で必ずクレームと突き合わせます。

| トークンの `tenant_ids` | `X-Tenant-Id` | 結果 |
|---|---|---|
| （トークンなし・署名が不正・期限切れ） | 何でも | 401（Spring Security が拒否） |
| `[1]` | なし / `1` | A商事として動く |
| `[1]` | `2` | **403**（所属していないテナント。なりすましの試みとして WARN ログに記録） |
| `[1,2]` | なし | 400（どちらか決められない） |
| `[1,2]` | `2` | B工業として動く |

ヘッダを残しているのは、複数のテナントに所属するユーザー（グループ会社の兼務者、販売代理店など）が「今どのテナントとして操作するか」を選べるようにするためです。所属が1社だけならヘッダは不要です。`TenantFilterWebTest` がこの表のとおりに動くことを確認しています。

なお、これで守れるのは「ユーザーが所属していないテナントを名乗ること」までです。JWT を発行する IdP の設定（`tenant_ids` に何を入れるか）が間違っていれば、その間違いはそのまま通ります。信頼の起点は、アプリから IdP に移っただけで、なくなったわけではありません。

### ② テナントがスレッドをまたいで正しく伝わること

**前提**：処理が同じスレッドで動いている。ThreadLocal は `finally` で必ず片付けられる。

**抜け道**：

- **別スレッドに処理を渡す**。`@Async`、`CompletableFuture`、parallel stream、メッセージリスナー、スケジューラなどです。別スレッドにはテナントが届かないので 0 件になります。漏れはしませんが、「動かないから RLS を外そう」「bypass ロールで動かそう」という判断を誘いやすい点が危険です。別スレッドでは `TenantContext.runAs` でテナントを明示的に渡します。
- **`set` だけして `remove` しない実装**。スレッドプールで次のリクエストに前のテナントが残ります。これは**本当に漏れる**ほうの事故です。値をセットする方法は `runAs` だけに限定します。
- **`InheritableThreadLocal` とスレッドプールの組み合わせ**。スレッドが作られた時点の親のテナントがコピーされ、そのスレッドが再利用されるたびに古いテナントで動きます。

### ③ テナントが DB の接続に届くこと

**前提**：SQL は、`TenantAwareTransactionManager` が開始したトランザクションの中で、同じ接続を使って実行される。

**抜け道**：

- **トランザクションの途中でテナントを切り替える。** `set_config` は BEGIN のときに1回しか発行されません。そのため、トランザクションの中で `TenantContext.runAs(B工業, ...)` を呼ぶと、Java 側は B工業なのに DB 側は A商事のまま動きます。このサンプルにも最初はこの穴がありましたが、今は `runAs` のガードでふさいでいます（直後のコラムを参照）。
- **別の DataSource や TransactionManager を通る経路**。2つ目の DB、独自に作った `JdbcTemplate`、JPA を後から追加した場合の別の TransactionManager、ライブラリが内部で持つ接続などです。テナントが設定されないので通常は 0 件になりますが、その経路が bypass ロールで接続していれば全件見えます。
- **誰かが `SET app.tenant_id = ...` や `set_config(..., false)` を書く**。設定がセッション全体に残るので、コネクションプールの次の利用者にテナントが漏れます。
- **SQL インジェクション**。`app.tenant_id` は、どのロールでも自由に書き換えられる設定値です[^guc]。そのため、MyBatis の `${}` などで SQL を注入できると、攻撃者が同じトランザクションの中で `SELECT set_config('app.tenant_id', '2', true)` を実行して、**他社に切り替えられます**。実際に `app_user` でこれを実行すると、B工業の行が見えます。**RLS は SQL インジェクション対策の代わりにはなりません。**

#### コラム：トランザクションの途中でテナントを切り替えると、何が起きるか

このサンプルの最初の版には、次のコードが「動いてしまう」穴がありました。

```java
TenantContext.runAs(A商事, () ->
    tx.execute(status ->                        // ① BEGIN。ここで set_config('app.tenant_id', '1', true)
        TenantContext.runAs(B工業, () -> {        // ② Java 側だけ B工業に切り替わる
            Order order = orderService.find(...); // ③ DB 側はまだ A商事。A商事の行が返る
            auditLog.write(TenantContext.require(), order); // ④ 「B工業が A商事の行を見た」と記録される
            return order;
        })));
```

テナントを DB に教えるのは `TenantAwareTransactionManager` で、BEGIN のときの1回だけです。②で `TenantContext` を B工業に書き換えても、DB の `app.tenant_id` は A商事のままです。その結果、**Java 側と DB 側で「今のテナント」が食い違います**。

この食い違いは、それ自体ではすぐに他社データの漏えいにはなりません。DB は一貫して A商事として動いているからです。危ないのは、Java 側で `TenantContext` の値を使っている処理です。

- ④のように、監査ログやメール送信の宛先、キャッシュのキー、ファイルの保存先に、間違ったテナントが使われる
- Java 側で「B工業のデータだから B工業のユーザーに送る」と判断した処理に、A商事の行が渡される
- `createOrder` は `TenantContext` の値を `tenant_id` に入れるので、B工業の行を A商事の接続で INSERT しようとして WITH CHECK 違反になる（これは DB が止めてくれる）

バッチ処理で「全テナントを順番に処理する」ループを書くと、ループ全体を1つのトランザクションで囲んでしまい、この状態に陥りがちです。

**対処の選択肢**は3つありました。

| 方法 | 内容 | 判断 |
|---|---|---|
| A. 切り替え時に `set_config` を発行し直す | `runAs` の中で、実行中のトランザクションの接続に新しいテナントを設定する | ❌ 同じトランザクションの中に、A商事として読んだデータと B工業として読んだデータが混ざる。「1つのトランザクションは1つのテナント」という前提が崩れる |
| B. 切り替え時に自動で新しいトランザクションを始める | `REQUIRES_NEW` 相当の動きを `runAs` に持たせる | ❌ 呼び出し側から見えないところでトランザクションが分かれ、ロールバックの範囲が予想と変わる |
| C. **トランザクションの途中での切り替えを禁止する** | 実行中のトランザクションがあり、テナントが変わる場合は例外を投げる | ✅ 間違った使い方を、その場で、確実に気付かせられる |

このサンプルは C を選び、`TenantContext.runAs` に次のガードを入れています。

```java
if (TransactionSynchronizationManager.isActualTransactionActive() && !tenantId.equals(previous)) {
    throw new IllegalStateException(
            "Cannot switch tenant inside a transaction: current=" + previous + ", requested=" + tenantId);
}
```

同じテナントでの `runAs` の入れ子は、食い違いが起きないので許しています。テナントが設定されていない状態で始まったトランザクションの中で `runAs` を呼ぶ場合も、DB 側は「未設定」のままなので拒否します。

全テナントを順番に処理したいときは、**テナントごとに `runAs` を呼び、その内側でトランザクションを始めます**。

```java
for (TenantId tenant : tenants) {
    TenantContext.runAs(tenant, () -> batchService.process());   // process() が @Transactional
}
```

`OrderRlsIntegrationTest` の `switchTenantInsideTransaction_isRejected` と `sameActorInsideTransaction_isAllowed` が、この2つの動きを確認しています。

### ④ DB 側の設定に漏れがないこと

**前提**：アプリはスーパーユーザーでも BYPASSRLS ロールでもないロールで接続する。テナントが持つすべてのテーブルに ENABLE、FORCE、ポリシーが付いている。

**抜け道**：

- **RLS が効かないロールで接続する**[^bypass]。スーパーユーザー、`BYPASSRLS` を持つロール、`FORCE` が付いていないテーブルの所有者は、ポリシーを素通りします。`bypass` プロファイルはこの状態の再現です。Spring Boot の Docker Compose 連携は `POSTGRES_USER`（スーパーユーザー）で接続するので、このサンプルでは使っていません。
- **新しく追加したテーブルにポリシーを付け忘れる**。RLS が無効なテーブルは全件見えます。README に載せた検出 SQL を CI で実行するのが確実です。
- **ビュー。見落とされやすい代表例です。** ビューは、デフォルトでは**ビューの所有者の権限**でテーブルにアクセスします[^view]。所有者がスーパーユーザーなら RLS を素通りします。このサンプルの環境で `owner` が作ったビューを `app_user` から読むと、テナントを設定していても全社の行が返ります。PostgreSQL 15 以降では `WITH (security_invoker = true)` を付けて、呼び出したユーザーの権限で動かします。`SECURITY DEFINER` 付きの関数も同じ問題を持ちます。
- **マテリアライズドビュー**。中身は REFRESH した時点のスナップショットで、元のテーブルのポリシーは効きません。マテリアライズドビューには RLS を付けられないので、テナントのデータを入れないか、アクセスできるロールを絞ります。
- **`WITH CHECK` のないポリシー**。`USING` だけでも INSERT/UPDATE のチェックに流用されますが[^policy]、`FOR SELECT` だけのポリシーなど書き方によっては書き込みが素通りします。読み取りと書き込みの両方が守られているか、テストで確認します。
- **`tenant_id` を持たない子テーブル**（受注明細など）。親テーブルを経由しないと読めないつもりでも、子テーブルを直接 SELECT されれば全件見えます。子テーブルにも `tenant_id` を持たせてポリシーを付けるのが一番単純です。このサンプルの `order_details` はそうしています（第8章）。

### ⑤ そもそも RLS の守備範囲の外にあるもの

RLS は「PostgreSQL のテーブルの行」を守る仕組みです。DB を通らないもの、行より細かい単位のものは守れません。

- **アプリのキャッシュ**。id だけをキーにした `@Cacheable` や Redis、MyBatis の2次キャッシュ（Mapper の `<cache/>`）[^mybatiscache] は、キーにテナントが含まれません。A商事の結果がキャッシュされると、B工業にもそのまま返ります。DB まで問い合わせないので RLS は関係ありません。
- **DB の外にあるデータ**。S3 などのファイル、全文検索エンジン、ログ、外部 API です。
- **同じテナントの中の認可**。テナント分離の仕組みだけでは、一般社員が同僚や上長のデータを見られる問題は防げません。RLS で表現することはできますが、テナント分離とは別に設計する必要があります（このサンプルでの設計は第8章）。
- **存在の推測（サイドチャネル）**[^sidechannel]。一意制約違反や外部キー違反のエラーから、「他社にこの値が存在する」ことを推測できる場合があります。外部キーの存在チェックは RLS を無視するためです。

## 第7章　設計のベストプラクティス

第6章の抜け道を、関所ごとに「守り方」と「機械的なチェック」に落とし込みます。人の注意力に頼らず、**CI で落ちる仕組み**にするのが原則です。

### ① テナントの決定

- テナントは**認証情報（署名を検証した JWT のクレームやサーバー側のセッション）からだけ**取る。ヘッダやパラメータの値をそのまま使わない
- 複数テナントに所属できるユーザーなら、ヘッダなどで指定された切り替え先のテナントが、認証情報の所属テナントに含まれているかをサーバー側で確認する（このサンプルの `TenantFilter`）
- 所属外のテナントの指定は 403 で拒否し、なりすましの試みとしてログに残す
- `TenantContext` に値をセットできるクラスを制限する（ArchUnit[^archunit] で「`runAs` を呼べるのはフィルタ、バッチの起点、テストだけ」と検査する）
- リクエストボディにテナント ID を含めない（このサンプルの `CreateOrderRequest` に `tenantId` が無いのはそのため）

### ② テナントの伝播

- ThreadLocal に値を入れる方法は `runAs`（try/finally で必ず戻す）だけにする
- 別スレッドに処理を渡すときは、`runAs` で明示的にテナントを渡す。`TaskDecorator` を使って Executor で自動的に引き継ぐ方法もあります
- `InheritableThreadLocal` は使わない

### ③ 接続への設定

- `set_config(..., true)`（`SET LOCAL`）だけを使い、セッション単位の `SET` は使わない
- テナントの設定は TransactionManager の1か所だけで行う
- トランザクションの中で `runAs` によるテナントの切り替えを禁止する（このサンプルは `runAs` が例外を投げる。第6章③のコラムを参照）
- DataSource と TransactionManager はひとつにまとめる。増やすときは同じ仕組みを必ず入れる
- MyBatis の `${}` を禁止し、`#{}` だけを使う。CI で `${` を grep して検出する

### ④ DB の設定

- 接続ロールを用途ごとに分ける（`01_roles.sql`）
  - `owner`：DDL 専用。アプリからは使わない
  - `app_user`：通常のアプリ。RLS が効く
  - `app_bypass`：テナントをまたぐバッチや運用作業だけに使う。使う箇所を限定し、監査ログを残す
- テナントが持つテーブルには、例外なく `ENABLE` + `FORCE` + `USING` と `WITH CHECK` 付きのポリシーを付ける。ポリシーの条件は `current_tenant_id()` を使い回す
- CI で次のような SQL を実行し、1行でも返ったら失敗させる

```sql
-- tenant_id を持つのに RLS が無効、または FORCE されていないテーブル
SELECT c.relname
  FROM pg_class c
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id'
 WHERE c.relkind = 'r'
   AND (NOT c.relrowsecurity OR NOT c.relforcerowsecurity);

-- security_invoker が付いていないビューと、マテリアライズドビュー
SELECT c.relname, c.relkind
  FROM pg_class c
 WHERE c.relnamespace = 'public'::regnamespace
   AND (   (c.relkind = 'v' AND NOT coalesce(c.reloptions @> ARRAY['security_invoker=true'], false))
        OR  c.relkind = 'm');

-- SECURITY DEFINER の関数
SELECT p.proname
  FROM pg_proc p
 WHERE p.pronamespace = 'public'::regnamespace AND p.prosecdef;
```

### ⑤ アプリの WHERE 句は消さない（多層防御）

RLS があっても、アプリ側の `WHERE tenant_id = ...` は残すことを推奨します。

- bypass ロールで動くバッチや管理機能では RLS が効かない
- ポリシーを付け忘れたテーブルがあっても、アプリ側の条件で止まる
- `tenant_id` 条件があるほうが、インデックスを使った実行計画になりやすい[^perf]

RLS は「書き忘れたときの安全網」であって、「書かなくてよくする仕組み」ではありません。

#### コラム：MyBatis にも同じような機能はないのか

素の MyBatis には、テナントの条件を自動で付ける機能はありません。あるのは、SQL の実行前に割り込める `Interceptor`（プラグイン）の仕組みだけです。これを使って自分で SQL を書き換えることはできますが、SQL を正しく解析する処理を自作するのは現実的ではありません。

同じ目的の機能を持っているのは、MyBatis の拡張ライブラリである **MyBatis-Plus** の `TenantLineInnerInterceptor` です。実行前の SQL を JSqlParser[^jsqlparser] で解析し、`tenant_id = ?` の条件（INSERT なら `tenant_id` の列と値）を自動で追加します。ほかの ORM では、Hibernate 6 の `@TenantId`、jOOQ の `VisitListener` が近い仕組みです。

RLS との一番の違いは、**どこで条件を足すか**です。

```
MyBatis-Plus : アプリが SQL を書き換えてから DB に送る   ← アプリ側の関所
RLS          : DB が受け取った SQL に条件を足す           ← DB 側の関所
```

| 観点 | MyBatis-Plus のテナントプラグイン | RLS |
|---|---|---|
| WHERE の書き忘れ・IDOR | ✅ 防げる | ✅ 防げる |
| MyBatis を通らない SQL（`JdbcTemplate`、psql、別のアプリ） | ❌ 素通り | ✅ 同じロールで接続していれば効く |
| 複雑な SQL（CTE、ウィンドウ関数、PostgreSQL 独自の構文） | ⚠️ パーサーが解析できないと、条件を足せない場合がある | ✅ DB が実行できる SQL ならすべて |
| 対象から外す設定 | ⚠️ `ignoreTable` の設定や、Mapper のメソッドに `@InterceptorIgnore(tenantLine = "true")` を付けるだけで外れる。レビューで必ず確認する | ポリシーを外すには DDL が必要。アプリのコードからは外せない |
| DB への依存 | ✅ DB を選ばない（MySQL などでも使える） | ❌ RLS を持つ DB（PostgreSQL など）だけ |
| 導入するもの | MyBatis-Plus | DB のポリシーと TransactionManager の差し替え |

ここで大事なのは、**第6章①の「テナントが正しく決まること」は、どちらの方式でもまったく同じ前提**だという点です。MyBatis-Plus も `TenantLineHandler#getTenantId()` で ThreadLocal などからテナント ID を取り出して SQL に埋め込むだけなので、テナント ID が偽物なら、偽物のテナントで SQL を正しく書き換えます。「誰として動いているか」を守れないのは、アプリ側の関所でも DB 側の関所でも同じです。

多層防御に当てはめると、次の組み合わせになります。

- 1枚目（アプリ）：手書きの `WHERE tenant_id = ...`、または MyBatis-Plus のテナントプラグイン
- 2枚目（DB）：RLS（最後の砦）

PostgreSQL を使うなら RLS は入れておくべき守りで、MyBatis-Plus は「手書きの WHERE の代わりに使うかどうか」という位置づけになります。導入する場合は、使っている Spring Boot のバージョンに対応しているかを公式のドキュメントで確認してください。

### ⑥ テストで「越境できないこと」を確認する

- テストデータは**必ず2テナント以上**用意する（1社分だけだと、WHERE 句の書き忘れが見つからない）
- 「自社のデータが見える」だけでなく、「他社のデータが見えない・消せない・作れない」ことを確認する（`OrderRlsIntegrationTest` のように）
- テナントの中の権限も同じ考え方で、「一般ユーザーには他人のデータが見えない・作れない」「管理者には見える」の両方を確認する（`OrderRoleAccessTest`）。アプリのチェックをすり抜けて Mapper を直接呼んでも DB が止めることまで確認しておく
- テストメソッドに `@Transactional` を付けない。テナントを設定する前にトランザクションが始まり、`set_config` が流れないため
- 本物の PostgreSQL に接続して確認する（H2 などの組み込み DB には RLS が無い）

## 第8章　テナントの中の権限 ── RESTRICTIVE と PERMISSIVE

テナントの壁ができると、次の要望が来ます。

> 「一般社員には自分の受注だけを見せたい。でも管理者には、部署全員の受注を見せて、代理で直させたい」

テナント分離は「他社が見えない」ことを守る仕組みで、「同じ会社の中で誰が何を見られるか」は別の問題です。このサンプルでは、この要望も RLS とアプリの両方で守る形に発展させました。

### データモデル

```
companies ─┬─ users          (id, tenant_id, subject, name, role: GENERAL / ADMIN)
           ├─ orders         (id, tenant_id, user_id → users, title)
           └─ order_details  (id, tenant_id, order_id → orders, item, quantity, unit_price)
```

設計上のポイントは3つあります。

- **子テーブル（`order_details`）にも `tenant_id` を持たせる**。第6章④の「`tenant_id` を持たない子テーブル」の抜け道をふさぐためです
- **外部キーを `(tenant_id, 親の id)` の複合にする**。`orders` は `(tenant_id, user_id)` で `users` を、`order_details` は `(tenant_id, order_id)` で `orders` を参照します。こうすると「A商事の受注に B工業のユーザーを付ける」「B工業の受注に A商事の明細をぶら下げる」といった、テナントをまたぐ関連を DB が作らせません。外部キーのチェックは RLS を無視して行われる[^sidechannel]ので、ここは RLS ではなく制約で守るのが確実です
- **ユーザーはテナントごとに別の行を持つ**。同じ人（同じ JWT の `sub`）でも、A商事と B工業でそれぞれ `users` の行があり、ロールもテナントごとに決まります。サンプルの `taro` は A商事では一般ユーザー、B工業では管理者です

### ロールは JWT ではなく DB から取る

ロールの出どころには、JWT のクレームに入れる方法と、DB の `users` テーブルから取る方法があります。このサンプルは DB を選びました。

| | JWT のクレーム | users テーブル（このサンプル） |
|---|---|---|
| ロールを変えたとき | トークンの有効期限が切れるまで反映されない | 次のリクエストからすぐに反映される |
| テナントごとに違うロール | クレームの構造が複雑になる | 行が分かれているので自然に表せる |
| コスト | DB を引かない | リクエストごとに users を1回引く |

`TenantFilter` は、テナントを確定させたあと、JWT の `sub` でそのテナントの `users` を引きます。このとき DB には `app.tenant_id` と `app.subject` だけが届いていて、RLS により「自分の行」だけが見えます。行が見つからなければ、トークンの `tenant_ids` にそのテナントが入っていても 403 です。IdP の設定と DB の登録の両方がそろって、はじめてそのテナントで動けます。

### ポリシーの2段構え

`orders` のポリシーは次のようになっています。

```sql
-- 1段目: テナントの壁（RESTRICTIVE）
CREATE POLICY tenant_isolation ON orders AS RESTRICTIVE
  USING      (tenant_id = current_tenant_id())
  WITH CHECK (tenant_id = current_tenant_id());

-- 2段目: テナントの中で、誰のデータまで扱えるか（PERMISSIVE）
CREATE POLICY owner_or_admin ON orders
  USING      (user_id = current_app_user_id() OR current_app_is_admin())
  WITH CHECK (user_id = current_app_user_id() OR current_app_is_admin());
```

PostgreSQL のポリシーには2種類あります[^restrictive]。

- **PERMISSIVE（許可）**：デフォルト。複数あると **OR** で効く。「どれか1つに当てはまれば見える」
- **RESTRICTIVE（制限）**：`AS RESTRICTIVE` を付けたもの。複数あると **AND** で効き、PERMISSIVE の結果にも **AND** で掛かる。「全部に当てはまらないと見えない」

実際に見える行は、**(RESTRICTIVE を全部 AND) AND (PERMISSIVE のどれか1つ)** です。

テナントの壁を RESTRICTIVE にしておく理由は、**将来の変更に強くするため**です。たとえば誰かが「監査担当は全部見える」という PERMISSIVE ポリシーを、テナント条件を書かずに追加したとします。

```sql
CREATE POLICY auditor_can_read ON orders FOR SELECT USING (current_setting('app.role') = 'AUDITOR');
```

テナントの壁も PERMISSIVE で書いていたら、これは OR で効くので、監査担当には**全テナント**の受注が見えてしまいます。RESTRICTIVE にしておけば、新しいポリシーにもテナントの壁が AND で掛かるので、見えるのは自社の受注だけです。「他テナントは絶対に見えない」という要件を、ポリシーを足す人の注意力に頼らず守れます。

なお、RESTRICTIVE だけでは何も許可されません。PERMISSIVE が1つもないテーブルは、常に 0 件になります。

### 明細は「親の受注が見えるか」で決める

`order_details` の PERMISSIVE ポリシーは、一般ユーザーか管理者かを自分では判定しません。

```sql
CREATE POLICY visible_parent_order ON order_details
  USING (EXISTS (SELECT 1 FROM orders o
                  WHERE o.tenant_id = order_details.tenant_id AND o.id = order_details.order_id))
  WITH CHECK (...同じ条件...);
```

ポリシーの中のサブクエリにも、`orders` の RLS が効きます。そのため「親の受注が見えるなら、その明細も見える・追加できる」という意味になり、権限の判定を `orders` の1か所に集められます。ロールのルールを変えるときに、明細のポリシーを直す必要はありません。明細に `user_id` を持たせて同じ条件を書く方法もありますが、そうすると親と子で `user_id` がずれる心配が生まれます。

### DB に届く値と、信頼の鎖

DB に届く値は、`app.tenant_id` / `app.subject` / `app.user_id` / `app.role` の4つになりました。どれもアプリが JWT と `users` テーブルから決めて、`TenantAwareTransactionManager` が `SET LOCAL` で設定します。

第6章の信頼の鎖は、そのままユーザーにも当てはまります。特に③の **SQL インジェクション**は、テナントよりも被害が大きくなります。`app.role` を `'ADMIN'` に書き換えられると、一般ユーザーが自社の全員のデータを読み書きできるからです（RESTRICTIVE があるので、他社までは届きません）。`${}` を使わないことの重要性は変わりません。

fail-closed の性質も保たれています。

| DB に届いている値 | 見える受注 |
|---|---|
| なし | 0 件 |
| テナントだけ（ユーザー未解決） | 0 件（自分の行でも管理者でもない） |
| テナント + 一般ユーザー | 自分の受注 |
| テナント + 管理者 | 自社の全員の受注 |

### アプリ側でも同じ判定をする（多層防御）

`OrderService` も、ロールに応じて SQL を出し分けています。一般ユーザーなら `WHERE tenant_id = ? AND user_id = ?`、管理者なら `WHERE tenant_id = ?` です。一般ユーザーが他人名義で受注を作ろうとした場合も、DB に届く前にアプリが 403 で止めます。

それでも、アプリのチェックをすり抜けた場合（Mapper を直接呼ぶ、WHERE を書き忘れる）は DB が止めます。`OrderRoleAccessTest` は、アプリが止める場合と DB が止める場合の両方をテストしています。

**403 と 404 の使い分け**にも注意しています。他社や他人の**既存の行**へのアクセスは、すべて 404（存在しない扱い）です。403 を返すのは「一般ユーザーは他人名義では作れない」「一般ユーザーはユーザー一覧を見られない」といった、**自分の権限についての情報**だけです。403 を返すと「その id の行は存在する」と相手に教えてしまうからです。

## 第9章　組織階層 ── 判断はアプリ、強制は DB

次に来たのは、組織の上下関係による要望です。

> 「部長には部員の受注を、事業部長には事業部全体の受注を見せたい」

この4パターン（一般・部長・事業部長・管理者）だけなら、RLS のポリシーで表現できます。しかし、兼務・代理・休暇中だけの権限といった仕様が増えていくと、ポリシーはどんどん複雑になります。かといって、判定をアプリに全部移すと、テナントの中で WHERE を書き忘れたときに、見えてはいけない人にデータが見えてしまいます。

そこで、**見える範囲の「判断」はアプリで行い、その結果だけを DB に渡して RLS が「強制」する**形にしました。どの選択肢を比べてこう決めたかは [ADR.md](ADR.md) に記録しています。

```
① AccessScopeResolver（アプリ）が、組織階層から「見てよい部署」の一覧を算出する
     部長 ken    → {第一営業部}
     事業部長 mika → {営業事業部, 第一営業部, 第二営業部}
② TenantAwareTransactionManager が、5つ目の値として DB に渡す
     SELECT set_config('app.visible_org_ids', '{1,2,3}', true)
③ RLS は「渡された一覧に入っているか」だけを見る
     USING (user_id = current_app_user_id()
            OR org_id = ANY (current_app_visible_org_ids())
            OR current_app_is_admin())
```

この形にした効果は3つあります。

- **RLS のポリシーが仕様の変化から切り離される**。兼務や代理が増えても、変わるのは①の算出ロジックだけです。③のポリシーは「渡された一覧に入っているか」のまま変わりません。将来、算出を OpenFGA のような認可サーバーに任せることになっても、置き換わるのは `AccessScopeResolver` の実装だけです
- **WHERE の書き忘れにも強いまま**。どの SQL にも、DB が「見てよい部署か」の条件を付けます。`OrgScopeAccessTest` は、WHERE を書き忘れた SQL でも部長・事業部長には範囲の中しか見えないことを確認しています
- **DB は階層をたどらない**。階層をたどる再帰 CTE はリクエストごとに1回だけ実行され、ポリシーの中で行ごとに再帰が走ることはありません。そのため、ltree のような専用の型も要りません

**受注は「作った時点の部署」のデータ**として扱います。`orders.org_id` に名義人のその時点の所属部署を記録するので、名義人が異動しても、過去の受注は元の部署のデータのままです（元の部長が引き続き見られます）。名義人本人は、名義（`user_id`）で引き続き自分の受注を見られます。

この形でも、第6章の信頼の鎖はそのまま当てはまります。SQL インジェクションで `app.visible_org_ids` を書き換えられると、テナント内の任意の部署のデータが見えてしまいます（RESTRICTIVE があるので、他テナントには届きません）。

## 終章　関所を数か所に絞る

序章の2つの事故は、RLS を入れたこのサンプルでは起きません。WHERE を書き忘れた `findAllWithoutTenantFilter` も、id だけで取る `findById` も、DB が自社の行に絞り込みます。

ただし、RLS は万能ではありません。テナント ID そのものが偽物なら、RLS は偽物のテナントのデータを正しく返します。ロール、ポリシーの付け忘れ、ビュー、キャッシュといった RLS の外側の抜け道も残ります。

RLS の本当の価値は、**守るべき場所を「すべての SQL」から「数か所の関所」に絞れること**です。テナントの決定、伝播、接続への設定、DB の設定。この数か所を厳しくレビューし、残りを CI の機械的なチェックで守ります。人が数百本の Mapper を見張り続けるより、ずっと確実な方法です。

### このサンプルに残っている宿題

| 宿題 | 内容 |
|---|---|
| JWT の検証方法 | デモ用の共有鍵（HS256）から、IdP の公開鍵（`issuer-uri`）による検証に切り替える（①） |
| 抜け道を再現するテスト | ビュー（`security_invoker` の有無）と、インジェクションによる `set_config` の書き換えを、「破られるとこうなる」テストとして追加する（③④） |
| CI のチェック | 第7章④の検出 SQL と、`${` の grep を CI に組み込む |
| テナントを横断する Root 権限 | 運用担当などが全テナントを扱える権限を追加する。BYPASSRLS ロールに頼らず、RESTRICTIVE ポリシーとどう両立させるかを設計する（第8章） |

---

## 脚注

[^rls]: **Row Level Security（行レベルセキュリティ）**。PostgreSQL 9.5 で導入された機能で、テーブルの「どの行を誰が見られるか・書けるか」を、ポリシーとして DB 側に定義します。通常の権限（`GRANT`）が「テーブル単位でアクセスできるか」を決めるのに対し、RLS はその上にかかる行単位の追加の絞り込みです。`GRANT` が無ければ RLS 以前にアクセスできず、`GRANT` があっても RLS のポリシーに合わない行は見えません。

[^multitenant]: **マルチテナント**。ひとつのシステムを複数の顧客企業（テナント）で共有する構成です。データの分け方には大きく3つあります。(1) テナントごとに DB を分ける、(2) テナントごとにスキーマを分ける、(3) 同じテーブルに `tenant_id` 列を持たせて行で分ける。(1)(2) は分離が強い代わりに、テナント数が増えると運用（マイグレーション、接続数）が重くなります。(3) は運用が軽い代わりに、条件の書き忘れがそのまま漏えいになります。RLS は (3) の弱点を DB 側で補う仕組みです。

[^idor]: **IDOR（Insecure Direct Object Reference）**。URL やパラメータのオブジェクト ID を書き換えるだけで、権限のないデータにアクセスできてしまう脆弱性です。OWASP Top 10 の「A01: Broken Access Control（アクセス制御の不備）」に含まれます。連番の id を推測しにくい UUID に変えても、ID が漏れれば同じことが起きるので、根本的な対策にはなりません。

[^policy]: **ポリシーの動き**。`USING` 句は既存の行に対する条件で、SELECT、UPDATE・DELETE の対象行の絞り込みに使われます。条件に合わない行は、エラーにならずに「存在しない」扱いになります（そのため UPDATE / DELETE は 0 行になる）。`WITH CHECK` 句は新しく書き込む行に対する条件で、INSERT と UPDATE 後の行に適用され、違反すると `new row violates row-level security policy` エラーになります。`WITH CHECK` を省略すると `USING` の条件が流用されますが、明示しておくほうが意図が読み取りやすくなります。ポリシーは `FOR SELECT` / `FOR INSERT` などコマンドごとにも定義でき、このサンプルのように `FOR` を省略すると `FOR ALL`（全コマンド）になります。

[^guc]: **カスタム設定パラメータ**。PostgreSQL では、`app.tenant_id` のようにドットを含む名前の設定値を、事前の定義なしに自由に作れます（GUC: Grand Unified Configuration の仕組み）。`set_config(name, value, is_local)` の第3引数が `true` なら `SET LOCAL`（トランザクション終了で消える）、`false` なら `SET`（セッションが終わるまで残る）と同じです。`current_setting(name, true)` の第2引数 `true` は「未定義なら NULL を返す（エラーにしない）」という意味です。この仕組みは手軽な反面、**どのロールでも自由に書き換えられる**ので、SQL インジェクションがあればテナントを切り替えられてしまいます。

[^jwt]: **JWT（JSON Web Token）**。「誰が、いつまで、何をできるか」といった情報（クレーム）を JSON で表し、発行者が署名したトークンです。受け取った側は署名を検証することで、中身が改ざんされていないこと、信頼できる発行者が発行したことを確認できます。中身は Base64URL でエンコードされているだけで暗号化はされていないので、誰でも読めます。守られているのは「読めないこと」ではなく「書き換えられないこと」です。このサンプルは手軽に試せるよう共有鍵の HS256 で署名していますが、共有鍵を持つ者は誰でもトークンを発行できてしまうため、本番では IdP（Keycloak、Auth0、Cognito、Entra ID など）が秘密鍵で署名し、アプリは公開鍵で検証する方式（RS256 など）を使います。Spring Security では `issuer-uri` を設定すると、公開鍵（JWKS）の取得と、発行者（`iss`）・有効期限（`exp`）の検証を自動で行います。

[^failclosed]: **fail-closed（フェイルクローズ）**。異常時や設定漏れのときに「安全側（アクセスを拒否する側）」に倒れる設計です。反対の fail-open は、異常時に「全部許可する」側に倒れます。セキュリティの仕組みは fail-closed にするのが原則です。

[^nullif]: **`NULLIF(..., '')` が必要な理由**。一度でも `SET LOCAL app.tenant_id` を実行したセッションでは、トランザクションが終わった後に `current_setting('app.tenant_id', true)` が NULL ではなく空文字 `''` を返すようになります（パラメータの「定義」だけがセッションに残るため）。コネクションプールでは接続が使い回されるので、2回目以降のトランザクションでは必ずこの状態になります。`''::bigint` はエラーになるので、`NULLIF` で空文字を NULL に変換してから型変換しています。このロジックを関数に閉じ込めておくと、すべてのポリシーで同じ書き方を使い回せます。

[^threadpool]: **スレッドプールと ThreadLocal**。Spring Boot に組み込まれた Tomcat は、リクエストごとにスレッドを作るのではなく、決まった数のスレッド（デフォルトで最大200）を使い回します。ThreadLocal は「スレッドごとの変数」なので、片付けずに処理を終えると、そのスレッドで次に処理されるリクエストが古い値を読んでしまいます。このサンプルの `TenantFilter` は、毎回 `runAs` で値を上書きしてから `finally` で消すので、この問題を避けられます。なお、`spring.threads.virtual.enabled=true` で仮想スレッドを使う場合、スレッド自体は使い回されなくなりますが、`runAs` の try/finally による片付けは同じように必要です（`@Async` の Executor など、プールを使う経路が残るため）。

[^hikari]: **HikariCP**。Spring Boot がデフォルトで使うコネクションプールです。DB 接続を作るコストは高いので、接続を作り置きして使い回します。そのため、セッション単位の状態（`SET` した設定値、一時テーブルなど）が次の利用者に引き継がれる危険があります。`SET LOCAL` はトランザクションと一緒に消えるので、この問題が起きません。PgBouncer などのプロキシをトランザクションモードで使う場合も、`SET LOCAL` ならそのまま動きます（セッション単位の `SET` は使えません）。

[^txhook]: **`prepareTransactionalConnection`**。Spring の `DataSourceTransactionManager`（およびそのサブクラスの `JdbcTransactionManager`）が、トランザクション開始時に接続を autoCommit=false にした直後に呼ぶ protected メソッドです。もともとは `SET TRANSACTION READ ONLY` の発行などに使うためのフックで、ここで追加の SQL を流すと、同じトランザクションの最初の文として実行されます。

[^bypass]: **RLS を素通りするロール**。(1) スーパーユーザー、(2) `BYPASSRLS` 属性を持つロールは、常にポリシーを無視します。(3) テーブルの所有者は、`FORCE ROW LEVEL SECURITY` が付いていない場合にポリシーを無視します。このサンプルでは、テーブルの所有者 `owner` がスーパーユーザーでもあるので、`FORCE` を付けても `owner` 自身には効きません。だからこそアプリは `app_user` で接続します。

[^view]: **ビューと RLS**。ビューを通じたテーブルへのアクセスは、デフォルトでは**ビューの所有者**の権限で行われ、RLS のポリシーも所有者に対して評価されます。所有者がスーパーユーザーや BYPASSRLS ロールなら、ポリシーは無視されます。所有者が RLS の効く一般ロールで、テーブルに `FORCE` が付いていれば、ポリシーは評価されます（`current_tenant_id()` はセッションの設定値を読むので、その場合は正しく絞り込まれます）。ただ、所有者の属性に安全性が左右されるのは危ういので、PostgreSQL 15 で追加された `security_invoker = true` を付け、**呼び出したユーザー**の権限で評価させるのが確実です。

[^mybatiscache]: **MyBatis のキャッシュ**。1次キャッシュ（ローカルキャッシュ）は SqlSession ごと、つまりおおむねトランザクションごとなので、テナントをまたいで共有されることはありません。2次キャッシュは Mapper の XML に `<cache/>` を書くと有効になり、複数の SqlSession で共有されます。キャッシュのキーは「SQL 文とパラメータ」なので、WHERE 句に tenant_id を書いていない SQL では、テナントが違っても同じキーになります。このサンプルの Mapper は2次キャッシュを使っていません。

[^sidechannel]: **サイドチャネル**。PostgreSQL の公式ドキュメントにも、RLS の注意点として記載されています。一意制約（UNIQUE）や外部キー制約のチェックは、整合性を保つために RLS を無視して全行に対して行われます。そのため、「この注文番号で登録しようとしたら重複エラーになった」という結果から、他社に同じ番号が存在することを推測できます。一意制約を `(tenant_id, 番号)` の複合にすると、この問題はほぼ避けられます。

[^restrictive]: **PERMISSIVE と RESTRICTIVE**。PostgreSQL 10 で RESTRICTIVE ポリシーが追加されました。それまでのポリシーはすべて PERMISSIVE（OR で結合）だったため、「どんな場合でも必ず満たすべき条件」を表すには、すべてのポリシーに同じ条件を書き写すしかありませんでした。RESTRICTIVE ポリシーは、あるコマンド（SELECT など）に PERMISSIVE ポリシーが1つ以上ある場合にだけ意味を持ち、そのすべてに AND で掛かります。テナント分離のように「例外を作ってはいけない条件」は RESTRICTIVE に、ロールや所有者のように「条件を足していく可能性がある許可」は PERMISSIVE に書くのが定石です。

[^jsqlparser]: **JSqlParser**。SQL 文を解析して、Java のオブジェクト（構文木）として扱えるようにするライブラリです。MyBatis-Plus のテナントプラグインは、この構文木の WHERE 句や JOIN の条件にテナントの条件を追加してから、SQL 文に戻しています。解析できる構文はライブラリのバージョンに依存するので、DB 独自の新しい構文はうまく扱えないことがあります。

[^archunit]: **ArchUnit**。Java のクラス間の依存関係やメソッドの呼び出し関係を、JUnit のテストとして検査できるライブラリです。「`TenantContext.runAs` を呼べるのは `..tenant..` パッケージ、バッチの起点、テストだけ」といったルールをコードで書き、違反があればテストを失敗させられます。

[^perf]: **RLS とパフォーマンス**。ポリシーの条件は通常の WHERE 句と同じようにプランナーに渡されるので、`tenant_id` 先頭のインデックス（このサンプルの `orders_tenant_id_idx`）があれば使われます。`current_tenant_id()` を `STABLE` と宣言しているのは、1回のクエリの中で値が変わらないことをプランナーに伝え、インデックスの検索キーとして使えるようにするためです。ただし、ユーザーが書いた条件に `LEAKPROOF` でない関数が含まれると、情報漏えいを防ぐためにポリシーの条件が先に評価され、実行計画が制約される場合があります。アプリ側でも `tenant_id` 条件を書いておくと、こうした場合も含めて安定した計画になりやすくなります。
