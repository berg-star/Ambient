# 和风天气接入 · 0.4.0

本文记录 0.4.0 的和风接入：区域天气和空气质量、城市搜索、单次定位、凭据导入与加密缓存。该轮未实现历史快照或 CSV；后续版本已加入[历史记录](history.md)和[CSV 导出](csv-export.md)。天气预报列表、海拔估算及后台自动刷新仍未实现。

## 在手机上使用

1. 安装当前版本的 Debug APK（0.7.0 为 `dist/Ambient-0.7.0-debug.apk`），可覆盖相同签名的旧版。环境快照见[历史说明](history.md)，筛选、对比与可选趋势见[历史分析](history-analysis.md)，CSV 导出见[导出说明](csv-export.md)；本文下方保留 0.4.0 的接入验证记录。
2. 通过 USB 将电脑的 `qweather.local.json` 和 `secrets/qweather/ed25519-private.pem` 复制到自己手机的“下载”目录。不要用聊天、邮件或公开网盘传递私钥。
3. 在 App 的设置中点击“导入配置与私钥”。第一个文件选择器选 JSON，第二个选私钥 PEM；不要误选公钥 PEM。
4. 显示“已配置”后返回首页，点击“选择城市”搜索并选择结果，或者“使用当前位置”并授予定位权限。
5. 数据成功后页首标注“和风天气 · 区域 API，非手机实测”，每项来源单独标识。
6. 导入成功后可删除手机“下载”目录中的这两份文件；保留电脑上受保护的备份。清除 App 数据或卸载后需要重新导入。

配置 JSON 的四项分别是开发者 ID、Ambient 项目 ID、JWT 凭据 ID、控制台的专属 API Host。Host 只填写域名，不加 `https://`、端口或路径。`privateKeyFile` 是电脑本地校验脚本的路径，手机通过第二个文件选择器读取私钥，不依赖该 Windows 路径。仓库只提交空白 `.example`，真实配置和 `secrets/` 已忽略。

未导入时保持演示模式。配置开关关闭时不请求区域 API，可继续使用传感器。城市搜索不需要定位权限；定位失败或拒绝授权时可手选城市。选择城市代表该城市区域，单次定位则使用当时位置；移动后需再次点击定位，不后台追踪。

## 已核对的官方协议

核对日期：2026-10-16。每个用户使用自己的 API Host，不使用通用旧域名。

| 服务 | 路径及关键约定 |
| --- | --- |
| JWT | `EdDSA/Ed25519`，Header 的 `kid` 为凭据 ID；Payload 的 `iss` 为开发者 ID、`sub` 为项目 ID。`iat` 比当前时间早 30 秒，`exp` 为当前时间后 10 分钟 |
| 实时天气 | `/weather/v1/current/{latitude}/{longitude}`；经纬度各最多两位小数。湿度为 0–1，转换为百分数；风速 m/s；气压是海平面气压；紫外线来自 `uvIndex` |
| 实时空气 | `/airquality/v1/current/{latitude}/{longitude}`；AQI 仅取 `indexes.code=cn-mee` 的 `aqiDisplay`。PM2.5 对应 `pm2p5`，PM10 对应 `pm10`；不把 US AQI 或 QAQI 改名为中国 AQI |
| 城市查询 | `/geo/v2/city/lookup?location=城市名称`；由服务返回城市坐标，取实际搜索结果，不预设北京是用户所在地 |

服务与 UI 之间保留独立 `EnvironmentDataService` / `ManagedEnvironmentRepository` 边界。Retrofit + OkHttp 只存在于数据层，响应通过独立解码器转换为领域模型。页面显示官方返回的 `metadata.attributions`，不省略归因。

依据：[认证](https://dev.qweather.com/docs/configuration/authentication/)、[API Host](https://dev.qweather.com/docs/configuration/api-host/)、[实时天气](https://dev.qweather.com/docs/api/weather/weather-current/)、[实时空气](https://dev.qweather.com/docs/api/air-quality/air-current/)、[AQI 标准](https://dev.qweather.com/docs/api/air-quality/aqi-list/)、[城市查询](https://dev.qweather.com/docs/api/geoapi/city-lookup/)。费用和额度以[官方定价](https://dev.qweather.com/docs/finance/pricing/)及个人控制台为准；本版没有后台轮询或自动重试，搜索仅在点击后发起。

## 数据与隐私边界

- 手机光照、磁场、原始加速度和陀螺仪来自传感器；去重力加速度来自系统计算。区域数据不使用传感器猜测，不用演示值填补网络缺失值。
- 海拔仍显示 `—`，未将 GPS 椭球高或气压估计当作地形海拔。
- 实时接口未返回独立观测时间，显示的是成功获取时间。空气失败不沿用旧空气数值冒充本次更新。
- 用 AES-256-GCM 加密凭据、所选区域和缓存；AES 密钥保存在 Android Keystore。为兼容 API 26，Ed25519 签名在应用内存中完成，未声称导入私钥是硬件内不可导出的签名密钥。
- 密文位于 App 私有 `noBackupFilesDir`，禁用备份；不把私钥、JWT、配置 ID 写入源码、资源、日志或 APK。网络只发送短期 JWT，不发送私钥。
- 严格限制 Host 为专属 `*.qweatherapi.com`，禁用 HTTP/HTTPS 重定向，避免把 Bearer 凭据转发给其他域名。错误只展示分类信息，不回显服务端原始错误体或令牌。
- 首页单次定位仅在主动点击后启动，使用不超过两分钟且精度在 5 km 内的定位。最多等待 30 秒；退出首页或后台立即注销监听。手机只授予大致位置时尝试系统网络定位，不可用则提示搜索城市。
- 坐标保留两位小数后发送给和风，所选原始位置与缓存仅在本机加密保存；测速功能不上传位置。系统标记的模拟定位会明确提示。
- 更换区域先清除旧缓存，包括同名不同坐标的区域。网络失败保留原区域的数值和原获取时间；没有缓存就显示无数据。启动优先使用 10 分钟内缓存，手动刷新至少间隔 30 秒。

个人测试采用本机导入方式。未来公开发布时应另行设计凭据分发或服务端代理；不能直接发布含个人私钥的安装包。

## 验证记录

- 桌面使用本地 JWT 对官方文档中的北京坐标进行真实天气、空气、城市接口各一次检查，均 HTTP 200；空气实际返回 `cn-mee`。不是用户当前位置测量。
- Debug 构建、56 项单元测试通过；Android Lint 0 错误，联网检查提示 5 项已有依赖存在新版本。
- 单元测试覆盖 JWT 签名验证与篡改拒绝、Host 边界、百分比/单位转换、中国 AQI 选择、缺失值、归因、错误体隐藏、接口路径、空气部分失败，以及持久缓存恢复、限流、同名区域切换、错误私钥导入和配置清除。
- 专用 API 31 模拟器：真实系统文件选择器导入、Keystore 加密存储、手机端 JWT 城市搜索与天气/空气成功；升级/进程重启后可恢复配置和缓存。
- 关闭模拟器网络再重启，可显示原区域缓存；刷新失败有提示且原获取时间不变，连续刷新被限流。
- 单次定位使用独立测试 APK 的模拟上海坐标验证，天气为该坐标的真实区域 API 数据，位置明确标为模拟；成功后及离开前台均已注销监听。模拟器内置 GPS 提供了异常的事件时间戳，因此被拒绝，未放宽时间校验。
- 截图位于 `docs/screenshots/weather/`；其中北京为手选测试城市，上海为模拟位置，均非 Pura 70 Pro 的用户位置实测。
- Pura 70 Pro 上的实际网络、单次定位和文件管理器导入仍需安装本版验收。
- 原有基础回归 8 项通过：演示标记、虚拟光照事件、暂停恢复、后台停采、设置与主题保存、历史占位及导航、当前进程无致命异常。首次回归因 ADB 连接中断未完成，恢复连接后已完整重跑通过。

最终 Debug APK：`dist/Ambient-0.4.0-debug.apk`，12,196,827 字节，APK v2 签名验证通过。

SHA-256：`6EC97BF1B1EFB20F452C3A338C008CBF05C4755519400189C895EE2FD607544C`。

本次还扫描了待提交文件及 APK 解压内容，未发现本机实际凭据 ID、Host、私钥 PEM/DER 或种子；模拟器凭据文件也未包含这些明文。模拟器下载目录中的导入文件已删除，通过设置清除了测试凭据及缓存，独立定位工具已重置并卸载。

重复离线缓存测试：先在专用模拟器导入并选择城市，再运行 `scripts/test-weather-cache-emulator.ps1`；脚本仅操作核对名称后的 `Ambient_API_31`，会暂时关闭网络并在结束时恢复。`scripts/check-qweather.cjs` 可在已有 Node.js 环境中进行本机三项接口检查，不打印凭据或 JWT，成功的公开示例区域响应写入被忽略的 `.tools/qweather-probe/`。
