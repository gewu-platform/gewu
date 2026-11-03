# Docker 构建入口

> 本目录是容器化构建的索引。**实际文件多数留在仓库根目录**——原因见下「为什么文件在根目录」。
> 详细部署步骤见 [docs/DEPLOY.md](../docs/DEPLOY.md)。

## 一、两种构建模式

| 模式 | Dockerfile | 适用场景 | 构建耗时 | 依赖 |
|---|---|---|---|---|
| **A · 编译期打包**（默认） | `../Dockerfile` | 有外网、希望镜像自包含 | 首次较慢（镜像内 `mvn package`） | 无需本机 JDK |
| **B · 预打包 jar** | `../Dockerfile.jar` | 内网 / 企业 CI：本机或 CI 先打好包 | 快（只做分层解包） | 需先 `mvn package` |

两种模式的**运行层完全一致**（分层 + healthcheck + 时区），只有构建来源不同。

### 模式 A：编译期打包

```bash
cd <仓库根目录>
docker compose build gewu-server
docker compose up -d
```

### 模式 B：预打包 jar

三步缺一不可：

```bash
# ① 打 fat jar
mvn package -DskipTests

# ② 拷 jar 到固定位置（文件名必须是 gewu-server.jar）
cp gewu-web/target/gewu-web-1.0-SNAPSHOT.jar docker/jar/gewu-server.jar
# Windows: copy /Y gewu-web\target\gewu-web-1.0-SNAPSHOT.jar docker\jar\gewu-server.jar

# ③ 用 .env 切换 Dockerfile 后构建
GEWU_SERVER_DOCKERFILE=Dockerfile.jar docker compose build gewu-server
```

也可脱离 compose 直接构建：

```bash
docker build -f Dockerfile.jar -t gewu/gewu-server:latest .
```

## 二、为什么文件在根目录（不要随意移动）

| 文件 | 位置 | 为什么不能移 |
|---|---|---|
| `.dockerignore` | **必须根目录** | Docker 只认 build context 根的 `.dockerignore`；移走即失效，`target/`、`.git/`、`node_modules/` 会全进构建上下文，镜像构建变慢数倍 |
| `.env` / `.env.example` | **必须根目录** | `docker compose` 默认读取**当前工作目录**的 `.env` |
| `docker-compose.yml` | **建议根目录** | compose 中相对路径的基准是**compose 文件所在目录**。现有挂载 `./database/gewu-init.sql`、`./database/migrations` 都依赖这个基准；移进 `docker/` 会静默失效 |
| `Dockerfile` / `Dockerfile.jar` | 根目录（可移但不必） | 靠 `-f` 指定，不依赖自身位置。移入 `docker/` 需同步改 30+ 处引用且每次构建要写 `-f docker/Dockerfile`，收益不值 |

**结论**：本目录只放**构建期资产与说明**（`jar/` 预打包产物、本文档），配置文件保持在根目录。

## 三、目录内容

```
docker/
├── README.md          本文档（构建入口索引）
└── jar/
    ├── README.md      模式 B 的 jar 放置说明
    └── gewu-server.jar  预打包产物（不纳入版本控制，见 .gitignore）
```

## 四、镜像内容要点

- 基础镜像：`eclipse-temurin:17-jre-alpine`
- 时区：`Asia/Shanghai`（镜像内已 `apk add tzdata`）
- 工作目录：`/app`
- 端口：`8081`
- 健康检查：`GET /actuator/health`（旧版 `/api/chat/health` 仍可用）
- **数据库迁移文件**：镜像内置 `/app/database/migrations`（构建时 `COPY`），
  运行时可被只读挂载覆盖，见下节

## 五、数据库迁移与容器

Flyway 使用 `filesystem:database/migrations`（相对进程工作目录 `/app`）：

| 场景 | 做法 |
|---|---|
| 镜像自带迁移 | 构建时已 `COPY database/migrations ./database/migrations`，开箱可用 |
| 免重建更新迁移 SQL | compose 已配置只读挂载 `./database/migrations:/app/database/migrations:ro`，改宿主机文件后重启容器即生效 |
| 迁移目录不在默认位置 | 设 `GEWU_FLYWAY_LOCATIONS=filesystem:/绝对路径` |

> ⚠️ 若自行构建镜像且**改动了 `COPY` 路径**，务必确认 `database/migrations` 没被
> `.dockerignore` 排除——否则容器内目录为空，Flyway 会静默认为「无迁移可跑」，
> 排查时极难发现。

## 六、常见问题

**Q：`docker compose build` 报找不到 `database/migrations`？**
检查 `.dockerignore` 是否排除了该路径（**不应排除**），以及是否在仓库根目录执行构建。

**Q：容器启动后表结构不全？**
确认迁移文件确实进了镜像：`docker exec gewu-server ls /app/database/migrations`。
若为空 → `.dockerignore` 误排除或 `COPY` 路径写错。

**Q：能否只用 `docker run` 不用 compose？**
可以，但需自行传全环境变量（`SPRING_DATASOURCE_*`、`JWT_SECRET` 等），
并挂载迁移目录。compose 已把必要变量收敛好，建议优先用 compose。

## 七、相关文档

- 部署全流程： [docs/DEPLOY.md](../docs/DEPLOY.md)
- 环境变量一览： `docs/DEPLOY.md` §「V2.x 新增配置项一览」
- 架构总览： [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md)
