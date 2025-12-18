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
- 打包：`mvn clean package`
- 运行：`java -jar iot-gateway.jar --spring.profiles.active=dev`
- 健康检查：`GET /health`
- 监控指标：`/metrics`
- 管理接口：`/admin/routes`, `/admin/circuit-breakers`

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

## 14. 版本特性 (v2.0.0)

- ✨ **解决配置绑定BUG**: 使用Nacos原生API绕过Spring Cloud Alibaba 2025.0.0.0的@RefreshScope + @ConfigurationProperties BUG
- ✨ **增强配置热更新**: GatewayConfigHolder提供线程安全的配置管理
- ✨ **改进路由机制**: Router支持动态配置热更新
- ✨ **优化过滤器链**: 支持配置热更新的过滤器执行
- 🐛 **修复路由状态同步**: 确保路由requireAuth配置实时生效

---

以上内容为 IoT Gateway 的完整配置与使用手册，适用于开发、测试、生产环境快速上手与运维。