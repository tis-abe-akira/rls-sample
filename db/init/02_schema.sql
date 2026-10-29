-- =============================================================
-- 02_schema.sql : テーブル定義と RLS ポリシー
-- =============================================================

-- -------------------------------------------------------------
-- 「今の接続は誰か」を返す関数たち
--   アプリはトランザクション開始時に、次の値を SET LOCAL 相当で設定する
--   （TenantAwareTransactionManager）。
--     app.tenant_id : テナント（JWT のクレームとヘッダで確定）
--     app.subject   : JWT の sub（users テーブルで自分の行を引くために使う）
--     app.user_id   : users テーブルで解決したユーザーID
--     app.role      : users テーブルで解決したロール（GENERAL / ADMIN）
--
--   NULLIF(..., '') が重要:
--   一度でも SET LOCAL したセッションでは、トランザクション終了後に
--   current_setting が NULL ではなく空文字 '' を返す。
--   ''::bigint はエラーになるので、空文字を NULL に変換しておく。
--   NULL との比較は常に偽 → 「未設定なら 0 件」= fail-closed になる。
-- -------------------------------------------------------------
CREATE FUNCTION current_tenant_id() RETURNS bigint
  LANGUAGE sql STABLE
AS $$
  SELECT NULLIF(current_setting('app.tenant_id', true), '')::bigint
$$;

CREATE FUNCTION current_app_subject() RETURNS text
  LANGUAGE sql STABLE
AS $$
  SELECT NULLIF(current_setting('app.subject', true), '')
$$;

CREATE FUNCTION current_app_user_id() RETURNS bigint
  LANGUAGE sql STABLE
AS $$
  SELECT NULLIF(current_setting('app.user_id', true), '')::bigint
$$;

-- ロール未設定は「管理者ではない」として扱う（fail-closed）
CREATE FUNCTION current_app_is_admin() RETURNS boolean
  LANGUAGE sql STABLE
AS $$
  SELECT coalesce(current_setting('app.role', true) = 'ADMIN', false)
$$;

-- -------------------------------------------------------------
-- 企業マスタ（テナント自体）。共有マスタの例として RLS はかけない。
-- -------------------------------------------------------------
CREATE TABLE companies (
  id   bigint PRIMARY KEY,
  name text   NOT NULL
);

-- -------------------------------------------------------------
-- ユーザー（テナント所有テーブル）
--   同じ人（同じ subject）でも、テナントごとに別の行を持つ。
--   ロールもテナントごとに決まる（A商事では一般、B工業では管理者、など）。
-- -------------------------------------------------------------
CREATE TABLE users (
  id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id bigint NOT NULL REFERENCES companies(id),
  subject   text   NOT NULL,   -- IdP が発行する JWT の sub
  name      text   NOT NULL,
  role      text   NOT NULL CHECK (role IN ('GENERAL', 'ADMIN')),
  UNIQUE (tenant_id, subject),
  UNIQUE (tenant_id, id)       -- 子テーブルからの複合外部キーの参照先
);

-- -------------------------------------------------------------
-- 受注（テナント所有テーブル）
--   (tenant_id, user_id) の複合外部キーで、「別テナントのユーザーの受注」を DB が作らせない。
-- -------------------------------------------------------------
CREATE TABLE orders (
  id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id  bigint NOT NULL REFERENCES companies(id),
  user_id    bigint NOT NULL,
  title      text   NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),      -- 明細からの複合外部キーの参照先（tenant_id 先頭のインデックスも兼ねる）
  FOREIGN KEY (tenant_id, user_id) REFERENCES users (tenant_id, id)
);

CREATE INDEX orders_tenant_user_idx ON orders (tenant_id, user_id);

-- -------------------------------------------------------------
-- 受注明細（子テーブル）
--   親を経由しないと読めないつもりでも、子テーブルを直接 SELECT されることはある。
--   なので子テーブルにも tenant_id を持たせて RLS をかける。
--   (tenant_id, order_id) の複合外部キーで、「親と違うテナントの明細」を DB が作らせない。
-- -------------------------------------------------------------
CREATE TABLE order_details (
  id         bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id  bigint  NOT NULL,
  order_id   bigint  NOT NULL,
  item       text    NOT NULL,
  quantity   integer NOT NULL CHECK (quantity > 0),
  unit_price integer NOT NULL CHECK (unit_price >= 0),
  FOREIGN KEY (tenant_id, order_id) REFERENCES orders (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX order_details_tenant_order_idx ON order_details (tenant_id, order_id);

-- -------------------------------------------------------------
-- RLS 有効化
--   ENABLE : RLS をオンにする
--   FORCE  : テーブル所有者にも RLS を適用する（付けないと所有者は素通り）
--   ※ スーパーユーザーと BYPASSRLS ロールは FORCE でも素通りする
-- -------------------------------------------------------------
ALTER TABLE users         ENABLE ROW LEVEL SECURITY;
ALTER TABLE users         FORCE  ROW LEVEL SECURITY;
ALTER TABLE orders        ENABLE ROW LEVEL SECURITY;
ALTER TABLE orders        FORCE  ROW LEVEL SECURITY;
ALTER TABLE order_details ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_details FORCE  ROW LEVEL SECURITY;

-- -------------------------------------------------------------
-- ポリシーは2段構え
--
--   RESTRICTIVE（制限）: テナントの壁。すべての RESTRICTIVE ポリシーが AND で効く。
--   PERMISSIVE （許可）: テナントの中で「誰のデータまで見えるか」。PERMISSIVE 同士は OR で効く。
--
--   実際の条件 = (RESTRICTIVE を全部 AND) AND (PERMISSIVE のどれか1つ)
--
--   テナントの壁を RESTRICTIVE にしておくと、あとで誰かが PERMISSIVE ポリシーを足し間違えても
--   （例: 「管理者は全部見える」を tenant 条件なしで書いた）、他テナントは見えないままになる。
--   なお、RESTRICTIVE だけでは何も許可されない（PERMISSIVE が1つもないと 0 件）。
--
--   USING      : SELECT / UPDATE / DELETE で「見える行」を絞る
--   WITH CHECK : INSERT / UPDATE で「書き込める行」を絞る
-- -------------------------------------------------------------

-- ---- users -------------------------------------------------
CREATE POLICY tenant_isolation ON users AS RESTRICTIVE
  USING      (tenant_id = current_tenant_id())
  WITH CHECK (tenant_id = current_tenant_id());

-- 一般ユーザーは自分の行だけ、管理者はテナント内の全員が見える
CREATE POLICY users_select ON users FOR SELECT
  USING (subject = current_app_subject() OR current_app_is_admin());

-- ユーザーの追加・変更・削除は管理者だけ
CREATE POLICY users_insert ON users FOR INSERT
  WITH CHECK (current_app_is_admin());
CREATE POLICY users_update ON users FOR UPDATE
  USING (current_app_is_admin()) WITH CHECK (current_app_is_admin());
CREATE POLICY users_delete ON users FOR DELETE
  USING (current_app_is_admin());

-- ---- orders ------------------------------------------------
CREATE POLICY tenant_isolation ON orders AS RESTRICTIVE
  USING      (tenant_id = current_tenant_id())
  WITH CHECK (tenant_id = current_tenant_id());

-- 一般ユーザーは自分の受注だけ、管理者はテナント内の全員の受注を読み書きできる
--   WITH CHECK により、一般ユーザーは「他人名義の受注」を作れないし、自分の受注を他人に付け替えられない
CREATE POLICY owner_or_admin ON orders
  USING      (user_id = current_app_user_id() OR current_app_is_admin())
  WITH CHECK (user_id = current_app_user_id() OR current_app_is_admin());

-- ---- order_details -----------------------------------------
CREATE POLICY tenant_isolation ON order_details AS RESTRICTIVE
  USING      (tenant_id = current_tenant_id())
  WITH CHECK (tenant_id = current_tenant_id());

-- 「親の受注が見えるなら、その明細も見える」
--   ポリシーの中のサブクエリにも orders の RLS が効くので、
--   一般/管理者の判定を orders の1か所に集められる。
CREATE POLICY visible_parent_order ON order_details
  USING      (EXISTS (SELECT 1 FROM orders o
                       WHERE o.tenant_id = order_details.tenant_id AND o.id = order_details.order_id))
  WITH CHECK (EXISTS (SELECT 1 FROM orders o
                       WHERE o.tenant_id = order_details.tenant_id AND o.id = order_details.order_id));

-- -------------------------------------------------------------
-- 権限付与（RLS は GRANT の「上に」かかる追加の絞り込み）
-- -------------------------------------------------------------
GRANT SELECT                         ON companies     TO app_user, app_bypass;
GRANT SELECT, INSERT, UPDATE, DELETE ON users         TO app_user, app_bypass;
GRANT SELECT, INSERT, UPDATE, DELETE ON orders        TO app_user, app_bypass;
GRANT SELECT, INSERT, UPDATE, DELETE ON order_details TO app_user, app_bypass;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public         TO app_user, app_bypass;
