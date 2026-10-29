-- =============================================================
-- 03_data.sql : 2社分のデモデータ
--   初期化スクリプトはスーパーユーザー(owner)で実行されるため RLS は効かず、
--   どのテナントの行も投入できる。
--   テストはこの投入順で採番される ID（users 1〜7、orders 1〜5）を前提にしている。
-- =============================================================

INSERT INTO companies (id, name) VALUES
  (1, 'A商事'),
  (2, 'B工業');

-- taro は2社に所属し、A商事では一般ユーザー、B工業では管理者
INSERT INTO users (tenant_id, subject, name, role) VALUES
  (1, 'alice', 'A商事 アリス（管理者）', 'ADMIN'),    -- id=1
  (1, 'bob',   'A商事 ボブ',            'GENERAL'),  -- id=2
  (1, 'carol', 'A商事 キャロル',        'GENERAL'),  -- id=3
  (1, 'taro',  'A商事 太郎',            'GENERAL'),  -- id=4
  (2, 'dave',  'B工業 デイブ（管理者）', 'ADMIN'),    -- id=5
  (2, 'erin',  'B工業 エリン',          'GENERAL'),  -- id=6
  (2, 'taro',  'B工業 太郎（管理者）',   'ADMIN');    -- id=7

INSERT INTO orders (tenant_id, user_id, title) VALUES
  (1, 2, 'A商事 ノートPC調達（ボブ）'),        -- id=1
  (1, 3, 'A商事 モニター購入（キャロル）'),     -- id=2
  (2, 6, 'B工業 産業用ロボット（エリン）'),     -- id=3
  (2, 5, 'B工業 制御盤（デイブ）'),            -- id=4
  (1, 1, 'A商事 オフィスチェア（アリス）');     -- id=5

INSERT INTO order_details (tenant_id, order_id, item, quantity, unit_price) VALUES
  (1, 1, 'ノートPC',       3,  150000),
  (1, 1, 'マウス',         3,    3000),
  (1, 2, 'モニター',       5,   40000),
  (2, 3, '産業用ロボット', 1, 8000000),
  (2, 4, '制御盤',         2, 1200000),
  (1, 5, 'オフィスチェア', 2,   60000);
