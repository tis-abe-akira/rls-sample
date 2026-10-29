# ファイル

- [組織階層による見える範囲（AccessScope と AccessScopeResolver）](org-hierarchy-scope.md) - 部長・事業部長が配下の部署のデータを扱える仕組み。アプリが組織階層から「見てよい部署の一覧」を算出して app.visible_org_ids として DB に渡し、RLS が org_id = ANY(...) で強制する。
