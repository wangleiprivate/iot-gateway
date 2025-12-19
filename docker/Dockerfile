FROM eclipse-temurin:17-jre-jammy AS runtime

# 设置工作目录
WORKDIR /app

# 创建非root用户
RUN groupadd -r gateway && useradd -r -g gateway gateway

# 复制JAR文件
COPY target/iot-gateway.jar app.jar

# 创建必要的目录并设置权限
RUN mkdir -p /app/logs /app/config && \
    chown -R gateway:gateway /app

# 切换到非root用户
USER gateway

# 暴露端口
EXPOSE 8080

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# 启动应用
ENTRYPOINT ["java", "-jar", "app.jar"]