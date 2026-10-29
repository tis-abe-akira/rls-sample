---
type: tutorial
title: チュートリアル：テナント所有のテーブルを1つ追加する
description: 受注に付けるコメント（order_comments）を題材に、テナント所有のテーブルを追加して RLS で守るまでを8ステップで説明する。各ステップで、何をするか・なぜ必要か・忘れると何が起きるかを示す。
tags: [tutorial, rls, schema, mybatis, testing, step-by-step]
verified:
  - by: openwiki/0.7.2
    at: 2026-10-29T20:48:55.949Z
sources:
  - id: openwiki-source-e201e686a785f09b6d899f0b
    resource: repo://compose.yaml
  - id: openwiki-source-ae9507c4a442eab54c70e7da
    resource: repo://db/init/02_schema.sql
  - id: openwiki-source-23775c3de52f3ab95a13cb8b
    resource: repo://README.md
  - id: openwiki-source-d14c00b0c82b0bb4d07c161d
    resource: repo://src/main/java/com/example/rlssample/order/OrderService.java
  - id: openwiki-source-b823e8e08b7b68930a5702d6
    resource: repo://src/test/java/com/example/rlssample/order/OrderRoleAccessTest.java
  - id: openwiki-source-e7dba39fd3249c85dfcc11d5
    resource: repo://src/test/java/com/example/rlssample/support/Actor.java
generated: { by: "claude-code", at: "2026-10-29T20:48:55.949Z" }
---

# チュートリアル：テナント所有のテーブルを1つ追加する

受注に「コメント」を付けられるようにします。題材は `order_comments` という小さなテーブルで、このリポジトリにある部品だけで作れます。

> このページのコード例は **チュートリアル用の例で、リポジトリには含まれていません**。書き方は、既存の `db/init/02_schema.sql`、Mapper の XML、テストに合わせています。

## ゴール

- コメントは受注に紐づく。**親の受注が見える人だけ** がコメントを読める・書ける
- 他テナントのコメントは、WHERE を書き忘れても、id を指定しても見えない
- テストで「見えない・作れない・消せない」を確かめる

権限のルールは、明細（`order_details`）とまったく同じ「親の受注が見えるか」にします。一般・管理職・管理者の判定は `orders` のポリシーに任せられるので、新しいテーブルに権限のロジックを書かずに済みます。

## Step 1. テーブルを定義する

`db/init/02_schema.sql` の `order_details` の定義の後ろに追加します。

```sql
CREATE TABLE order_comments (
  id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id  bigint NOT NULL,
  order_id   bigint NOT NULL,
  body       text   NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id, order_id) REFERENCES orders (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX order_comments_tenant_order_idx ON order_comments (tenant_id, order_id);
```

- **何を**: 子テーブルにも `tenant_id` を持たせ、外部キーを `(tenant_id, order_id)` の複合にする。参照先の `orders` には、すでに `UNIQUE (tenant_id, id)` がある
- **なぜ**: RLS は行の `tenant_id` で絞るので、子テーブルにも列が要る。複合外部キーにすると、「親と違うテナントのコメント」を DB が作らせない
- **忘れると**: `tenant_id` がないと、親を経由せずに `order_comments` を直接 SELECT されたときに全社分が見える。外部キーが `order_id` だけだと、`tenant_id` と親のテナントが食い違った行を作れてしまう

## Step 2. RLS を有効にする

```sql
ALTER TABLE order_comments ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_comments FORCE  ROW LEVEL SECURITY;
```

- **何を**: `ENABLE` と `FORCE` の両方を付ける
- **なぜ**: `ENABLE` だけでは、テーブル所有者は RLS を素通りする
- **忘れると**: `ENABLE` を忘れるとポリシーを書いても何も効かない。`FORCE` を忘れると、所有者で接続した経路から全件見える

## Step 3. ポリシーを書く

```sql
-- テナントの壁（必ず RESTRICTIVE）
CREATE POLICY tenant_isolation ON order_comments AS RESTRICTIVE
  USING      (tenant_id = current_tenant_id())
  WITH CHECK (tenant_id = current_tenant_id());

-- 「親の受注が見えるなら、そのコメントも見える・書ける」
CREATE POLICY visible_parent_order ON order_comments
  USING      (EXISTS (SELECT 1 FROM orders o
                       WHERE o.tenant_id = order_comments.tenant_id AND o.id = order_comments.order_id))
  WITH CHECK (EXISTS (SELECT 1 FROM orders o
                       WHERE o.tenant_id = order_comments.tenant_id AND o.id = order_comments.order_id));
```

- **何を**: RESTRICTIVE なテナントの壁と、PERMISSIVE な「親の受注が見えるか」の2段にする。`order_details` のポリシーと同じ形
- **なぜ**: ポリシーの中のサブクエリにも `orders` の RLS が効くので、一般は自分の受注、管理職は範囲の部署の受注、管理者は全部、という判定がそのまま引き継がれる。`WITH CHECK` も付けるのは、見えない受注にコメントを書かせないため
- **忘れると**: テナントの壁を PERMISSIVE で書くと、ほかの PERMISSIVE ポリシーと OR で効くので、あとで緩いポリシーを足したときに他テナントが見える。PERMISSIVE を1つも書かないと、RESTRICTIVE だけでは何も許可されず 0 件になる

## Step 4. 権限を付与する

```sql
GRANT SELECT, INSERT, UPDATE, DELETE ON order_comments TO app_user, app_bypass;
```

`02_schema.sql` の最後の `GRANT` の並びに足します。シーケンスへの `USAGE` は、すでに `ALL SEQUENCES IN SCHEMA public` で付与しているので、`GRANT` より前に `CREATE TABLE` が流れていれば追加は要りません。

- **なぜ**: RLS は GRANT の **上に** かかる絞り込みで、権限そのものは与えない
- **忘れると**: アプリから `permission denied for table order_comments` になる

## Step 5. デモデータを足して、DB を作り直す

`db/init/03_data.sql` の **末尾** に追加します。

```sql
INSERT INTO order_comments (tenant_id, order_id, body) VALUES
  (1, 1, 'ノートPCは来週納品予定'),   -- A商事・ボブの受注
  (1, 2, 'モニターの型番を確認中'),   -- A商事・キャロルの受注
  (2, 3, 'ロボットの設置日を調整中'); -- B工業・エリンの受注
```

```bash
docker compose down -v && docker compose up -d
```

- **なぜ末尾か**: テストは投入順で採番される ID（organizations 1〜6、users 1〜9、orders 1〜6）を前提にしている。既存の INSERT の間に入れると ID がずれる
- **なぜ作り直すか**: 初期化スクリプトは DB が空のときしか流れない
- **2テナント分入れる理由**: 1社分だけだと、テナントの壁が効いていなくてもテストが通ってしまう

## Step 6. Mapper と Service を作る

Mapper は `OrderDetailMapper` と同じ形です。

```xml
<!-- src/main/resources/mapper/OrderCommentMapper.xml（例） -->
<mapper namespace="com.example.rlssample.order.OrderCommentMapper">
    <select id="findByOrderId" resultType="com.example.rlssample.order.OrderComment">
        SELECT id, tenant_id, order_id, body
          FROM order_comments
         WHERE tenant_id = #{tenantId}
           AND order_id  = #{orderId}
         ORDER BY id
    </select>

    <!-- ❌ テスト用にわざと WHERE を書き忘れた SQL。RLS が効いていることを確かめる -->
    <select id="findAllWithoutTenantFilter" resultType="com.example.rlssample.order.OrderComment">
        SELECT id, tenant_id, order_id, body
          FROM order_comments
         ORDER BY id
    </select>

    <select id="insert" resultType="long" flushCache="true">
        INSERT INTO order_comments (tenant_id, order_id, body)
        VALUES (#{tenantId}, #{orderId}, #{body})
        RETURNING id
    </select>
</mapper>
```

Service は、`OrderService#addDetail` と同じく、先に親の受注を引いて、見えなければ 404 にします。

```java
// 例: OrderCommentService
@Service
@Transactional
public class OrderCommentService {

    // コンストラクタは省略

    public long addComment(long orderId, String body) {
        Order order = orderMapper.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        return orderCommentMapper.insert(order.tenantId(), orderId, body);
    }

    @Transactional(readOnly = true)
    public List<OrderComment> listComments(long orderId) {
        Order order = orderMapper.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        return orderCommentMapper.findByOrderId(order.tenantId(), orderId);
    }
}
```

- **`@Transactional` をクラスに付ける**: テナントとユーザーはトランザクション開始時に DB に入る。付け忘れると 0 件になる（漏れはしないが、気付きにくい）
- **アプリ側の WHERE も書く**: RLS があっても `tenant_id` で絞る（多層防御）。`BYPASSRLS` のロールで動く経路では、これが唯一の守りになる
- **リクエストに `tenant_id` を含めない**: Controller のリクエストは `body` だけにし、`tenant_id` は親の受注（＝ RLS で見えた行）から取る
- **見えない受注は 404**: 403 にすると「その id の受注は存在する」と教えてしまう

## Step 7. テストを書く

`OrderRoleAccessTest` と同じ書き方で、`Actor` を使って「誰として動くか」を切り替えます。

```java
// 例: OrderCommentAccessTest
@SpringBootTest
class OrderCommentAccessTest {

    @Test
    @DisplayName("一般ユーザーは、自分の受注にはコメントできるが、他人の受注は 404")
    void general_canCommentOnlyOwnOrder() {
        assertThat(BOB.run(() -> commentService.listComments(1L))).isNotEmpty();
        assertThatThrownBy(() -> BOB.run(() -> commentService.addComment(2L, "勝手に")))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    @DisplayName("コメントを WHERE なしで直接 SELECT しても、管理者に見えるのは自社分だけ")
    void leakyQuery_isIsolatedByRls() {
        List<OrderComment> comments = ALICE.run(() -> tx.execute(status -> commentMapper.findAllWithoutTenantFilter()));
        assertThat(comments).isNotEmpty()
                .allSatisfy(c -> assertThat(c.tenantId()).isEqualTo(A_SHOJI.value()));
    }

    @Test
    @DisplayName("アプリをすり抜けても、他社の受注へのコメントは DB（WITH CHECK）が拒否する")
    void otherTenantInsert_isRejectedByDatabase() {
        assertThatThrownBy(() -> ALICE.run(() ->
                tx.execute(status -> commentMapper.insert(B_KOGYO.value(), 3L, "なりすまし"))))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security policy");
    }
}
```

- **何を確かめるか**: 見える（自分・自社）だけでなく、**見えない・作れない・消せない**（他人・他社）を確かめる。管理者でも他社は見えないことを、管理者で確かめるのがポイント
- **テストに `@Transactional` を付けない**: `runAs` でテナントを設定する前にトランザクションが始まり、何も見えないテストになる
- **DB だけの守りを確かめる**: Mapper を `TransactionTemplate` の中で直接呼ぶ。トランザクションがないと 0 件になってしまう

テストの全体像は [テスト：越境できないことの確認](../testing/rls-test-suite.md) を参照してください。

## Step 8. RLS の付け忘れがないか確かめる

README の「本番に向けて足すもの」にある検出クエリを、`owner` で流します。

```sql
SELECT c.relname
  FROM pg_class c
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id'
 WHERE c.relkind = 'r' AND (NOT c.relrowsecurity OR NOT c.relforcerowsecurity);
```

`tenant_id` 列を持つのに、`ENABLE` か `FORCE` が付いていないテーブルが出てきます。何も返らなければ OK です。CI に組み込んでおくと、テーブルを増やしたときの付け忘れを機械的に見つけられます。

## つまずきやすい点

| 落とし穴 | 何が起きるか | 対処 |
|---|---|---|
| `FORCE` の付け忘れ | テーブル所有者で接続した経路から全件見える | `ENABLE` と `FORCE` をセットで書き、Step 8 のクエリで確かめる |
| テナントの壁を PERMISSIVE で書く | あとから足した PERMISSIVE と OR で効き、他テナントが見える | テナントの壁は必ず `AS RESTRICTIVE` |
| 子テーブルに `tenant_id` がない | 子テーブルを直接 SELECT されると全件見える | 子テーブルにも `tenant_id` を持たせ、外部キーを複合にする |
| テストに `@Transactional` | テナントが設定されず、何も見えないテストが「通って」しまう | `Actor#run` の内側でトランザクションを始める |
| デモデータを途中に挿入 | 既存テストの ID の前提が崩れる | 新しいテーブルの INSERT は末尾に足す |
| DB を作り直さない | 新しいテーブルができていない | `docker compose down -v && docker compose up -d` |

ポリシーの全体像は [スキーマと RLS ポリシー](../database/schema-and-rls-policies.md)、API の作り方は [受注・ユーザー API と Mapper](../domain/orders-and-users-api.md) にあります。
