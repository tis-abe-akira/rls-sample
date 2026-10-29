# ファイル

- [DB へのコンテキスト受け渡し（ThreadLocal と SET LOCAL）](db-session-context.md) - TenantContext / UserContext の ThreadLocal に入れたテナントとユーザーを、TenantAwareTransactionManager がトランザクション開始直後に set_config(..., true) で同じ接続へ渡す仕組み。値が残らない理由、未設定時の fail-closed、途中での切り替えを禁止する理由をまとめる。
- [テナントとユーザーの確定（JWT・TenantFilter・UserService）](tenant-and-user-resolution.md) - JWT の検証のあと、TenantFilter がクレーム tenant_ids と X-Tenant-Id ヘッダからテナントを決め、そのテナントの users テーブルを sub で引いてユーザー ID・ロール・見てよい部署を決める流れ。401 / 400 / 403 を返す条件と、その理由をまとめる。
