# 日光与天空 · 0.9.0

从总览点击“日光与天空”进入。沿用总览选择的区域与已有和风凭据，不新增账号、依赖或权限。返回按钮和系统返回手势回到总览；“查看手机实测光照”进入探测页。

## 数值含义与边界

| 内容 | 来源与解释 |
| --- | --- |
| 日出、日落 | 和风天文 API 根据地点与日期计算，非手机测量；保留服务返回的时区偏移 |
| 白昼时长、倒计时、进度弧线 | 本机根据日出、日落及当前时间计算；弧线只表达白昼时间进度，不代表太阳轨迹、高度角或实际晴天时长 |
| 太阳高度角、方位角 | 和风天文计算；明确显示计算对应时间。高度角负值表示太阳在地平线下；方位以地理北为 0°，不与手机磁北指南针叠加 |
| 太阳角度的高度参数 | 用户点击“当前位置”时，若系统报告有效定位高度，则使用其整数米近似值；Android `Location.altitude` 是手机的 WGS84 椭球高度，不是地面或平均海平面海拔。页面标注来源和可用的垂直精度；手选城市或高度缺失时标注“0 m · 参考假设” |
| 月相、月面照明比例 | 和风逐小时天文计算，只选当前小时；缺失不沿用另一小时，不据此判断地面亮度或月亮是否被云遮挡 |
| 紫外线 UVI | 沿用当前区域的真实天气 API 指标及其获取时间/缓存标记，不额外请求；没有真实区域指标时显示 `—`，不填演示值 |
| 手机光照 lx | 仍在探测页来自 SensorManager。与天文计算、区域紫外线分开，lx 不直接换算 UVI |

定位高度仅为近似参考，不承诺固定误差范围。垂直精度没有返回时不制造精度值；无高度字段时不能把系统默认 0 当成有效读数。太阳角度使用选定区域上一次单次定位的高度，不持续跟踪手机高度；换地点应回总览重新定位。模拟位置明确标注。

若已获得不带高度的快速定位，同时 GPS 可用，则最多再等 5 秒尝试获得带高度的定位；没有取得仍使用有效的区域位置，不因此伪造高度。整个单次定位仍受原有 30 秒超时和前台取消控制。

0.9.1 起，总览原海拔卡改为[近似定位高度](overview-height.md)，明确标注定位估计；不作为准确地形海拔。保存环境快照可保留该高度、来源及独立定位时间，天文数据仍不写入历史；数据库 schema v1 不变。

## 时间与缺失值

- 新搜索的城市保留 GeoAPI 返回的 IANA 时区；查询日期、太阳角度的时间与 UTC 偏移都使用该时区，正确处理夏令时和半小时时区。
- 老版本已保存城市或单次定位没有地区时区时，明确标为“设备时区”，提示跨时区时重新搜索城市。不通过经度猜测行政时区。
- 日出/日落为空、错误日期或顺序异常时，不计算白昼，不擅自判断极昼/极夜。
- 日出前倒计时至当日日出，白昼期间倒计时至日落；日落后显示当日时段结束，不把今天的日出包装成明天的时间。
- 当地区日期变化，停止显示旧日期的白昼进度、倒计时及当前月相，并提示刷新。太阳角度超过 10 分钟或日期变化后，保留原值与对应时间并标为较早计算。

## 请求、缓存和安全

天文接口通过独立 `AstronomyDataService` / `SkyRepository` 边界接入，界面不依赖 JSON、Host 或认证字段。复用现有专属 Host、JWT、严格域名校验及禁止重定向的客户端。

首次主动打开页面最多请求三项：日出日落、月相、太阳角度。正常重新进入时，同地点、同查询日期的日出日落和月相使用缓存；太阳角度在 10 分钟内复用。超过 10 分钟重新进入时只更新太阳角度。手动刷新请求三项，至少间隔 30 秒；不自动重试，不后台轮询。

前台仅每 30 秒更新本地时钟用于倒计时，不因此请求 API。退出、打开设置或退到后台会取消天文请求；天文页面不注册传感器或位置监听。地点、日期和时区必须匹配才能复用缓存；换区域、重新导入或清除凭据会清除天文缓存。凭据配置和天文缓存操作共用互斥锁，避免换区域与旧请求写缓存相互覆盖。

天文缓存和所选位置均保存在现有 Keystore/AES-GCM 加密的私有 `noBackupFilesDir`。高度作为太阳角度参数发送给和风，用户主动选择的区域坐标仍按两位小数发送。接口分别处理失败并保留同区域同日期的已有部分，每个部分显示原获取时间，不冒充全部刚更新。403、余额、限流、网络和缓存写入失败均有明确提示。

## 已核对的协议

核对日期：2026-10-17。

| 接口 | 参数 |
| --- | --- |
| `GET /v7/astronomy/sun` | `location=经度,纬度`，`date=yyyyMMdd` |
| `GET /v7/astronomy/moon` | 同上，`lang=zh`；读取 `moonPhase` 的 `fxTime`、`name`、`illumination` |
| `GET /v7/astronomy/solar-elevation-angle` | 坐标、`date`、`time=HHmm`、`tz`（如 `0800`、`-0530`）、`alt` 米；读取 `solarElevationAngle`、`solarAzimuthAngle` |

保留 `refer.sources` 与 `refer.license` 的来源及许可说明。日期、角度和百分比无效时留空，不补 0。

官方依据：[日出日落](https://dev.qweather.com/docs/api/astronomy/sunrise-sunset/)、[月相](https://dev.qweather.com/docs/api/astronomy/moon-and-moon-phase/)、[太阳角度](https://dev.qweather.com/docs/api/astronomy/solar-elevation-angle/)、[城市时区](https://dev.qweather.com/docs/api/geoapi/city-lookup/)、[定位高度与垂直精度](https://developer.android.com/reference/android/location/Location#getAltitude())。

天文属于天气和基础服务的计费组；目前官方按该组月累计请求量阶梯计费，0–50,000 次为免费档，此后按价目计费。不能理解为每个接口单独有 50,000 次；实际权限、用量与账单以个人控制台为准。[官方定价](https://dev.qweather.com/docs/finance/pricing/)

## 测试复现

```powershell
. .\scripts\environment.ps1
.\gradlew.bat --offline --no-daemon assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
.\scripts\start-emulator.ps1
adb -s emulator-5560 install -r .\app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5560 install -r .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb -s emulator-5560 shell am instrument -w -r -e notClass com.ambient.app.ui.sky.SkyUiFixture com.ambient.app.test/androidx.test.runner.AndroidJUnitRunner
.\scripts\test-sky-emulator.ps1
```

UI 脚本只操作核对名称后的专用 `Ambient_API_31`。离线注入明确标为“界面测试 · 非实际天文数据”的测试缓存与合成测试凭据，覆盖三套主题的深浅色、缺失值、高度参数和导航；不是现场天文实测。测试配置只存在 androidTest APK 中，不随主包发布。脚本结束恢复原先加密配置及网络状态。

`node scripts/check-sky.cjs` 是显式运行的真实接口探针，使用官方文档北京示例坐标，最多三次调用，无自动重试，遇到失败即停止。仅输出状态及字段存在性，不输出 Host、凭据、JWT 或错误体；成功的公开示例响应保存在被忽略的 `.tools/sky-probe/`。

需要诊断权限时可显式加 `--control-weather`，在天文请求前多检查一次既有天气接口。只识别官方白名单中的错误类型，不回显服务原始错误说明。API 授权应修改对应 JWT 凭据：先确认下拉选择，再保存“API 限制”；控制台提示的传播时间内可能暂时返回请求限制，不需要重新生成密钥。

`scripts/test-sky-live-emulator.ps1` 需要已有本机配置和成功的桌面探针响应。它通过正常系统文件选择器导入凭据、手选北京测试城市，验证 Android 端真实数据及断网重启缓存；不会保存历史快照。测试结束恢复原先加密配置，并清理导入临时文件。

## 0.9.0 验证结果

- Debug 主包与 androidTest 包构建成功，226 项单元测试、55 项 Android 实机框架/数据库/状态测试通过（在 API 31 专用模拟器运行，不包含 UI 夹具入口）。Android Lint 0 错误、5 条已有依赖更新提示。
- 12 项离线界面检查通过：三主题各深浅色、来源标记、日出日落及弧线、参考高度、定位高度与垂直精度、月相、缺失值、手机光照入口、后台恢复及返回。
- 8 项原有基础回归通过：演示、虚拟光照、暂停恢复、后台停采、主题保存、历史空状态、导航及无致命异常。
- 首次真实探针返回官方 `security-restriction`，未猜测返回值填充页面；用户在控制台启用天文后，等待授权传播，三个天文接口及天气对照均 HTTP 200。
- 4 项 Android 真实接口验收通过：系统文件选择器导入并加密保存、选择真实北京测试城市及地区时区、日出日落与桌面结果一致、太阳角度和月相正常显示、断网重启保留原数据、无传感器监听和致命异常。
- 测试配置和缓存均恢复原状，Downloads 临时导入文件已清理。源码候选文件、主 APK 解压内容和测试期间的模拟器凭据密文均通过真实凭据明文扫描。
- 本轮没有安装到 Pura 70 Pro。真实手机的高度可用性、网络、系统时区和手势仍需实际验收；模拟器的高度展示使用明确标记的合成定位夹具，不能当作 Pura 70 Pro 的读数。

截图位于 `docs/screenshots/sky/`：`live-beijing*` 为手选北京区域的真实 API 天文计算，其余是有明确测试标题的离线界面夹具。

最终包另外验证了“当日缓存”标记。Debug APK：`dist/Ambient-0.9.0-debug.apk`，13,047,337 字节，APK v2 签名通过；SHA-256：`FEB3BEFE95D471894ADEFC3CE92AF98C4FF57007E737DA353DEA149A2DCB77CC`。本机私钥、配置和 APK 未加入 Git，仓库包含源码、测试、脚本及验证截图。
