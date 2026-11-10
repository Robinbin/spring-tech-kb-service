# ── Stage 1：Maven 构建 ────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-jammy AS build
WORKDIR /workspace

# 先复制 pom.xml，利用 Docker 层缓存加速依赖拉取
COPY pom.xml .
RUN mvn dependency:go-offline -B -q

COPY src src
RUN mvn package -DskipTests -B -q

# ── Stage 2：提取 Spring Boot 分层 ────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy AS layers
WORKDIR /workspace
COPY --from=build /workspace/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ── Stage 3：最终运行镜像 ──────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# 按变更频率从低到高复制各层，最大化层缓存命中率
COPY --from=layers /workspace/extracted/dependencies/ ./
COPY --from=layers /workspace/extracted/spring-boot-loader/ ./
COPY --from=layers /workspace/extracted/snapshot-dependencies/ ./
COPY --from=layers /workspace/extracted/application/ ./

EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
