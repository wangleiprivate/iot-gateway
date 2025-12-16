# require-auth 热加载测试指南

## 测试环境准备

1. 确保 Nacos 配置中心正常运行
   - 地址: http://61.134.69.50:8848/nacos
   - 命名空间: iot_gateway
   - 分组: IOT_GROUP

2. 确保当前配置文件 `application-dev.yml` 包含以下路由配置：

```yaml
gateway:
  routes:
    - id: iot-route
      path-pattern: /api/yt/**
      targets:
        - http://61.134.69.91:9528/api/yt/
      strip-prefix: true
      require-auth: true  # 初始值为 true
      header-transforms:
        X-Gateway: iot-gateway-dev
```

## 测试步骤

### 1. 启动应用

```bash
mvn spring-boot:run
```

观察启动日志，确认：
- 路由加载成功
- iot-route 的 requireAuth=true

### 2. 第一次测试（require-auth=true）

发送请求测试鉴权功能：

```bash
curl -X GET http://localhost:8080/api/yt/test
```

**预期结果：**
- 返回 401 Unauthorized 错误
- 日志显示 "路由 iot-route 需要鉴权"

### 3. 修改配置

在 Nacos 控制台：
1. 进入配置管理 -> 配置列表
2. 找到 `application-dev.yml` 配置
3. 点击编辑
4. 将 iot-route 的 `require-auth: true` 改为 `require-auth: false`
5. 发布配置

### 4. 验证配置热加载

观察应用日志，应该看到：
```
收到 Nacos 配置变更通知
开始刷新配置...
GatewayProperties 配置已重新绑定
刷新路由配置...
路由配置刷新完成
路由: id=iot-route, pattern=/api/yt/**, requireAuth=false
```

### 5. 第二次测试（require-auth=false）

再次发送测试请求：

```bash
curl -X GET http://localhost:8080/api/yt/test
```

**预期结果：**
- 如果后端服务正常，应该得到正常响应
- 日志显示 "路由 iot-route 配置为无需鉴权，直接放行"

### 6. 再次修改配置验证

将 `require-auth` 改回 `true`，重复步骤 3-5，验证配置可以双向切换。

## 关键日志监控点

### 配置变更监听
```
收到 Nacos 配置变更通知 (原生监听器): dataId=application-dev.yml
检测到网关配置变更，变更的配置项: [gateway.routes[1].require-auth]
```

### 路由配置刷新
```
刷新路由配置...
路由配置刷新完成，路由数量: 2 -> 2
路由: id=iot-route, pattern=/api/yt/**, requireAuth=false
```

### 请求处理
```
[trace-id] 路由 iot-route requireAuth=false
[trace-id] 路由 iot-route 配置为无需鉴权，直接放行
```

## 故障排查

### 1. 配置未生效
- 检查 Nacos 配置是否正确发布
- 确认应用连接到正确的 Nacos 地址和命名空间
- 查看是否有配置监听错误日志

### 2. 热加载失败
- 检查 `@RefreshScope` 注解是否正确
- 确认 `ConfigurationPropertiesRebinder` 是否正常工作
- 查看是否有配置绑定错误

### 3. 鉴权逻辑异常
- 确认 `AuthFilter` 使用 `ObjectProvider` 获取配置
- 检查路由匹配逻辑是否正确
- 验证 `Router.refresh()` 方法是否被调用

## 预期行为总结

1. **require-auth=true**: 请求需要鉴权，无 Token 时返回 401
2. **require-auth=false**: 请求直接转发到后端服务，无需鉴权
3. **配置变更**: 无需重启应用，配置变更后立即生效
4. **日志输出**: 清晰显示配置变更和路由决策过程