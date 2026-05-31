#!/usr/bin/env bash
set -euo pipefail

IMAGE="${1:-intranet-mail-kotlin:smoke}"
NAME="intranet-mail-smoke-$$"
HTTP_PORT="${HTTP_PORT:-18080}"
SMTP_PORT="${SMTP_PORT:-12525}"
POP3_PORT="${POP3_PORT:-11110}"

cleanup() {
  docker rm -f "$NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if docker image inspect "$IMAGE" >/dev/null 2>&1 && [[ "${SKIP_DOCKER_BUILD:-false}" == "true" ]]; then
  echo "Using existing image $IMAGE"
else
  docker build -t "$IMAGE" .
fi

docker run -d --name "$NAME" \
  -p "${HTTP_PORT}:8080" \
  -p "${SMTP_PORT}:2525" \
  -p "${POP3_PORT}:1110" \
  -e ADMIN_TOKEN="smoke-admin-token" \
  -e ADMIN_SESSION_SECRET="smoke-admin-session-secret-32-chars" \
  -e MAIL_DOMAIN="smoke.local" \
  "$IMAGE" >/dev/null

for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:${HTTP_PORT}/health" | grep -q '"status" : "ok"'; then
    echo "Docker smoke test passed"
    exit 0
  fi
  sleep 1
done

docker logs "$NAME" || true
echo "Docker smoke test failed" >&2
exit 1
