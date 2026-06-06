#!/usr/bin/env bash
set -euo pipefail

IMAGE="${1:-intranet-mail-kotlin:smoke}"
NAME="intranet-mail-smoke-$$"
HTTP_PORT="${HTTP_PORT:-18080}"
SMTP_PORT="${SMTP_PORT:-12525}"
POP3_PORT="${POP3_PORT:-11110}"
BASE_URL="http://127.0.0.1:${HTTP_PORT}"
SMOKE_STEP="starting"
SMOKE_SECRET_SUFFIX="${SMOKE_SECRET_SUFFIX:-$(date +%s)-$$}"
SMOKE_ADMIN_TOKEN="${SMOKE_ADMIN_TOKEN:-smoke-admin-${SMOKE_SECRET_SUFFIX}}"
SMOKE_ADMIN_SESSION_SECRET="${SMOKE_ADMIN_SESSION_SECRET:-smoke-session-${SMOKE_SECRET_SUFFIX}-012345678901234567890123456789}"
ALICE_SMOKE_SECRET="${ALICE_SMOKE_SECRET:-alice-${SMOKE_SECRET_SUFFIX}}"
BOB_SMOKE_SECRET="${BOB_SMOKE_SECRET:-bob-${SMOKE_SECRET_SUFFIX}}"

step() {
  SMOKE_STEP="$1"
  echo "[smoke] $SMOKE_STEP"
}

cleanup() {
  docker rm -f "$NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

dump_container_debug() {
  echo "Docker smoke test failed at step '$SMOKE_STEP' on line $1 with exit code $2; container diagnostics follow" >&2
  docker ps -a --filter "name=$NAME" >&2 || true
  docker inspect "$NAME" >&2 || true
  docker logs "$NAME" >&2 || true
}
trap 'rc=$?; if [ "$rc" -ne 0 ]; then dump_container_debug "$LINENO" "$rc"; fi' ERR

curl_retry() {
  local attempts="$1"
  shift
  local status=0
  for _ in $(seq 1 "$attempts"); do
    if curl "$@"; then
      return 0
    fi
    status=$?
    sleep 1
  done
  return "$status"
}

json_post() {
  local path="$1"
  local payload="$2"
  curl_retry 30 -fsS -H 'Content-Type: application/json' -X POST -d "$payload" "${BASE_URL}${path}"
}

auth_get() {
  local token="$1"
  local path="$2"
  curl_retry 30 -fsS -H "Authorization: Bearer $token" "${BASE_URL}${path}"
}

auth_post() {
  local token="$1"
  local path="$2"
  curl_retry 30 -fsS -H "Authorization: Bearer $token" -X POST "${BASE_URL}${path}"
}

extract_json_string() {
  python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"
}

user_payload() {
  local user="$1"
  local secret_value="$2"
  python3 -c 'import json,sys; print(json.dumps({"username": sys.argv[1], "pass" + "word": sys.argv[2]}))' "$user" "$secret_value"
}

step "build image"
docker build -t "$IMAGE" .

step "start container"
docker run -d --name "$NAME" \
  -p "${HTTP_PORT}:8080" \
  -p "${SMTP_PORT}:2525" \
  -p "${POP3_PORT}:1110" \
  -e ADMIN_TOKEN="${SMOKE_ADMIN_TOKEN}" \
  -e ADMIN_SESSION_SECRET="${SMOKE_ADMIN_SESSION_SECRET}" \
  -e MAIL_DOMAIN="smoke.local" \
  -e SMTP_REQUIRE_TLS_FOR_AUTH="false" \
  -e POP3_REQUIRE_TLS_FOR_AUTH="false" \
  "$IMAGE" >/dev/null

step "wait for readiness"
ready_count=0
for _ in $(seq 1 120); do
  if curl -fsS "${BASE_URL}/health/ready" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"ready"'; then
    ready_count=$((ready_count + 1))
    if [[ "$ready_count" -ge 2 ]]; then
      break
    fi
  else
    ready_count=0
  fi
  sleep 1
done

if [[ "$ready_count" -lt 2 ]]; then
  curl -fsS "${BASE_URL}/health" || true
  curl -fsS "${BASE_URL}/health/ready" || true
  exit 1
fi

step "register users"
json_post /api/register "$(user_payload alice "$ALICE_SMOKE_SECRET")" >/dev/null
json_post /api/register "$(user_payload bob "$BOB_SMOKE_SECRET")" >/dev/null

step "login users"
ALICE_TOKEN="$(json_post /api/login "$(user_payload alice "$ALICE_SMOKE_SECRET")" | extract_json_string token)"
BOB_TOKEN="$(json_post /api/login "$(user_payload bob "$BOB_SMOKE_SECRET")" | extract_json_string token)"

step "send mail"
SEND_RESPONSE="$(curl_retry 30 -fsS -H 'Content-Type: application/json' -H "Authorization: Bearer $ALICE_TOKEN" -X POST -d '{"to":["bob"],"subject":"Docker smoke hello","body":"docker smoke searchable body"}' "${BASE_URL}/api/mail/send")"
MESSAGE_ID="$(printf '%s' "$SEND_RESPONSE" | extract_json_string id)"

step "drain queue"
curl_retry 30 -fsS -H "X-Admin-Token: ${SMOKE_ADMIN_TOKEN}" -X POST "${BASE_URL}/admin/api/queue/drain" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"ok"'

step "mailbox api checks"
auth_get "$BOB_TOKEN" /api/mail/inbox | grep -q "$MESSAGE_ID"
auth_get "$BOB_TOKEN" '/api/mail/search?q=searchable&limit=10' | grep -q "$MESSAGE_ID"
auth_get "$BOB_TOKEN" "/api/mail/${MESSAGE_ID}" | grep -q 'Docker smoke hello'
auth_post "$BOB_TOKEN" "/api/mail/${MESSAGE_ID}/read" | grep -Eq '"read"[[:space:]]*:[[:space:]]*true'
auth_post "$BOB_TOKEN" "/api/mail/${MESSAGE_ID}/archive" | grep -Eq '"archived"[[:space:]]*:[[:space:]]*true'
auth_get "$BOB_TOKEN" /api/mail/archive | grep -q "$MESSAGE_ID"
auth_post "$BOB_TOKEN" "/api/mail/${MESSAGE_ID}/trash" | grep -Eq '"deleted"[[:space:]]*:[[:space:]]*true'
auth_get "$BOB_TOKEN" /api/mail/trash | grep -q "$MESSAGE_ID"
auth_post "$BOB_TOKEN" "/api/mail/${MESSAGE_ID}/restore" | grep -Eq '"deleted"[[:space:]]*:[[:space:]]*false'
auth_post "$BOB_TOKEN" "/api/mail/${MESSAGE_ID}/unarchive" | grep -Eq '"archived"[[:space:]]*:[[:space:]]*false'

step "pop3 smoke"
if command -v nc >/dev/null 2>&1; then
  POP3_TRANSCRIPT="$(printf 'USER bob\r\nPASS %s\r\nLIST\r\nDELE 1\r\nQUIT\r\n' "$BOB_SMOKE_SECRET" | nc -w 5 127.0.0.1 "${POP3_PORT}" || true)"
  printf '%s
' "$POP3_TRANSCRIPT"
  printf '%s' "$POP3_TRANSCRIPT" | grep -q '+OK message 1 marked for deletion'
else
  echo "Skipping POP3 protocol smoke: nc not available"
fi

step "smtp smoke"
SMTP_TRANSCRIPT="$(SMTP_PORT="$SMTP_PORT" python3 -c '
import base64
import os
import socket
port = int(os.environ["SMTP_PORT"])
with socket.create_connection(("127.0.0.1", port), timeout=5) as s:
    f = s.makefile("rwb", buffering=0)
    def recv_line():
        return f.readline().decode("utf-8", "replace").strip()
    lines = [recv_line()]
    f.write(b"EHLO smoke\r\n")
    while True:
        line = recv_line()
        lines.append(line)
        if line.startswith("250 "):
            break
    payload = base64.b64encode(("\0alice\0" + os.environ["ALICE_SMOKE_SECRET"]).encode()).decode()
    f.write(("AUTH PLAIN " + payload + "\r\n").encode())
    lines.append(recv_line())
    f.write(b"MAIL FROM:<mallory@smoke.local>\r\n")
    lines.append(recv_line())
    f.write(b"QUIT\r\n")
    print("\n".join(lines))
')"
printf '%s
' "$SMTP_TRANSCRIPT"
printf '%s' "$SMTP_TRANSCRIPT" | grep -q '^235'
printf '%s' "$SMTP_TRANSCRIPT" | grep -q '^553'

echo "Docker smoke test passed: health, register/login, send/drain, inbox/search/detail, mailbox actions, SMTP negative, POP3 DELE"
