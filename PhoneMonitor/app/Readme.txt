# Phone Monitor

这是一个Android手机监控应用，可以收集并展示手机的各种系统指标。结合 Prometheus 和 Grafana，可以实现数据的收集、存储和可视化。

## 生成安装文件 (APK)

### 1. 生成签名密钥
1. 在 Android Studio 中点击顶部菜单 `Build` -> `Generate Signed Bundle / APK`
2. 选择 `APK`
3. 点击 `Create new` 创建新的密钥库
4. 填写密钥信息：
   - Key store path: 选择保存位置
   - Password: 设置密钥库密码
   - Alias: 密钥别名
   - Password: 密钥密码
   - Validity: 25 (有效期，年)
   - Certificate: 填写你的信息

### 2. 生成 APK
1. 点击顶部菜单 `Build` -> `Generate Signed Bundle / APK`
2. 选择 `APK`
3. 选择已创建的密钥库
4. 填写密码
5. 选择 `release` 版本
6. 选择 `V1` 和 `V2` 签名版本
7. 点击 `Finish`

生成的 APK 文件位于：`app/release/app-release.apk`

## 功能特点

- CPU使用率监控
- 内存使用情况
- 存储空间统计
- 电池状态监控
- WiFi连接状态和信号强度
- USB连接状态
- 设备基本信息

## 监控指标

- `phone_cpu_usage`: CPU使用率
- `phone_memory_total`: 总内存(KB)
- `phone_memory_free`: 空闲内存(KB)
- `phone_memory_usage_percent`: 内存使用率
- `phone_battery_level`: 电池电量
- `phone_battery_charging`: 充电状态
- `phone_storage_total`: 总存储空间(GB)
- `phone_storage_available`: 可用存储空间(GB)
- `phone_storage_usage_percent`: 存储空间使用率
- `phone_wifi_connected`: WiFi连接状态
- `phone_wifi_signal_strength`: WiFi信号强度
- `phone_wifi_link_speed`: WiFi连接速度(Mbps)
- `phone_usb_connected`: USB连接状态

## 配置 Prometheus + Grafana

### 1. Prometheus 配置

创建 prometheus.yml 配置文件：
```yaml
global:
  scrape_interval: 5s  # 每5秒采集一次数据

scrape_configs:
  - job_name: 'phone_monitor'
    static_configs:
      - targets: ['手机IP:8080']  # 替换为你手机的IP地址
    metrics_path: '/metrics'      # 指标接口路径
```

启动 Prometheus：
```bash
docker run -d \
  --name prometheus \
  -p 9090:9090 \
  -v /path/to/prometheus.yml:/etc/prometheus/prometheus.yml \
  prom/prometheus
```

### 2. Grafana 配置

启动 Grafana：
```bash
docker run -d \
  --name grafana \
  -p 3000:3000 \
  grafana/grafana
```

配置步骤：
1. 访问 `http://localhost:3000`（默认用户名/密码：admin/admin）
2. 添加 Prometheus 数据源：
   - Configuration -> Data Sources -> Add data source
   - 选择 Prometheus
   - URL 填写：`http://prometheus:9090`
   - 点击 Save & Test

### 3. Grafana 仪表板配置

导入以下 Dashboard 配置：
```json
{
  "dashboard": {
    "title": "手机监控面板",
    "panels": [
      {
        "title": "CPU使用率",
        "type": "gauge",
        "datasource": "Prometheus",
        "targets": [
          {
            "expr": "phone_cpu_usage",
            "refId": "A"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "min": 0,
            "max": 100,
            "thresholds": {
              "steps": [
                { "value": 0, "color": "green" },
                { "value": 70, "color": "yellow" },
                { "value": 85, "color": "red" }
              ]
            }
          }
        }
      },
      {
        "title": "内存使用率",
        "type": "gauge",
        "datasource": "Prometheus",
        "targets": [
          {
            "expr": "phone_memory_usage_percent",
            "refId": "A"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "min": 0,
            "max": 100,
            "thresholds": {
              "steps": [
                { "value": 0, "color": "green" },
                { "value": 75, "color": "yellow" },
                { "value": 90, "color": "red" }
              ]
            }
          }
        }
      },
      {
        "title": "电池电量",
        "type": "gauge",
        "datasource": "Prometheus",
        "targets": [
          {
            "expr": "phone_battery_level",
            "refId": "A"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "min": 0,
            "max": 100,
            "thresholds": {
              "steps": [
                { "value": 0, "color": "red" },
                { "value": 20, "color": "yellow" },
                { "value": 50, "color": "green" }
              ]
            }
          }
        }
      },
      {
        "title": "存储空间使用率",
        "type": "gauge",
        "datasource": "Prometheus",
        "targets": [
          {
            "expr": "phone_storage_usage_percent",
            "refId": "A"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "min": 0,
            "max": 100,
            "thresholds": {
              "steps": [
                { "value": 0, "color": "green" },
                { "value": 80, "color": "yellow" },
                { "value": 90, "color": "red" }
              ]
            }
          }
        }
      },
      {
        "title": "WiFi信号强度",
        "type": "stat",
        "datasource": "Prometheus",
        "targets": [
          {
            "expr": "phone_wifi_signal_strength",
            "refId": "A"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "thresholds": {
              "steps": [
                { "value": 0, "color": "red" },
                { "value": 2, "color": "yellow" },
                { "value": 3, "color": "green" }
              ]
            }
          }
        }
      }
    ],
    "refresh": "5s"
  }
}
```

### 4. Prometheus 告警规则

可以添加以下告警规则到 Prometheus：
```yaml
groups:
  - name: phone_alerts
    rules:
      - alert: HighCPUUsage
        expr: phone_cpu_usage > 90
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "CPU使用率过高"
          description: "CPU使用率超过90%持续5分钟"

      - alert: LowBattery
        expr: phone_battery_level < 20
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "电池电量低"
          description: "电池电量低于20%"
```

### 5. 常用 PromQL 查询

```promql
# CPU使用率最大值
max_over_time(phone_cpu_usage[1h])

# 内存使用率变化率
rate(phone_memory_usage_percent[5m])

# WiFi连接状态
phone_wifi_connected == 1

# 存储空间剩余GB
phone_storage_available
```

## 使用步骤

1. 确保手机和电脑在同一网络
2. 在手机上运行监控应用
3. 获取手机IP地址
4. 修改 prometheus.yml 中的目标地址
5. 启动 Prometheus 和 Grafana
6. 导入仪表板配置
7. 访问 Grafana（http://localhost:3000）查看监控数据

## 注意事项

1. 应用需要相关权限才能正常工作
2. 确保防火墙允许相关端口访问
3. 监控数据每5秒更新一次
4. 电池信息每30秒更新一次 