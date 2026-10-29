# 格物 Gewu 部署指南

> 目标：全新机器 **30 分钟内**从零到第一次成功对话。
> **部署形态**：后端 + 数据库走 Docker Compose；**前端不容器化**——构建出静态产物（dist），由宿主机 nginx 托管并把 `/api` 反代到后端。
> 两种场景：**在线部署**（可拉镜像）与**离线部署**（内网，镜像包导入）。

---

## 0. 前置要求

| 项 | 要求 |
|---|---|
| 操作系统 | Linux（Ubuntu 22.04+/CentOS 8+）或 Windows Server 2019+ |
| Docker | 24.0+ 与 Docker Compose v2.19+（`docker compose version` 验证） |
| nginx | 宿主机已安装（前端托管 + API 反代） |
| Node.js | ≥ 20（仅构建前端产物时需要；可在任意机器构建后拷贝 dist） |
| 内存 | ≥ 8G（后端 JVM 1G + PG + nginx） |
| 磁盘 | ≥ 20G（镜像 + 数据卷 + 知识库文件） |
| 模型 Key | 任一 OpenAI 兼容端点的 API Key（火山方舟/DeepSeek/Moonshot 等；部署后在网页「设置」里配） |

---

## 1. 在线部署（推荐）

### 1.1 获取代码

```bash
git clone https://github.com/<owner>/gewu-agent.git gewu          # 后端 + 编排
git clone https://github.com/<owner>/gewu-web.git gewu-web        # 前端
```

### 1.2 构建前端产物（约 2 分钟）

```bash
cd gewu-web
npm ci
npm run build          # 产物在 dist/
```

把 dist 放到 nginx 站点目录（或直接引用该路径）：

```bash
sudo mkdir -p /var/www/gewu
sudo cp -r dist/* /var/www/gewu/
```

### 1.3 启动后端 + 数据库（约 5-8 分钟，含镜像构建）

```bash
cd ../gewu
cp .env.example .env
vi .env   # 必改：GEWU_DB_PASSWORD、GEWU_JWT_SECRET
```

生成随机密钥（二选一）：

```bash
openssl rand -base64 24   # GEWU_DB_PASSWORD 用
openssl rand -base64 48   # GEWU_JWT_SECRET 用
```

> ⚠️ **GEWU_JWT_SECRET 与数据库备份分开存放**：密钥丢失不影响数据库，但所有登录态失效；两者别放同一目录同一权限。

```bash
docker compose up -d
docker compose ps        # 两个服务 healthy/running 即就绪
docker compose logs -f gewu-server   # 看到「格物 Gewu - 服务已启动」横幅
```

首次启动自动执行 `database/gewu-init.sql` 建表（幂等）。

### 1.4 宿主机 nginx 站点配置

`/etc/nginx/conf.d/gewu.conf`：

```nginx
server {
    listen       80;
    server_name  _;                      # 换成你的域名或 IP

    root  /var/www/gewu;                 # §1.2 的 dist 产物目录
    index index.html;

    gzip on;
    gzip_types text/plain text/css application/json application/javascript image/svg+xml;
    gzip_min_length 1024;

    # API 反代到后端容器（GEWU_SERVER_PORT 默认 8080）
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header Connection "";

        # SSE 必需：禁缓冲 + 长读超时（审批挂起流可长达数分钟）
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 600s;
        proxy_send_timeout 600s;
    }

    # SPA 路由回退
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 带 hash 的静态产物长缓存
    location /assets/ {
        expires 30d;
        add_header Cache-Control "public, immutable";
    }
}
```

```bash
sudo nginx -t && sudo systemctl reload nginx
```

### 1.5 初始化使用（5 分钟）

1. 浏览器打开 `http://<服务器IP>`
2. **首个管理员**：用用户名 `admin` 注册（服务端 `AdminAccounts.USERNAMES` 名单内的用户名自动获得管理员角色，默认名单含 `admin`）→ **注册后立即在「设置」改强密码**
3. 进入 **设置 → 模型设置**：配置模型端点（baseUrl/apiKey/modelName），点「测试连通性」
4. 进入 **知识库**：上传一份 md/txt/docx/pdf 文档，等状态变为「已索引」
5. 进入 **对话**：选择刚上传的知识库文档，提问验证引用

✅ 验收完成：答案出现知识库引用标记即部署成功。

---

## 2. 离线部署（内网无外网）

在**有网的机器**上：

```bash
# 1. 构建后端镜像
cd gewu && docker build -t gewu/gewu-server:latest .
# 2. 拉取 PG 向量镜像
docker pull pgvector/pgvector:pg16
# 3. 导出离线镜像包（仅两个：server + pgvector；前端是静态文件不需要镜像）
docker save gewu/gewu-server:latest pgvector/pgvector:pg16 -o gewu-images.tar
# 4. 前端产物
cd ../gewu-web && npm ci && npm run build && tar czf gewu-dist.tgz -C dist .
# 5. 打部署包（代码目录含 init.sql / compose / .env.example / nginx 配置样例）
cd .. && tar czf gewu-deploy-pkg.tar.gz gewu-images.tar gewu-dist.tgz gewu/docker-compose.yml gewu/.env.example gewu/database gewu/docs/DEPLOY.md
```

拷贝到内网机器：

```bash
docker load -i gewu-images.tar
tar xzf gewu-deploy-pkg.tar.gz
# 按包内目录结构摆放：compose + database + .env → docker compose up -d
# gewu-dist.tgz 解压到 nginx 站点目录，nginx 配置同 §1.4
```

后续升级：重新导出/导入新版本 server 镜像 + 新 dist 包即可；数据在 named volume 中不受影响。

---

## 3. 常用运维

| 操作 | 命令 |
|---|---|
| 看服务状态 | `docker compose ps` |
| 看后端日志 | `docker compose logs -f gewu-server`（容器内文件日志：`/app/logs/gewu-web.log`） |
| 重启后端 | `docker compose restart gewu-server` |
| 升级后端 | 重新 `docker compose build gewu-server && docker compose up -d` |
| 前端发布 | 重新 `npm run build`，覆盖站点目录，浏览器强刷（产物带 hash 无缓存问题） |
| 备份数据库 | 见 §5 |
| 备份知识库文件 | `docker run --rm -v gewu_gewu-uploads:/data -v $PWD:/backup alpine tar czf /backup/uploads.tgz /data` |
| 彻底重置 | `docker compose down -v`（⚠️ 删除全部数据） |

---

## 4. 架构与端口

```
浏览器 ──80──▶ 宿主机 nginx
                ├─ 静态资源（dist 产物，/var/www/gewu）
                └─ /api/* ──反代──▶ 127.0.0.1:8080 ──▶ gewu-server 容器(8081)
                                                      ├─ JDBC ──▶ gewu-db 容器(5432, PG16+pgvector)
                                                      └─ /app/uploads 卷（知识库文件）
宿主机端口：nginx 80 / GEWU_SERVER_PORT(8080) / GEWU_DB_PORT(15432，可注释不暴露)
```

- 前端仓库：`gewu-web`（Vue 3 + Vite；**不容器化**，静态产物 + nginx）
- 后端仓库：本仓库（多模块 Maven：gewu-{common,client,security,web}）
- 开发模式不容器化：后端 `mvn spring-boot:run -pl gewu-web`（8081）+ 前端 `npm run dev`（3001，代理已配）

---

## 5. 数据备份与恢复

```bash
# 数据库备份（建议每日 cron）
docker exec gewu-db pg_dump -U gewu gewu | gzip > gewu-db-$(date +%F).sql.gz

# 恢复
gunzip -c gewu-db-2026-10-23.sql.gz | docker exec -i gewu-db psql -U gewu gewu
```

> ⚠️ 数据库备份 + GEWU_JWT_SECRET + 知识库文件卷，三者齐了才是完整备份；三者分开存放（JWT 密钥泄露=可伪造任意登录态，别和备份放一起）。

---

## 6. 常见问题（FAQ）

**Q1：`docker compose up` 后 gewu-server 一直 restarting？**
看日志 `docker compose logs gewu-server`。最常见：① DB 密码含特殊字符没加引号；② 数据库还没 ready（healthcheck 有 90s 启动窗口，等一下）；③ 端口被占用（改 .env 端口）。

**Q2：网页能开，对话报「模型连接失败」？**
模型 Key 未配置或端点不通。到 **设置 → 模型设置** 配置并点测试连通性。

**Q3：首次注册的账号不是管理员？**
管理员=用户名在服务端 `AdminAccounts.USERNAMES` 名单中的账号（默认 `admin`）。用用户名 `admin` 注册；若已被占用，改名单后重启。

**Q4：上传 pdf 后检索不到内容？**
当前版本解析**文本层** pdf；扫描件（无文本层）会明确报错提示，OCR 支持在路线图 v2.0。

**Q5：SSE 流式回答一次性出现而不是逐字？**
检查 nginx 的 `/api/` 反代是否配了 `proxy_buffering off`（§1.4 配置已含）；若中间还有其他代理层，同样要关缓冲。

**Q6：内网机器时间不对导致 token 频繁过期？**
容器内 TZ=Asia/Shanghai 已设，但宿主机时钟漂移会影响 JWT 校验，校时（ntp）即可。

## U4 Redis

- compose 已内置 `gewu-redis`（redis:7-alpine，无持久化纯缓存），`gewu-server` 自动连接，无需额外配置。
- 宿主机调试端口：`GEWU_REDIS_PORT`（默认 16379）。
- 应用侧开关：`GEWU_REDIS_ENABLED`（默认 true；false 时全部走 DB/内存原路径）。
- Redis 不可用不影响业务：令牌校验/限流/配额/设置缓存自动降级回 DB/内存。
