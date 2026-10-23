# ============================================
# 格物 Gewu Server — 多阶段构建（分层 jar 优化缓存）
# 构建: docker build -t gewu-server .
# ============================================

# ---------- 构建层 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先拷 pom 下载依赖（利用 Docker 层缓存，源码变更不重下依赖）
COPY pom.xml ./
COPY gewu-common/pom.xml gewu-common/
COPY gewu-client/pom.xml gewu-client/
COPY gewu-security/pom.xml gewu-security/
COPY gewu-web/pom.xml gewu-web/
RUN mvn -q -B dependency:go-offline -DskipTests || true

# 再拷源码全量构建
COPY gewu-common/src gewu-common/src
COPY gewu-client/src gewu-client/src
COPY gewu-security/src gewu-security/src
COPY gewu-web/src gewu-web/src
RUN mvn -q -B package -DskipTests

# Spring Boot 分层解包（依赖层与应用层分离，运行层缓存友好）
RUN java -Djarmode=layertools -jar gewu-web/target/gewu-web-1.0-SNAPSHOT.jar extract --destination /build/layers

# ---------- 运行层 ----------
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 时区
ENV TZ=Asia/Shanghai
RUN apk add --no-cache tzdata && cp /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 分层拷贝（变动频率从低到高）
COPY --from=build /build/layers/dependencies/ ./
COPY --from=build /build/layers/spring-boot-loader/ ./
COPY --from=build /build/layers/snapshot-dependencies/ ./
COPY --from=build /build/layers/application/ ./

# 知识库上传文件卷（应用按 user.dir/uploads 落盘）
VOLUME ["/app/uploads"]

ENV JAVA_OPTS=""
EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8081/api/chat/health > /dev/null 2>&1 || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
