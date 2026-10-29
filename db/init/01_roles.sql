-- =============================================================
-- 01_roles.sql : 接続ロールを用途別に分ける
-- =============================================================
-- owner      : テーブル所有者（docker の POSTGRES_USER）。DDL専用。
-- app_user   : アプリケーションが普段使うロール。RLS が適用される。
-- app_bypass : バッチや運用管理用。BYPASSRLS を持ち RLS を素通りする。
--              このサンプルでは「RLS がない状態」を再現するデモ用にも使う。
-- =============================================================

CREATE ROLE app_user   LOGIN PASSWORD 'app_user';
CREATE ROLE app_bypass LOGIN PASSWORD 'app_bypass' BYPASSRLS;
