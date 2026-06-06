# Intranet Mail Kotlin（内网邮件服务）

Kotlin + Spring Boot 2.7 的轻量级内网邮件服务（MVP）。项目覆盖用户 REST API、Webmail、管理后台、SMTP/POP3 协议服务、邮件队列、附件落盘、审计与登录限流，适合内网邮件场景部署与验证。

> 说明：这不是完整企业级 Internet MTA。IMAP、完整反垃圾、DKIM/SPF/DMARC、S/MIME、PGP 等能力未默认内置。

---

## 目录

- [项目结构](#项目结构)
- [核心能力](#核心能力)
- [鉴权与安全](#鉴权与安全)
- [运行配置](#运行配置)
- [快速启动](#快速启动)
- [API 与示例](#api-与示例)
- [数据库与迁移](#数据库与迁移)
- [测试与 CI](#测试与-ci)
- [部署示例](#部署示例)
- [运维与边界](#运维与边界)

---

## 项目结构

- `src/main/kotlin/com/maoning/mail`
  - `api/`：REST API（用户 API 与管理员 API）
  - `admin/`：管理员页面与鉴权（Thymeleaf）
  - `webmail/`：Webmail 页面（Thymeleaf）
  - `smtp/`：SMTP 实现（`EHLO/HELO`、`AUTH`、`STARTTLS`、`MAIL/RCPT/DATA`）
  - `pop3/`：POP3 实现（`CAPA/STLS`、`USER/PASS`、`STAT/LIST/RETR`、`DELE/RSET/QUIT`）
  - `queue/`：队列 worker（重试、死信）
  - `store/`：存储接口与领域模型（`MailStore`）
  - `jpa/`：JPA 实体、Repository、`MailProperties`、`JpaMailStore`
  - `config/`：`AppConfig` 与环境变量映射
  - `auth/`：用户鉴权（注册、登录、会话）
  - `audit/`：审计日志
  - `attachment/`：附件存储与读取
  - `mime/`：MIME 解析与附件提取
  - `security/`：登录失败限速（`LoginRateLimiter`）
- `src/main/resources`
  - `application.properties`
  - `db/migration/`：Flyway 迁移脚本
  - `templates/`：Thymeleaf 页面模板
- `src/test/kotlin`：控制层与服务层测试
- `deploy/intranet-mail.service`：systemd 示例
- `docker-compose.yml` 与 `Dockerfile`
- `.github/workflows/ci.yml`

---

## 核心能力

### 用户 API

- `POST /api/register`
- `POST /api/login`
- `POST /api/mail/send`
- `GET /api/mail/inbox`
- `GET /api/mail/sent`
- `GET /api/mail/search`
- `GET /api/mail/{messageId}/delivery-status`（仅发件人可见的投递状态）
- `GET /api/mail/{messageId}/attachments/{attachmentId}`（附件下载）
- `GET /health` / `GET /health/live` / `GET /health/ready`
- `GET /metrics/queue`（队列与死信轻量指标）
- `GET /dns`（内网 DNS 示例文本）

### 页面与协议服务

- Web UI：`GET /webmail`
- 管理页：`GET /admin/login`、`GET /admin`
- SMTP：`SMTP_HOST:SMTP_PORT`
  - `EHLO` / `HELO`
  - `AUTH PLAIN` / `AUTH LOGIN`
  - `STARTTLS`（配置 `SMTP_TLS_KEYSTORE` 与 `SMTP_TLS_KEYSTORE_PASSWORD` 后启用）
  - `MAIL FROM` / `RCPT TO` / `DATA` / `QUIT`
  - 发送时会校验 `MAIL FROM` 与已认证用户一致
- POP3：`POP3_HOST:POP3_PORT`
  - `CAPA` / `STLS` / `USER` / `PASS` / `STAT` / `LIST` / `RETR` / `NOOP` / `RSET` / `DELE` / `QUIT`
  - `STLS` 在配置 `SMTP_TLS_KEYSTORE` 与 `SMTP_TLS_KEYSTORE_PASSWORD` 后启用；默认 `POP3_REQUIRE_TLS_FOR_AUTH=true`，未进入 TLS 前拒绝 `USER` / `PASS`，避免明文传输口令。
  - 仅在受控内网调试/兼容旧客户端时才建议显式设置 `POP3_REQUIRE_TLS_FOR_AUTH=false`，该模式会允许 POP3 明文认证。
  - `RETR` 成功返回邮件后会将该 mailbox/message 标记为已读。
  - `DELE` 采用 POP3 事务语义：会话内先标记待删除；`RSET` 取消待删除；`QUIT` 时落库为 mailbox 软删除（进入 trash），不会物理删除消息或破坏留存策略。

### 队列与管理员能力

- 邮件持久化后入队，异步投递到收件人 mailbox。
- `MailQueueWorker` 每 3 秒扫描一次：`@Scheduled(fixedDelay = 3000, initialDelay = 1000)`。
- 失败后指数退避重试，重试次数上限为 `MAX_QUEUE_ATTEMPTS`，超过后转死信。
- 退避间隔：`min(3600, 5 * 2^attempts)` 秒。
- 队列状态：`QUEUED` / `RETRY` / `DELIVERED` / `DEAD`。
- 发件人可通过 `GET /api/mail/{messageId}/delivery-status` 查看自己已发送邮件的逐收件人队列状态；非发件人请求返回 `404`，避免泄露其他用户投递信息。
- 管理 API（统一鉴权）：
  - `GET /admin/api/users`
  - `GET /admin/api/messages`
  - `GET /admin/api/queue`
  - `GET /admin/api/dead`
  - `GET /admin/api/audit`
  - `POST /admin/api/queue/drain`

---

## 鉴权与安全

### 用户鉴权

- 用户 API 使用 `Authorization: Bearer $ACCESS`。
- 登录失败受 `LOGIN_MAX_FAILURES` + `LOGIN_WINDOW_SECONDS` 限速。

### 管理鉴权（统一逻辑）

`/admin` 页面与 `/admin/api/*` 共用同一鉴权逻辑（`AdminAuthService`）：

1. Header：`X-Admin-Token`
2. Query：`?token=`（仅当 `ADMIN_QUERY_TOKEN_ENABLED=true`，仅允许 GET 管理接口；响应会附带 `Referrer-Policy: no-referrer` 与 `Cache-Control: no-store`）
3. Cookie：`ADMIN_SESSION`

`/admin/login` 在配置 `ADMIN_SESSION_SECRET` 且登录成功时会签发 `ADMIN_SESSION`（HTTP Only Cookie）。使用 Cookie 访问管理 POST 接口时必须携带 `X-CSRF-Token`；Header token 鉴权不需要 CSRF。Query token 永远不能用于管理 POST。

### 安全建议

- 优先使用 `ADMIN_PASSWORD_HASH`（bcrypt）而非明文 `ADMIN_TOKEN`。
- `ADMIN_SESSION_SECRET` 建议长度 >= 32。
- HTTPS 场景建议设置 `SECURE_COOKIES=true`。
- 默认关闭 `ADMIN_QUERY_TOKEN_ENABLED`，按受控场景按需开启。
- 如仅需 HTTP 功能，可设置 `SOCKET_SERVERS_ENABLED=false` 关闭 SMTP/POP3。

---

## 运行配置

- JDK：11+
- Maven：3.x
- 默认数据库：H2（`./data/intranet-mail`）

配置入口：`src/main/kotlin/com/maoning/mail/jpa/MailProperties`，启动时会映射到 `AppConfig`。

### 环境变量

| 变量 | 说明 | 默认值 |
| --- | --- | --- |
| `MAIL_DOMAIN` | 内网域名后缀 | `intra.local` |
| `HTTP_HOST` | HTTP 监听地址 | `0.0.0.0` |
| `HTTP_PORT` | HTTP 监听端口 | `8080` |
| `SMTP_HOST` | SMTP 监听地址 | `0.0.0.0` |
| `SMTP_PORT` | SMTP 监听端口 | `2525` |
| `POP3_HOST` | POP3 监听地址 | `0.0.0.0` |
| `POP3_PORT` | POP3 监听端口 | `1110` |
| `H2_URL` | H2 JDBC URL | `jdbc:h2:./data/intranet-mail;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE` |
| `H2_USER` | H2 用户名 | `sa` |
| `H2_PASSWORD` | H2 密码 | 空 |
| `ADMIN_USER` | 管理员用户名（供 `/admin/login` 使用） | `admin` |
| `ADMIN_PASSWORD_HASH` | 管理员 bcrypt hash（推荐） | 空 |
| `ADMIN_TOKEN` | 管理员 token（与 `ADMIN_PASSWORD_HASH` 二选一） | 空 |
| `ADMIN_SESSION_SECRET` | 管理会话签名密钥（>=32） | 空 |
| `ADMIN_SESSION_HOURS` | 管理会话有效时长（小时） | `8` |
| `ADMIN_QUERY_TOKEN_ENABLED` | 是否允许 `?token=` 鉴权 | `false` |
| `SECURE_COOKIES` | `ADMIN_SESSION` 是否携带 `Secure` 标志 | `false` |
| `SOCKET_SERVERS_ENABLED` | 是否启动 SMTP/POP3 | `true` |
| `SMTP_MAX_CONNECTIONS` | SMTP 并发上限 | `50` |
| `POP3_MAX_CONNECTIONS` | POP3 并发上限 | `50` |
| `SOCKET_TIMEOUT_MILLIS` | Socket 超时（毫秒） | `30000` |
| `SMTP_REQUIRE_TLS_FOR_AUTH` | SMTP 是否要求 STARTTLS 后才允许 AUTH | `true` |
| `POP3_REQUIRE_TLS_FOR_AUTH` | POP3 是否要求 STLS 后才允许 USER/PASS | `true` |
| `MAX_QUEUE_ATTEMPTS` | 队列最大重试次数 | `5` |
| `MAX_MESSAGE_BYTES` | 单封邮件最大字节 | `10485760` |
| `MAX_ATTACHMENT_BYTES` | 单附件最大字节 | `10485760` |
| `MAX_TOTAL_ATTACHMENT_BYTES` | 单封邮件附件总上限 | `20971520` |
| `ATTACHMENT_DIR` | 附件目录 | `./data/attachments` |
| `LOGIN_MAX_FAILURES` | 登录失败阈值 | `5` |
| `LOGIN_WINDOW_SECONDS` | 登录失败窗口（秒） | `300` |
| `SMTP_TLS_KEYSTORE` | TLS keystore 路径（可选） | 空 |
| `SMTP_TLS_KEYSTORE_PASSWORD` | TLS keystore 密码（可选） | 空 |

> 启动前至少配置一项：`ADMIN_TOKEN` 或 `ADMIN_PASSWORD_HASH`。若两者均为空，应用会在启动校验失败并退出。

---

## 快速启动

### 本地启动

```bash
cd /opt/dev/intranet-mail-kotlin

# 启动前先设置管理员鉴权（两者选一）
export ADMIN_PASSWORD_HASH=<bcrypt-hash>
# export ADMIN_TOKEN=<admin-secret>

# 可选：启用管理页登录会话
# export ADMIN_SESSION_SECRET=<at-least-32-chars>

mvn -B package
java -jar target/intranet-mail-kotlin-0.1.0.jar
```

默认端口：HTTP `8080` / SMTP `2525` / POP3 `1110`。

### Docker Compose

```bash
docker compose up -d --build
```

### Docker 直接运行

```bash
docker build -t intranet-mail-kotlin .

docker run --rm \
  -p 8080:8080 \
  -p 2525:2525 \
  -p 1110:1110 \
  -e MAIL_DOMAIN=corp.local \
  -e ADMIN_PASSWORD_HASH=<bcrypt-hash> \
  -e ADMIN_SESSION_SECRET=<at-least-32-chars> \
  -v intranet-mail-data:/opt/intranet-mail/data \
  intranet-mail-kotlin
```

> 说明：`deploy/intranet-mail.service` 示例里 `SMTP_PORT` 设置为 `25`，与上面本地示例的 `2525` 可按环境选择。

---

## API 与示例

示例默认主机为 `127.0.0.1:8080`，按实际环境替换。

### 用户 API

```bash
# 注册
curl -s -X POST http://127.0.0.1:8080/api/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice-test-pw"}'

# 登录并提取访问凭据
ACCESS=$(curl -s -X POST http://127.0.0.1:8080/api/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice-test-pw"}' | jq -r '.token')

# 发送邮件
curl -s -X POST http://127.0.0.1:8080/api/mail/send \
  -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' \
  -d '{"to":["bob"],"subject":"Hello","body":"测试邮件"}'

# 收件箱
curl -s http://127.0.0.1:8080/api/mail/inbox \
  -H "Authorization: Bearer $ACCESS"

# 已发送
curl -s http://127.0.0.1:8080/api/mail/sent \
  -H "Authorization: Bearer $ACCESS"

# 搜索
curl -s 'http://127.0.0.1:8080/api/mail/search?q=hello&box=inbox&limit=20' \
  -H "Authorization: Bearer $ACCESS"

# 查看自己已发送邮件的逐收件人投递状态
MESSAGE_ID=<message-id>
curl -s "http://127.0.0.1:8080/api/mail/$MESSAGE_ID/delivery-status" \
  -H "Authorization: Bearer $ACCESS"

# 下载附件（示例）
ATTACHMENT_ID=<attachment-id>
curl -OJ "http://127.0.0.1:8080/api/mail/$MESSAGE_ID/attachments/$ATTACHMENT_ID" \
  -H "Authorization: Bearer $ACCESS"
```

### 管理 API

```bash
# Header 鉴权
curl -s -H 'X-Admin-Token: <admin-secret>' \
  'http://127.0.0.1:8080/admin/api/users'

# Query 鉴权（需 ADMIN_QUERY_TOKEN_ENABLED=true；仅允许 GET，生产不推荐）
curl -s 'http://127.0.0.1:8080/admin/api/users?token=<admin-secret>'

# 手动触发队列消费（POST 仅支持 Header token，或 Cookie session + X-CSRF-Token）
curl -s -X POST -H 'X-Admin-Token: <admin-secret>' \
  'http://127.0.0.1:8080/admin/api/queue/drain'
```

### SMTP / POP3 调试

```bash
# SMTP（nc 连接后逐行输入）
nc 127.0.0.1 2525
EHLO test
AUTH LOGIN
# 按提示输入 base64 编码后的用户名与密码
MAIL FROM:<alice@intra.local>
RCPT TO:<bob@intra.local>
DATA
Subject: SMTP hello

这是测试正文。
.
QUIT

# POP3
nc 127.0.0.1 1110
USER bob
PASS bob-test-pw
STAT
LIST
RETR 1
QUIT
```

---

## 数据库与迁移

数据库脚本位于：`src/main/resources/db/migration`

- `V1__init.sql`
- `V2__audit_and_login_attempts.sql`
- `V3__mailbox_flags.sql`

默认持久化栈：JPA + Flyway + H2。

---

## 测试与 CI

```bash
mvn -B test
mvn -B verify
```

CI 流程定义见：`.github/workflows/ci.yml`。

---

## 部署示例

`deploy/intranet-mail.service` 提供 systemd 示例。

---

## 运维与边界

- 健康检查：`/health/live` 用于 liveness，`/health/ready` 用于 readiness；readiness 会访问 `MailStore`，失败返回 `503`。
- 队列指标：`/metrics/queue` 返回 `queued`、`retry`、`delivered`、`dead`、`total`，可用于死信和积压告警。
- 链路标识：HTTP 响应包含 `X-Correlation-Id`，请求带同名 header 时透传，否则自动生成 UUID。
- 部署与备份：详见 `deploy/RUNBOOK.md`，覆盖 Docker Compose/systemd 基线、H2 + attachments 备份恢复、smoke checks。
- 队列重试与死信策略由 `MAX_QUEUE_ATTEMPTS`、`MailQueueWorker` 与配置共同决定。
- `POST /admin/api/queue/drain` 支持手动触发投递。
- 当前不内建 IMAP；可通过 `/webmail` + POP3 进行收发辅助。
- 企业级安全能力（反垃圾、DKIM/SPF/DMARC、S/MIME、PGP）未默认内置。
- 附件与消息保留策略按业务配置调整；默认偏向可追溯保留。
