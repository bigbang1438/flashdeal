# =====================================================================
# FlashDeal 多阶段构建
#
# 阶段 1：Node 构建 Vue 前端
# 阶段 2：Maven 构建 Spring Boot 后端（把前端产物打进 static/）
# 阶段 3：仅保留 JRE + jar，镜像体积最小化
#
# 注意：本机没有 Docker，此文件是交付物，未在本机实际构建过。
#       无 Docker 环境请按 README「快速开始（无 Docker）」直接用 Maven 运行。
# =====================================================================

# ---------- 阶段 1：前端构建 ----------
FROM node:22-alpine AS frontend
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci --no-audit --no-fund || npm install --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---------- 阶段 2：后端构建 ----------
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build
# 先只复制 pom，利用 Docker 层缓存避免每次都重新拉依赖
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline -DskipTests || true
COPY src ./src
# 把前端产物放到 Spring Boot 静态资源目录，实现同源托管
COPY --from=frontend /build/frontend/dist ./src/main/resources/static
RUN mvn -B -q clean package -DskipTests

# ---------- 阶段 3：运行时 ----------
FROM eclipse-temurin:21-jre-alpine
LABEL maintainer="FlashDeal" \
      description="高并发秒杀交易系统"

WORKDIR /app

# 非 root 运行
RUN addgroup -S flashdeal && adduser -S flashdeal -G flashdeal \
    && mkdir -p /app/data && chown -R flashdeal:flashdeal /app

COPY --from=backend /build/target/flashdeal.jar /app/app.jar
RUN chown flashdeal:flashdeal /app/app.jar

USER flashdeal

# H2 文件数据库落在挂载卷上，容器重启数据不丢
VOLUME ["/app/data"]

EXPOSE 8080

ENV JAVA_OPTS="-Xms512m -Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8"

HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/api/metrics/summary >/dev/null 2>&1 || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
