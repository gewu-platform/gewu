# 模式 B：预打包 jar 暂存目录

把本机或 CI 打好的 fat jar 复制到这里，**固定文件名 `gewu-server.jar`**：

```bat
copy /Y gewu-web\target\gewu-web-1.0-SNAPSHOT.jar docker\jar\gewu-server.jar
```

```bash
cp gewu-web/target/gewu-web-1.0-SNAPSHOT.jar docker/jar/gewu-server.jar
```

完整构建步骤（含模式切换方式与 PowerShell 注意事项）见：

- [docker/README.md](../README.md) §模式 B —— 两种构建模式对比与端口说明
- [docs/DEPLOY.md](../../docs/DEPLOY.md) §1.3 —— 部署全流程与 FAQ

说明：

- jar 不进 git（`.gitignore` 已忽略本目录 `*.jar`），本 README 用于保留目录。
- 与模式 A（`Dockerfile`，编译期打包）的运行层完全一致，仅构建来源不同。
