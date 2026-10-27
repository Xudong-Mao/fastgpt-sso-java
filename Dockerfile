# 多阶段构建 Dockerfile for FastGPT SSO Java
# 第一阶段：构建阶段
FROM public.ecr.aws/docker/library/maven:3.8.6-openjdk-8-slim AS builder

# 设置工作目录
WORKDIR /app

# 复制构建文件和源码
COPY pom.xml .
COPY lib ./lib
COPY src ./src

# 构建应用
RUN --mount=type=cache,target=/root/.m2 mvn clean package -DskipTests -B

# 第二阶段：运行阶段
FROM public.ecr.aws/docker/library/openjdk:8-jre-slim

# 设置维护者信息
LABEL maintainer="FastGPT SSO Java Team"
LABEL description="FastGPT SSO Java Application"

# 创建非root用户以提高安全性
RUN groupadd -r appuser && useradd -r -g appuser appuser

# 设置工作目录
WORKDIR /app

# 从构建阶段复制jar文件
COPY --from=builder /app/target/fastgpt-sso-java-*.jar app.jar

# 更改文件所有者
RUN chown -R appuser:appuser /app

# 切换到非root用户
USER appuser

# 暴露端口（Spring Boot 默认端口）
EXPOSE 8080

# 用户映射表目录，运行时挂载: -v ./config:/app/config
RUN mkdir -p /app/config && chown appuser:appuser /app/config
# 设置JVM参数
ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseG1GC -XX:+UseContainerSupport"

# 启动应用
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
