# IoT Gateway 项目介绍与使用手册

## 1. 项目概述
IoT Gateway 是基于 Spring Boot 3.x + Netty + Nacos 2025.0.0.0 的高性能物联网网关，具备动态路由、黑白名单、Token 鉴权、限流、熔断等能力，支持配置热更新与高可用部署。

## 2. 系统架构
- **接入层**：Netty HTTP 服务器接收请求
- **路由层**：基于 AntPathMatcher 的路径匹配，支持负载均衡（轮询）与前缀剥离
- **安全层**：IP 白名单（支持 CIDR）、Token 鉴权、路由级 `require-auth` 控制
- **流量控制层**：令牌桶算法限流（Bucket4j），客户端维度限流
- **容错层**：Resilience4j 熔断器（失败率、慢调用率阈值）
- **配置管理层**：GatewayConfigHolder 解决 Spring Cloud Alibaba 2025.0.0.0 配置绑定 BUG，Nacos 原生 API 监听热更新
- **可观测性**：结构化日志（traceId）、健康检查、监控指标

## 3. 核心配置详解
### 3.1 application.yml 示例
```yaml
server:
  port: 8080

spring:
  application:
    name: iot-gateway
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
      config:
        server-addr: localhost:8848
        file-extension: yaml

gateway:
  server:
    port: 8080
    netty:
      boss-threads: 1
      worker-threads: 0  # 0 means Netty auto-calculates based on CPU cores

  # Rate Limiting Configuration
  rate-limit:
    enabled: true                           # 启用限流
    algorithm: token-bucket                 # 算法：token-bucket | leaky-bucket
    capacity: 100                          # 令牌桶容量
    refill-tokens: 100                     # 每次填充令牌数
    refill-period-seconds: 60              # 填充周期（秒）
    per-client:
      enabled: true                        # 启用客户端维度限流

  # Circuit Breaker Configuration
  circuit-breaker:
    enabled: true                          # 启用熔断
    failure-rate-threshold: 50             # 失败率阈值（%）
    wait-duration-open-state-seconds: 30   # 熔断打开状态等待时间
    sliding-window-size: 20                # 滑动窗口大小
    slow-call-duration-threshold-ms: 2000 # 慢调用阈值（毫秒）
    slow-call-rate-threshold: 80           # 慢调用率阈值（%）
    minimum-number-of-calls: 10            # 最小调用次数

  ip-whitelist:
    enabled: true
    allowed-cidrs:
      - 192.168.1.0/24
      - 10.0.0.0/8
  auth:
    token-header: X-IoT-Token
  routes:
    - id: device-data
      path-pattern: /api/device/data/**
      targets:
        - http://localhost:8090/
      strip-prefix: false
      require-auth: true
      header-transforms:
        X-Gateway: iot-gateway
    - id: public-api
      path-pattern: /api/public/**
      targets:
        - http://localhost:8091/
      strip-prefix: true
      require-auth: false
```

### 3.2 路由配置方法
- `id`：路由唯一标识
- `path-pattern`：匹配路径模式，常用 `Path=/api/xxx/**`
- `targets`：后端服务地址列表
- `strip-prefix`：是否剥离路径前缀
- `require-auth`：是否需要鉴权
- `header-transforms`：请求头转换规则

### 3.3 黑白名单配置方法
- `gateway.ip-whitelist.enabled`：启用开关
- `gateway.ip-whitelist.allowed-cidrs`：允许的 IP/CIDR 列表
- 支持标准 CIDR 表达式，如 `192.168.1.0/24`

## 4. 安全机制
### 4.1 IP 白名单
`IpWhitelistFilter` 在请求进入时校验来源 IP，不在白名单则返回 403。

### 4.2 Token 鉴权
`AuthFilter` 根据路由配置的 `require-auth` 决定是否校验 Header 中的 `X-IoT-Token`，无效或缺失返回 401。

### 4.3 路由级权限控制
支持在路由级别配置是否需要鉴权，提供灵活的权限控制策略。

## 5. 流量控制与容错
### 5.1 限流
`RateLimitFilter` 使用令牌桶算法，每个客户端独立限流，超出返回 429。

### 5.2 熔断
`CircuitBreakerFilter` 基于 Resilience4j，触发阈值后短路请求，返回 503。

## 6. 配置热更新
`GatewayConfigHolder` 通过 Nacos 原生 API 监听配置变更，解析 YAML 并更新内存配置，无需重启服务。该实现解决了 Spring Cloud Alibaba 2025.0.0.0 中 `@RefreshScope` + `@ConfigurationProperties` 的已知 BUG。

## 7. 部署与运维

### 7.1 传统部署
- 打包：`mvn clean package -DskipTests`
- 运行：`java -jar iot-gateway.jar --spring.profiles.active=dev`
- 健康检查：`GET /actuator/health`
- 监控指标：`/actuator/metrics`, `/actuator/prometheus`
- 管理接口：`/actuator/health`, `/actuator/info`

### 7.2 Docker 部署

#### 前提条件
- Docker 20.10+
- Docker Compose 2.0+
- JDK 17+ (构建时使用)

#### 快速开始

1. **构建镜像**
   ```bash
   # 在项目根目录执行
   docker build -t iot-gateway:latest .
   ```

2. **使用 Docker Compose 启动**
   ```bash
   # 启动所有服务（网关 + Nacos）
   docker-compose up -d
   
   # 查看服务状态
   docker-compose ps
   
   # 查看日志
   docker-compose logs -f iot-gateway
   ```

3. **单独运行网关容器**
   ```bash
   docker run -d \
     --name iot-gateway \
     -p 8080:8080 \
     -e SPRING_PROFILES_ACTIVE=docker \
     -v $(pwd)/logs:/app/logs \
     -v $(pwd)/config:/app/config \
     iot-gateway:latest
   ```

#### Docker Compose 服务说明

| 服务名 | 镜像 | 端口 | 说明 |
|--------|------|------|------|
| iot-gateway | 自定义构建 | 8080:8080 | IoT网关主服务 |
| nacos | nacos/nacos-server:v2.2.3 | 8848:8848, 9848:9848 | 配置中心 |

#### 环境配置

Docker环境下使用的配置文件：`config/application-docker.yml`

主要配置差异：
- Nacos地址：`localhost:8848` → `nacos:8848`
- 增加健康检查和监控端点
- 优化JVM参数和日志配置
- 支持Prometheus监控

### 7.3 Kubernetes 部署（可选）

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: iot-gateway
spec:
  replicas: 2
  selector:
    matchLabels:
      app: iot-gateway
  template:
    metadata:
      labels:
        app: iot-gateway
    spec:
      containers:
      - name: iot-gateway
        image: iot-gateway:latest
        ports:
        - containerPort: 8080
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "k8s"
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 30
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: iot-gateway-service
spec:
  selector:
    app: iot-gateway
  ports:
  - port: 80
    targetPort: 8080
    protocol: TCP
  type: ClusterIP
```

### 7.4 生产环境建议

#### 资源要求
- **CPU**: 2-4核
- **内存**: 2-4GB
- **磁盘**: 10GB+
- **网络**: 千兆网卡

#### JVM 调优
```bash
JAVA_OPTS="\
  -Xms2g -Xmx2g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/app/logs/heapdump.hprof \
  -Dspring.profiles.active=prod \
  -Dlogging.level.com.gateway=INFO \
  -Dlogging.level.org.springframework=WARN"
```

#### 监控告警
- 配置 Prometheus + Grafana 监控
- 设置关键指标告警（CPU、内存、QPS、错误率）
- 配置日志聚合（ELK Stack）
- 设置健康检查告警

#### 高可用部署
- 使用多副本部署（至少2个实例）
- 配置负载均衡器（Nginx/HAProxy）
- 使用外部化配置（Nacos集群）

### 7.5 运维命令

```bash
# 构建并启动
make docker-build && make docker-up

# 查看状态
make docker-status

# 查看日志
make docker-logs-gateway

# 停止服务
make docker-down

# 清理资源
make docker-clean

# 进入容器调试
make docker-shell
```

### 7.6 Makefile 支持

创建 `Makefile` 简化操作：

```makefile
.PHONY: docker-build docker-up docker-down docker-logs help

APP_NAME=iot-gateway
COMPOSE_FILE=docker-compose.yml

help:
	@echo "Available commands:"
	@echo "  make docker-build    - Build Docker image"
	@echo "  make docker-up       - Start services"
	@echo "  make docker-down     - Stop services"
	@echo "  make docker-logs     - Show logs"

# 其他Makefile内容详见项目根目录Makefile
```

## 8. API 示例
### 8.1 设备数据上报
```http
POST /api/device/data/upload HTTP/1.1
Host: gateway-host:8080
X-IoT-Token: abc123
Content-Type: application/json

{ "deviceId": "dev001", "value": 25 }
```

### 8.2 公共接口访问
```http
GET /api/public/status HTTP/1.1
Host: gateway-host:8080
```

## 9. 高级特性
### 9.1 动态路由
支持在 Nacos 配置中心动态修改路由配置，实时生效无需重启。

### 9.2 负载均衡
支持轮询算法在多个后端实例间分发请求。

### 9.3 路径重写
支持请求路径前缀剥离和后缀重写功能。

### 9.4 请求头转换
支持在请求转发过程中添加、修改、删除请求头。

## 10. 常见问题
- **配置不生效**：检查 Nacos 配置格式与 `GatewayConfigHolder` 解析逻辑
- **限流异常**：确认 `clientId` 提取规则与 Bucket4j 参数
- **熔断误触**：调整失败率与慢调用阈值
- **路由匹配问题**：路径模式按长度倒序匹配，精确匹配优先
- **热更新问题**：确保 Nacos 服务可用并检查配置监听器状态

## 11. 项目依赖
- Spring Boot 3.x
- Spring Cloud Alibaba 2025.0.0.0
- Netty
- Nacos 2025.0.0.0
- Bucket4j
- Resilience4j
- Reactor Netty

## 12. 扩展开发
- 新增过滤器：实现 `GatewayFilter` 接口并在过滤器链中注册
- 自定义路由匹配：修改 `Router.java` 中的 `AntPathMatcher` 逻辑
- 多环境配置：使用 Nacos 配置分组与 `spring.profiles.active`
- 自定义熔断策略：使用 `CircuitBreakerPolicy` 配置路由级熔断参数

## 13. 文档参考资料

完整的配置与使用指南请参阅以下文档：

- **增强配置手册**: [ENHANCED_CONFIG_GUIDE.md](./ENHANCED_CONFIG_GUIDE.md) - 详细的技术配置指南，包括架构、配置、部署、运维等内容
- **路由与安全配置指南**: [ROUTING_AND_SECURITY_CONFIG_GUIDE.md](./ROUTING_AND_SECURITY_CONFIG_GUIDE.md) - 详尽的路由配置和安全配置说明
- **完整文档**: [FULL_DOCUMENTATION.md](./FULL_DOCUMENTATION.md) - 项目的完整介绍和使用手册
---

以上内容为 IoT Gateway 的完整配置与使用手册，适用于开发、测试、生产环境快速上手与运维。