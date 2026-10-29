# 模式 B：预打包 jar 暂存目录

把本机或 CI 打好的 fat jar 复制到这里（固定文件名 `gewu-server.jar`），再用 `Dockerfile.jar` 构建运行时镜像：

```bash
mvn package -DskipTests
cp gewu-web/target/gewu-web-1.0-SNAPSHOT.jar docker/jar/gewu-server.jar
docker build -f Dockerfile.jar -t gewu/gewu-server:latest .
```

Windows：

```bat
copy /Y gewu-web\target\gewu-web-1.0-SNAPSHOT.jar docker\jar\gewu-server.jar
```

说明：

- jar 不进 git（`.gitignore` 已忽略本目录 `*.jar`），目录本身保留。
- 与模式 A（`Dockerfile`，编译期打包）的运行层完全一致，仅构建来源不同。
- compose 场景在 `.env` 设 `GEWU_SERVER_DOCKERFILE=Dockerfile.jar` 后 `docker compose build gewu-server`。
