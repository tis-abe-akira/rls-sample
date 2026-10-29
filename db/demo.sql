-- =============================================================
-- demo.sql : psql だけで RLS の挙動を体験するスクリプト
--   docker exec -i rls-sample-db psql -U app_user -d rls_sample < db/demo.sql
--
--   アプリはトランザクション開始時に、次の5つを SET LOCAL 相当で設定する。
--   ここでは同じことを手で打っている。
--     app.tenant_id / app.subject / app.user_id / app.role / app.visible_org_ids
--   app.visible_org_ids（見てよい部署）は、アプリの AccessScopeResolver が組織階層から算出する。
-- =============================================================
\echo '--- 1) 何も設定せずに WHERE なし SELECT → 0 件（fail-closed）'
SELECT id, tenant_id, user_id, title FROM orders;

\echo '--- 2) テナント=1 だけ設定（ユーザー未解決）→ それでも 0 件（テナントの壁を越えても、誰のデータかが決まらない）'
BEGIN;
SELECT set_config('app.tenant_id', '1', true);
SELECT id, tenant_id, user_id, title FROM orders;
COMMIT;

\echo '--- 3) A商事の管理者 alice → A商事の全員の受注（B工業の行は出ない）'
BEGIN;
SELECT set_config('app.tenant_id', '1', true), set_config('app.subject', 'alice', true),
       set_config('app.user_id', '1', true),   set_config('app.role', 'ADMIN', true);
SELECT id, tenant_id, user_id, title FROM orders;

\echo '--- 4) 管理者でも、B工業の行を id 指定で取りに行く（IDOR）→ 0 件'
SELECT id, tenant_id, user_id, title FROM orders WHERE id = 3;   -- id=3 は B工業の受注

\echo '--- 5) 管理者でも、B工業の行を UPDATE / DELETE → 0 行'
UPDATE orders SET title = '改ざん' WHERE tenant_id = 2;
DELETE FROM orders WHERE tenant_id = 2;
COMMIT;

\echo '--- 6) A商事の一般ユーザー bob → 自分の受注だけ（キャロル・アリスの受注は出ない）'
BEGIN;
SELECT set_config('app.tenant_id', '1', true), set_config('app.subject', 'bob', true),
       set_config('app.user_id', '2', true),   set_config('app.role', 'GENERAL', true);
SELECT id, tenant_id, user_id, title FROM orders;

\echo '--- 7) bob は明細も自分の受注の分だけ（親の受注が見えるかで決まる）'
SELECT id, order_id, item, quantity FROM order_details;

\echo '--- 8) bob は users テーブルも自分の行だけ'
SELECT id, subject, name, role FROM users;

\echo '--- 9) bob がキャロルの受注(id=2)を UPDATE → 0 行'
UPDATE orders SET title = '改ざん' WHERE id = 2;
COMMIT;

\echo '--- 10) bob がキャロル名義(user_id=3)の受注を INSERT → WITH CHECK 違反でエラー'
BEGIN;
SELECT set_config('app.tenant_id', '1', true), set_config('app.subject', 'bob', true),
       set_config('app.user_id', '2', true),   set_config('app.role', 'GENERAL', true);
INSERT INTO orders (tenant_id, user_id, org_id, title) VALUES (1, 3, 3, 'なりすまし注文');
ROLLBACK;

\echo '--- 11) bob がキャロルの受注(id=2)に明細を INSERT → 親が見えないので WITH CHECK 違反でエラー'
BEGIN;
SELECT set_config('app.tenant_id', '1', true), set_config('app.subject', 'bob', true),
       set_config('app.user_id', '2', true),   set_config('app.role', 'GENERAL', true);
INSERT INTO order_details (tenant_id, order_id, item, quantity, unit_price) VALUES (1, 2, '勝手に追加', 1, 1);
ROLLBACK;

\echo '--- 12) 第一営業部の部長 ken → アプリが算出した「見てよい部署」= {2}。第一営業部の受注（ボブ・ケン）だけ'
BEGIN;
SELECT set_config('app.tenant_id', '1', true), set_config('app.subject', 'ken', true),
       set_config('app.user_id', '8', true),   set_config('app.role', 'MANAGER', true),
       set_config('app.visible_org_ids', '{2}', true);
SELECT id, user_id, org_id, title FROM orders;
COMMIT;

\echo '--- 13) 営業事業部の事業部長 mika → 「見てよい部署」= {1,2,3}。配下の部の受注も見える（管理本部のアリスの受注は出ない）'
BEGIN;
SELECT set_config('app.tenant_id', '1', true), set_config('app.subject', 'mika', true),
       set_config('app.user_id', '9', true),   set_config('app.role', 'MANAGER', true),
       set_config('app.visible_org_ids', '{1,2,3}', true);
SELECT id, user_id, org_id, title FROM orders;

\echo '--- 14) mika は範囲の外（管理本部 org_id=4）の受注を作れない → WITH CHECK 違反でエラー'
INSERT INTO orders (tenant_id, user_id, org_id, title) VALUES (1, 1, 4, '範囲外の部署の受注');
ROLLBACK;

\echo '--- 15) トランザクション終了後 → 設定は消えて再び 0 件'
SELECT id, tenant_id, user_id, title FROM orders;
