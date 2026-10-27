#!/usr/bin/env bash
set -euo pipefail

SSO_BASE_URL="${SSO_BASE_URL:-http://127.0.0.1:8080}"
AUTH_TOKEN="${AUTH_TOKEN:-test-token}"
REDIRECT_URI="${REDIRECT_URI:-http://fastgpt.local/login/callback}"
STATE="${STATE:-local-state}"
MOCK_CAS_HOST="${MOCK_CAS_HOST:-mock-cas}"
MOCK_CAS_PORT="${MOCK_CAS_PORT:-18080}"
SSO_CALLBACK_HOST="${SSO_CALLBACK_HOST:-fastgpt-sso}"
SSO_CALLBACK_PORT="${SSO_CALLBACK_PORT:-8080}"

urlencode() {
  python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1"
}

extract_header() {
  python3 - "$1" "$2" <<'PY'
import sys

path, header_name = sys.argv[1], sys.argv[2].lower()
with open(path, "r", encoding="iso-8859-1") as f:
    for line in f:
        if line.lower().startswith(header_name + ":"):
            print(line.split(":", 1)[1].strip())
            break
PY
}

extract_auth_url() {
  python3 -c 'import json, sys
payload = json.load(sys.stdin)
auth_url = payload.get("authURL")
if not auth_url:
    raise SystemExit("authURL not found in response: " + json.dumps(payload, ensure_ascii=False))
print(auth_url)'
}

extract_code() {
  python3 - "$1" <<'PY'
import sys
from urllib.parse import parse_qs, urlparse

location = sys.argv[1]
code = parse_qs(urlparse(location).query).get("code", [""])[0]
if not code:
    raise SystemExit("code not found in redirect location: " + location)
print(code)
PY
}

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

encoded_redirect_uri="$(urlencode "$REDIRECT_URI")"
encoded_state="$(urlencode "$STATE")"

echo "1) requesting FastGPT SSO authURL"
auth_json="$(curl -fsS \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  "${SSO_BASE_URL}/login/oauth/getAuthURL?redirect_uri=${encoded_redirect_uri}&state=${encoded_state}")"
auth_url="$(printf '%s' "$auth_json" | extract_auth_url)"
echo "   authURL=${auth_url}"

echo "2) visiting mock CAS login"
cas_headers="$tmp_dir/cas.headers"
curl -fsS -D "$cas_headers" -o /dev/null \
  --resolve "${MOCK_CAS_HOST}:${MOCK_CAS_PORT}:127.0.0.1" \
  "$auth_url"
callback_url="$(extract_header "$cas_headers" "Location")"
echo "   callback=${callback_url}"

echo "3) calling SSO callback with CAS ticket"
callback_headers="$tmp_dir/callback.headers"
curl -fsS -D "$callback_headers" -o /dev/null \
  --resolve "${SSO_CALLBACK_HOST}:${SSO_CALLBACK_PORT}:127.0.0.1" \
  "$callback_url"
final_location="$(extract_header "$callback_headers" "Location")"
echo "   final=${final_location}"

code="$(extract_code "$final_location")"
echo "4) exchanging code for user info"
curl -fsS \
  -H "Authorization: Bearer ${AUTH_TOKEN}" \
  "${SSO_BASE_URL}/login/oauth/getUserInfo?code=$(urlencode "$code")"
echo
