#!/usr/bin/env bash
# =============================================================
# token.sh : デモ用の JWT（HS256）を発行する
#   scripts/token.sh bob 1       → A商事だけに所属する bob        sub=bob  tenant_ids=[1]
#   scripts/token.sh taro 1 2    → 2社に所属する taro             sub=taro tenant_ids=[1,2]
# ユーザーIDとロールはトークンに入れない（アプリが users テーブルで決める）。
# 本番ではトークンは IdP が発行する。これはローカルで試すためだけのもの。
# =============================================================
set -euo pipefail

SECRET="${JWT_SECRET:-rls-sample-demo-secret-change-me-0123456789}"   # application.yml の app.jwt.secret

if [ $# -lt 2 ]; then
  echo "usage: $0 SUBJECT TENANT_ID [TENANT_ID...]" >&2
  exit 1
fi
subject="$1"
shift
[[ "$subject" =~ ^[A-Za-z0-9_.@-]+$ ]] || { echo "subject must be alphanumeric: $subject" >&2; exit 1; }
for t in "$@"; do
  [[ "$t" =~ ^[0-9]+$ ]] || { echo "tenant id must be a number: $t" >&2; exit 1; }
done

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

tenants=$(IFS=,; echo "$*")
now=$(date +%s)
header=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
payload=$(printf '{"sub":"%s","tenant_ids":[%s],"iat":%d,"exp":%d}' "$subject" "$tenants" "$now" $((now + 3600)) | b64url)
signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -hmac "$SECRET" -binary | b64url)

echo "$header.$payload.$signature"
