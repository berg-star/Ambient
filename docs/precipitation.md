# 降水预报 · v0.10.0

总览 → **降水预报**。沿用所选区域，主动打开页面才获取降水预报。所有数值均为和风区域预报，非手机实测；没有演示天气自动填补。

## 两个时间尺度

| 视图 | 时间间隔 | 数值含义 | 概率 |
| --- | --- | --- | --- |
| 2 小时 | 每 5 分钟 | 5 分钟累计降水量，mm | 接口未提供 |
| 24 / 72 小时 | 每小时 | 小时累计降水量，mm | 0–1 转为百分比，单独显示 |

柱高从零开始，按当前可见预报点的最大值缩放，并显示纵轴范围。点击柱子查看时间、累计量、类型和可用概率；横向滑动查看后续时段。两种尺度不拼接、插值、平滑或换成同一雨强；缺口不补零。零、缺失和过期是不同状态。雪、混合降水等保留类型，不能都称为雨。

使用实际返回时间筛选尚未结束的预报时段，最多显示 24 个五分钟点或 24 / 72 个小时点；正在进行中的当前时段可保留。显示实际首尾预报点时间，不向最后一个时间点后额外延伸，不保证接口一定返回完整时长。过去时段不移到未来；完全过期时隐藏图和旧描述。部分缺失保留 `—`，全部降水量缺失时不展示“无降水”提示。

和风宣称分钟降水在中国为 1 公里精度，并非“手机周围半径 1 公里”，也不保证楼栋级准确。手选城市使用城市坐标附近的预报，不代表全市。移动后需重新点击“使用当前位置”。逐小时数据不用于断言具体哪一分钟起雨。

## 时间、缓存与请求

- 分钟预报显示 `updateTime` 和本机获取时间；接口的相对时间描述对应其更新时间，不当作实时倒计时。
- 新逐小时接口没有文档定义的发布时间，不编造；页面明确获取时间不等于预报发布时间。
- 地区时区优先，未知时明确标为设备时区；时间均附月日，可区分跨日时段。
- 首次打开最多两次请求；一次请求 72 小时，24 / 72 切换只筛选本地数据，不增加请求。
- 分钟缓存 5 分钟、小时缓存 1 小时内按需复用；分钟提供方更新时间超过 15 分钟也标旧。手动刷新两项，间隔至少 30 秒，无后台轮询、自动重试或新增定位/传感器监听。页面内仅每 30 秒更新时段有效性。
- 两项独立失败处理，有效小时缓存不会因分钟接口失败而消失。错误时保留原获取时间；旧预报明确提示，已结束的时段隐藏。
- 沿用 Keystore/AES-GCM 私有缓存，原始坐标仅本机加密保存，上传最多两位小数坐标。切换区域（包括同名不同坐标）、重新导入或清除凭据会清空降水缓存。
- 页面离开/进入后台取消请求，旧请求结果不得覆盖新区域；历史数据库和 CSV 不自动写入预报。

## 官方协议

核对日期：2026-10-17。沿用专属 API Host + Ed25519 JWT，禁用重定向和网络自动重试，不新增依赖或权限。

- [分钟级降水](https://dev.qweather.com/docs/api/minutely/minutely-precipitation/)：`GET /v7/minutely/5m?location=经度,纬度&lang=zh`；检查业务 `code=200`，读取 `minutely.fxTime/precip/type`、`updateTime`、`summary`、`refer.sources/license`。
- [逐小时天气](https://dev.qweather.com/docs/api/weather/weather-hourly-forecast/)：`GET /weather/v1/hourly/{latitude}/{longitude}?hours=72&lang=zh`；读取 `hours.forecastTime`、`precipitation.amount.value/unit`、`probability`、`type` 和 `metadata.attributions`。只接受 mm 单位；概率越界、非有限/负降水量保留缺失。
- JWT 凭据需允许 **分钟降水、天气预报**。控制台修改可能延迟生效；403 展示分类错误，不自动重试。
- 费用以[官方价格](https://dev.qweather.com/docs/finance/pricing/)和个人控制台为准。两接口属于“天气和基础服务”，与其他同组请求合并计算；不能把免费额度理解为各接口单独额度。

## 验证

```powershell
. .\scripts\environment.ps1
.\gradlew.bat --offline --no-daemon assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
.\scripts\test-orientation-android-emulator.ps1
.\scripts\test-precipitation-emulator.ps1
.\scripts\smoke-test-emulator.ps1
# 显式真实接口验收，不由构建自动执行；需要本机被忽略的配置及已授权凭据：
node scripts/check-precipitation.cjs
.\scripts\test-precipitation-live-emulator.ps1
```

专用模拟器为 `Ambient_API_31` / `emulator-5560`，测试脚本拒绝操作真机。离线夹具仅在 androidTest APK 内，标题明确“非实际预报”，临时天气密文备份并在 finally 恢复。真实测试使用手选北京，不是用户所在位置；正常文件选择器导入后移除临时明文文件，结束恢复原配置。测试不向历史库写入降水。

本轮 Debug 构建、263 项 JVM 单元测试、60 项 Android 测试、12 项离线界面检查及 8 项基础回归通过，最终 Lint 未发现问题。真实桌面接口分别返回 24 / 72 个点；Android 通过正常凭据导入、真实分钟量与更新时间、逐时量与概率、24 / 72 切换、断网重启恢复加密缓存等验收。真实测试后恢复测试前天气文件，无传感器监听或致命异常。本轮没有在实体手机上运行。

截图位于 `docs/screenshots/precipitation/`，均为明确标记的离线合成预报，不伪装真实雨情。截图记录首轮交互验收，最终包补充了明确的横向滑动提示、首尾预报点时间标注及 Compose 数值状态优化。新数据到达时同步更新时间判断，避免沿用请求前的界面时钟误判为旧预报。

最终 Debug APK：`dist/Ambient-0.10.0-debug.apk`，13,113,535 字节，APK v2 签名验证通过。SHA-256：`BEBD3C8807371262BC89C0755A97133B34E5E05A07979A76F1E23C109C5D04FE`。APK 和本机凭据均被 Git 忽略。
