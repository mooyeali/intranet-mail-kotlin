# Intranet Mail Kotlin

Kotlin/Ktor 实现的内网邮件服务，已包含用户、邮件、会话持久化，SMTP AUTH/STARTTLS，Jakarta Mail MIME/附件解析，管理后台登录，Flyway 迁移，审计日志，登录限流，内网 DNS 示例，以及邮件队列、重试和死信队列。

## 功能

- 邮箱注册：`POST /api/register`
- 登录与 Bearer Token：`POST /api/login`
- REST 发邮件：`POST /api/mail/send`
- 收件箱 / 已发送：`GET /api/mail/inbox`, `GET /api/mail/sent`
- H2 持久化：用户、会话、邮件、附件元数据/内容、投递队列
- SMTP：`EHLO/HELO`, `AUTH PLAIN`, `AUTH LOGIN`, `STARTTLS`, `MAIL FROM`, `RCPT TO`, `DATA`, `QUIT`
- MIME：使用 Jakarta Mail 解析 multipart、正文和附件，附件落盘，DB 仅存元数据
- 邮件队列：异步投递、指数退避重试、超过次数进入 DEAD 死信
- 管理后台：`/admin/login` 登录页，API 仍支持 `X-Admin-Token`
- DNS 示例：`/dns`


## 构建说明

项目以 Maven 为唯一构建入口；已移除 Gradle 相关配置，避免依赖版本漂移。当前 Maven 配置保持 JVM 8 字节码兼容，Docker 运行时仍使用 JRE 17。

## 启动

```bash
cd intranet-mail-kotlin
mvn -B package && java -jar target/intranet-mail-kotlin-0.1.0.jar
```

默认配置：

```text
MAIL_DOMAIN=intra.local
HTTP_HOST=0.0.0.0
HTTP_PORT=8080
SMTP_HOST=0.0.0.0
SMTP_PORT=2525
H2_URL=jdbc:h2:./data/intranet-mail;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE
H2_USER=sa
H2_PASSWORD=
ADMIN_TOKEN=change-me
ADMIN_SESSION_SECRET=change-me
MAX_QUEUE_ATTEMPTS=5
ATTACHMENT_DIR=./data/attachments
LOGIN_MAX_FAILURES=5
LOGIN_WINDOW_SECONDS=300
ADMIN_USER=admin
ADMIN_PASSWORD_HASH=
ADMIN_QUERY_TOKEN_ENABLED=false
SECURE_COOKIES=false
SMTP_MAX_CONNECTIONS=50
POP3_MAX_CONNECTIONS=50
SOCKET_TIMEOUT_MILLIS=30000
MAX_MESSAGE_BYTES=10485760
MAX_ATTACHMENT_BYTES=10485760
MAX_TOTAL_ATTACHMENT_BYTES=20971520
```

生产/内网建议显式配置：

```bash
MAIL_DOMAIN=corp.local \
ADMIN_TOKEN='replace-with-long-random-token' \
ADMIN_SESSION_SECRET='replace-with-at-least-32-characters-secret' \
SMTP_PORT=25 \
HTTP_PORT=8080 \
mvn -B package && java -jar target/intranet-mail-kotlin-0.1.0.jar
```

## STARTTLS 配置

没有配置 keystore 时，SMTP 会正常工作但 `STARTTLS` 返回不可用。生成内网自签证书：

```bash
keytool -genkeypair \
  -alias mail \
  -keyalg RSA \
  -keysize 2048 \
  -storetype PKCS12 \
  -keystore mail.p12 \
  -validity 3650 \
  -storepass changeit \
  -dname "CN=mail.intra.local, OU=IT, O=Intranet, L=LAN, S=LAN, C=CN"
```

启动：

```bash
SMTP_TLS_KEYSTORE=./mail.p12 \
SMTP_TLS_KEYSTORE_PASSWORD=changeit \
mvn -B package && java -jar target/intranet-mail-kotlin-0.1.0.jar
```

## REST 示例

注册：

```bash
curl -s -X POST http://127.0.0.1:8080/api/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alicePass123"}'

curl -s -X POST http://127.0.0.1:8080/api/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"bob","password":"bobPass123"}'
```

登录：

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alicePass123"}' | sed -n 's/.*"token" : "\([^"]*\)".*/\1/p')
```

发送：

```bash
curl -s -X POST http://127.0.0.1:8080/api/mail/send \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"to":["bob"],"subject":"Hello","body":"内网邮件测试"}'
```

队列 worker 会异步投递；也可以手动触发：

```bash
curl -X POST \
  -H 'X-Admin-Token: replace-with-long-random-token' \
  'http://127.0.0.1:8080/admin/api/queue/drain'
```

## SMTP AUTH 示例

`AUTH PLAIN` 内容为：`\0用户名\0密码` 的 base64。

```bash
printf '\0alice\0alicePass123' | base64
nc 127.0.0.1 2525
```

输入：

```smtp
EHLO test
AUTH PLAIN AGFsaWNlAGFsaWNlUGFzczEyMw==
MAIL FROM:<alice@intra.local>
RCPT TO:<bob@intra.local>
DATA
Subject: SMTP hello
Content-Type: multipart/mixed; boundary="demo"

--demo
Content-Type: text/plain; charset=utf-8

这是一封通过 SMTP AUTH 投递的内网邮件。
--demo
Content-Type: text/plain; name="hello.txt"
Content-Disposition: attachment; filename="hello.txt"
Content-Transfer-Encoding: base64

5L2g5aW977yM6ZmE5Lu25rWL6K+V
--demo--
.
QUIT
```

## 管理后台

页面：

```text
http://127.0.0.1:8080/admin/login
```

接口：

```bash
curl -H 'X-Admin-Token: replace-with-long-random-token' 'http://127.0.0.1:8080/admin/api/users'
curl -H 'X-Admin-Token: replace-with-long-random-token' 'http://127.0.0.1:8080/admin/api/messages'
curl -H 'X-Admin-Token: replace-with-long-random-token' 'http://127.0.0.1:8080/admin/api/queue'
curl -H 'X-Admin-Token: replace-with-long-random-token' 'http://127.0.0.1:8080/admin/api/dead'
```

## 内网 DNS / MX 配置

服务提供 `/dns` 输出示例。典型记录：

```dns
corp.local.        IN MX 10 mail.corp.local.
mail.corp.local.   IN A  192.168.1.10
smtp.corp.local.   IN CNAME mail.corp.local.
imap.corp.local.   IN CNAME mail.corp.local.
_smtp._tcp.corp.local. IN SRV 0 5 25 mail.corp.local.
```

如果用 CoreDNS，可写入 zone file 或使用 hosts 插件；如果用 Windows DNS，在正向查找区域添加 A/MX/CNAME/SRV 记录。

## Docker

```bash
docker build -t intranet-mail-kotlin .
docker run --rm -p 8080:8080 -p 2525:2525 \
  -e MAIL_DOMAIN=corp.local \
  -e ADMIN_TOKEN=replace-with-long-random-token \
  -e ADMIN_SESSION_SECRET=replace-with-at-least-32-characters-secret \
  -v intranet-mail-data:/opt/intranet-mail/data \
  intranet-mail-kotlin
```

Docker smoke test:

```bash
./scripts/docker-smoke-test.sh intranet-mail-kotlin:smoke
```

## systemd

示例服务文件在 `deploy/intranet-mail.service`。部署流程：

```bash
sudo useradd -r -s /usr/sbin/nologin mailapp
sudo mkdir -p /opt/intranet-mail/data
sudo cp build/libs/intranet-mail-kotlin-0.1.0-all.jar /opt/intranet-mail/app.jar
sudo cp deploy/intranet-mail.service /etc/systemd/system/intranet-mail.service
sudo chown -R mailapp:mailapp /opt/intranet-mail
sudo systemctl daemon-reload
sudo systemctl enable --now intranet-mail
```

生成后台密码 bcrypt hash 可用 Kotlin/Java 小工具或在线下安全环境调用 BCrypt。

## 当前边界

这个版本是内网 MVP，不是完整互联网 MTA：

- 已实现基础 POP3；没有实现 IMAP
- 没有 DKIM/SPF/DMARC
- 已使用 Jakarta Mail，但非常复杂的 S/MIME/加密邮件仍需专项处理
- 附件已落盘，生产可替换为对象存储
- SMTP 只允许认证用户以自己的邮箱发信
- 管理 API 默认不接受 URL query token；如需兼容旧脚本可显式设置 `ADMIN_QUERY_TOKEN_ENABLED=true`
- 管理后台 Cookie Session 使用 `ADMIN_SESSION_SECRET` 进行 HMAC 签名；生产环境必须配置 32 字符以上随机值
- SMTP/POP3 已限制连接线程、socket timeout、邮件大小和附件大小

## POP3 收信

默认监听 `POP3_PORT=1110`，支持最小可用命令：

- `USER`
- `PASS`
- `STAT`
- `LIST`
- `RETR`
- `NOOP`
- `RSET`
- `QUIT`

示例：

```bash
nc 127.0.0.1 1110
```

```pop3
USER bob
PASS bobPass123
STAT
LIST
RETR 1
QUIT
```

当前 `DELE` 暂不删除邮件，避免误删；后续可通过保留策略或软删除实现。

## 邮件搜索

```bash
curl 'http://127.0.0.1:8080/api/mail/search?q=hello&box=inbox&limit=20' \
  -H "Authorization: Bearer $TOKEN"
```

`box` 可选：`inbox` / `sent`。不传则搜索当前用户所有可见邮件。

## 附件下载

邮件响应中的附件对象包含 `id`、`fileName`、`contentType`、`size`、`path`。下载时使用：

```bash
curl -OJ 'http://127.0.0.1:8080/api/mail/{messageId}/attachments/{attachmentId}' \
  -H "Authorization: Bearer $TOKEN"
```

接口会校验当前 token 对应邮箱是否能看到该邮件，避免越权下载。

## Docker Compose

```bash
docker compose up -d --build
```

服务端口：

- HTTP: `8080`
- SMTP: `2525`
- POP3: `1110`

## CI

GitHub Actions 配置在 `.github/workflows/ci.yml`，包含：

- `mvn -B package -DskipTests`
- Docker image build

## Webmail 前端

已内置轻量 Webmail，适合内网 MVP 使用：

- 登录：`http://127.0.0.1:8080/webmail/login`
- 注册：`http://127.0.0.1:8080/webmail/register`
- 收件箱：`/webmail/inbox`
- 已发送：`/webmail/sent`
- 写邮件：`/webmail/compose`
- 搜索：`/webmail/search`

说明：当前 Webmail 使用 Ktor Cookie Session + CSRF Token；生产环境建议启用 HTTPS 并设置 `SECURE_COOKIES=true`。

## IMAP 说明

本轮优先实现了 Webmail，因为它能最快形成“注册-登录-收发-搜索-附件下载”的完整闭环。IMAP 协议状态机较复杂，建议后续单独实现或接入成熟库；当前 POP3 已可满足基础客户端收信。

## Webmail 安全会话、详情页、归档/删除

Webmail 已从 query token 改为 Cookie 会话：

- `WEBMAIL_SESSION`：HttpOnly，仅 `/webmail` 路径可见
- `WEBMAIL_CSRF`：CSRF token，表单提交时校验
- Cookie 使用 `SameSite=Lax`

新增页面：

- 邮件详情：`/webmail/message/{id}`
- 归档箱：`/webmail/archive`
- 回收站：`/webmail/trash`
- 登出：`/webmail/logout`

新增操作：

- 归档：`POST /webmail/message/{id}/archive`
- 取消归档：`POST /webmail/message/{id}/unarchive`
- 移入回收站：`POST /webmail/message/{id}/delete`
- 从回收站恢复：`POST /webmail/message/{id}/restore`

数据库迁移：

```text
src/main/resources/db/migration/V3__mailbox_flags.sql
```

新增 `mailboxes` 字段：

- `archived`
- `deleted`
- `read_flag`

当前删除是软删除，不会物理移除邮件或附件。


## Maven 构建

项目已增加 Maven 构建配置 `pom.xml`，当前推荐使用 Maven：

```bash
mvn -B test
mvn -B package
java -jar target/intranet-mail-kotlin-0.1.0.jar
```

生成的是 shade fat jar：

```text
target/intranet-mail-kotlin-0.1.0.jar
```

Dockerfile 和 GitHub Actions CI 也已切换到 Maven。
