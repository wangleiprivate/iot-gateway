# 验证热加载功能

## 启动应用
1. 确保应用已启动
2. 检查日志中是否有Nacos连接成功的信息

## 测试步骤
1. 初始状态：Nacos中`require-auth`为`true`
   - 访问 `/api/yt/**` 路径应触发鉴权
   - 在日志中看到 "需要鉴权" 相关信息

2. 修改Nacos配置
   - 将Nacos中`iot-route`的`require-auth`从`true`改为`false`

3. 验证热加载
   - 等待几秒钟（通常3-5秒）
   - 再次访问相同路径，应不再触发鉴权
   - 在日志中看到 "无需鉴权" 或类似信息

## 检查点
- 检查应用日志中的配置刷新信息
- 确认路由配置已更新
- 验证请求处理逻辑已改变

## 手动刷新接口（如果自动刷新未生效）
```bash
# 检查当前配置
curl http://localhost:8080/internal/config/status

# 手动触发刷新
curl -X POST http://localhost:8080/internal/config/refresh
```