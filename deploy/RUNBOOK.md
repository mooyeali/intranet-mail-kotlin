# Intranet Mail Kotlin 运维运行手册

## 健康检查与观测

### 端点

- `GET /health`：兼容性健康端点，只表示 HTTP 进程可响应。
- `GET /health/live`：liveness，用于 systemd/container 判断进程是否活着。
- `GET /health/ready`：readiness，会检查 `MailStore` 可访问；失败返回 `503`。
- `GET /metrics/queue`：轻量队列指标 JSON，包含 `queued`、`retry`、`delivered`、`dead`、`total`。

### 日志与链路

- HTTP 请求经过 `CorrelationIdFilter`，响应头总是包含 `X-Correlation-Id`。
- 如果请求已带 `X-Correlation-Id`，服务会透传；否则生成 UUID。
- 日志侧建议在 logback pattern 中输出 MDC key `correlationId`；接入集中日志时按该字段检索。

## 部署前安全基线

### 必填密钥

不要把真实密钥提交到仓库。运行前用环境文件或 Secret 管理器注入：

- `ADMIN_TOKEN` 或 `ADMIN_PASSWORD_HASH` 至少配置一项。
- `ADMIN_SESSION_SECRET` 配置为 32+ 字符随机值。
- HTTPS/反向代理场景设置 `SECURE_COOKIES=true`。
- 生产默认保持 `ADMIN_QUERY_TOKEN_ENABLED=false`。

生成示例：

```bash
openssl rand -hex 32
```

bcrypt hash 可由业务侧密码工具生成；不要在 shell history 中保留明文密码。

### Docker Compose

`docker-compose.yml` 通过 `${VAR:?message}` 强制要求关键密钥，并启用：

- `/health/ready` healthcheck
- memory/cpu resource limits
- `no-new-privileges`
- `cap_drop: ALL`
- 持久卷 `intranet-mail-data:/opt/intranet-mail/data`

启动示例：

```bash
export ADMIN_TOKEN="$(openssl rand -hex 32)"
export ADMIN_SESSION_SECRET="$(openssl rand -hex 32)"
export SECURE_COOKIES=true

docker compose up -d --build
docker compose ps
curl -fsS http://127.0.0.1:8080/health/ready
curl -fsS http://127.0.0.1:8080/metrics/queue
```

### systemd

1. 创建运行用户和目录：

```bash
sudo useradd -r -u 10001 -d /opt/intranet-mail -s /usr/sbin/nologin mailapp || true
sudo install -d -o mailapp -g mailapp -m 0750 /opt/intranet-mail/data
sudo install -d -o root -g root -m 0750 /etc/intranet-mail
```

2. 写入 `/etc/intranet-mail/intranet-mail.env`，权限 `0600`：

```bash
sudo tee /etc/intranet-mail/intranet-mail.env >/dev/null <<'EOF'
ADMIN_TOKEN=<long-random-token>
ADMIN_PASSWORD_HASH=
ADMIN_SESSION_SECRET=<32-plus-char-random-secret>
SECURE_COOKIES=true
ADMIN_QUERY_TOKEN_ENABLED=false
SOCKET_SERVERS_ENABLED=true
EOF
sudo chmod 0600 /etc/intranet-mail/intranet-mail.env
```

3. 安装并启动：

```bash
sudo cp deploy/intranet-mail.service /etc/systemd/system/intranet-mail.service
sudo systemctl daemon-reload
sudo systemctl enable --now intranet-mail
sudo systemctl status intranet-mail --no-pager
curl -fsS http://127.0.0.1:8080/health/ready
```

## 备份与恢复

持久数据由两部分组成：

- H2 数据库：默认路径 `/opt/intranet-mail/data/intranet-mail*`
- 附件目录：默认路径 `/opt/intranet-mail/data/attachments`

### 备份

推荐在低峰期暂停写入或短暂停服务，避免 H2 文件与附件不一致。

```bash
sudo systemctl stop intranet-mail
sudo tar --numeric-owner -czf /var/backups/intranet-mail-$(date +%Y%m%d-%H%M%S).tgz \
  -C /opt/intranet-mail data
sudo systemctl start intranet-mail
```

Docker volume 备份示例：

```bash
docker compose stop intranet-mail
docker run --rm \
  -v intranet-mail-kotlin_intranet-mail-data:/data:ro \
  -v "$PWD/backups:/backup" \
  alpine tar -czf /backup/intranet-mail-$(date +%Y%m%d-%H%M%S).tgz -C /data .
docker compose start intranet-mail
```

### 恢复

```bash
sudo systemctl stop intranet-mail
sudo mv /opt/intranet-mail/data /opt/intranet-mail/data.bak.$(date +%s)
sudo mkdir -p /opt/intranet-mail/data
sudo tar -xzf /var/backups/<backup-file>.tgz -C /opt/intranet-mail
sudo chown -R mailapp:mailapp /opt/intranet-mail/data
sudo systemctl start intranet-mail
curl -fsS http://127.0.0.1:8080/health/ready
```

Docker volume 恢复时先停止服务，再把备份解回 volume，最后运行 smoke checks。

## Smoke checks

```bash
curl -fsS http://127.0.0.1:8080/health/live
curl -fsS http://127.0.0.1:8080/health/ready
curl -fsS http://127.0.0.1:8080/metrics/queue
curl -fsS -H "X-Admin-Token: $ADMIN_TOKEN" http://127.0.0.1:8080/admin/api/queue
```

可选协议端口检查：

```bash
nc -vz 127.0.0.1 2525
nc -vz 127.0.0.1 1110
```

## 告警建议

- `/health/ready` 连续失败 3 次告警。
- `/metrics/queue.dead > 0` 告警。
- `/metrics/queue.retry` 持续增长或 `total` 长时间不下降告警。
- 进程重启次数、RSS 内存接近 768M、磁盘剩余空间低于 20% 告警。
