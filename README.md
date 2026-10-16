# Ambient · 环境探测仪

个人使用的原生 Android 环境仪表：**和风区域环境 + 真实设备传感器 + 本机环境快照 + 三套渐变主题**。

源码仓库：[berg-star/Ambient](https://github.com/berg-star/Ambient)。当前版本 **0.5.0**；参阅[环境快照与历史记录](docs/history.md)、[风向罗盘](docs/wind-compass.md)、[和风接入与配置说明](docs/qweather.md)、[版本记录](CHANGELOG.md)和[Git 使用说明](docs/version-control.md)。

## 开发环境

- Windows 命令行开发，不需要 Android Studio。
- JDK 17、Gradle Wrapper 8.13、AGP 8.13.2、Kotlin 2.3.21。
- Android SDK 36 / Build Tools 35.0.0；最低 Android 8.0（API 26）。
- Compose BOM 2025.12.01、Material 3、Lifecycle 2.10.0、Coroutines。
- Retrofit 3.0.0、OkHttp 4.12.0；EdDSA 0.3.0 用于 Android 8+ 的 JWT 签名。
- Room 2.8.5 / SQLite、KSP 2.3.12；历史数据库从 schema v1 开始。
- 包名 `com.ambient.app`，版本 `0.5.0`。

本机复用了 `L:\AI-Accounting\.tools` 中已有的 JDK 和 SDK。旧项目源码和 AVD 不参与 Ambient 构建；Gradle 依赖缓存已复制到 Ambient 自己的 `.gradle-user-home`，运行构建不会使用旧项目缓存作为写入目录。所有机器路径只在被 Git 忽略的本地配置中。

首次换电脑时：复制 `toolchain.local.ps1.example` 为 `toolchain.local.ps1`，填入已有 JDK/SDK 目录；创建 `local.properties`，填入 `sdk.dir=你的SDK路径`（建议用正斜杠，盘符冒号需转义，例如 `L\:/Android/Sdk`）。缺失工具时先确认再安装，不需要模拟器、NDK 或完整 IDE 才能构建。

```powershell
cd L:\Ambient
.\scripts\build.ps1
# 已缓存完整依赖时可使用：
.\scripts\build.ps1 -Offline
```

脚本配置只影响当前进程，不修改系统 `JAVA_HOME` 或 `PATH`。构建包含 `assembleDebug`、单元测试和 Android Lint。标准命令也可直接运行：

```powershell
. .\scripts\environment.ps1
.\gradlew.bat --no-daemon assembleDebug testDebugUnitTest lintDebug
```

Debug APK 的标准路径为 `app\build\outputs\apk\debug\app-debug.apk`；精度提示修正见 [传感器精度说明](docs/sensor-accuracy.md)，运动功能验证见 [加速度与定位速度验证](docs/motion-validation.md)，外观验证见 [三套渐变主题验证](docs/themes-validation.md)，原始功能验证见 [第一阶段验证记录](docs/phase-1-validation.md)。官方仓库在部分大陆网络环境中可能无法访问；项目没有默认引入第三方镜像，也不会在失败时静默替换仓库。

已有本机模拟器时，可在独立 AVD 中复现验证：

```powershell
.\scripts\start-emulator.ps1
.\scripts\smoke-test-emulator.ps1
.\scripts\test-themes-emulator.ps1
.\scripts\test-motion-emulator.ps1
```

启动脚本复用已安装的 API 31 系统镜像，创建本项目专用 `Ambient_API_31`，后台运行且不弹窗口。测试脚本只操作核实身份后的 `emulator-5560`，不会向实体手机安装；会设置测试用分辨率/主题、安装 Ambient、输入虚拟光照事件并验证采集生命周期。运动测试会清空专用模拟器中 Ambient 的测试状态，并安装独立的测试定位工具；不会改动实体手机或旧项目。脚本不会自动下载模拟器或镜像。

历史记录另有数据库仪器测试和完整操作测试，见[验证范围与运行步骤](docs/history.md#验证)。操作测试使用手选测试城市的真实区域 API，会临时关闭专用模拟器网络，并清理本轮测试记录及测试凭据。

## 目录

```text
Ambient/
├─ app/
│  ├─ build.gradle.kts
│  ├─ schemas/                        # Room 数据库结构，纳入版本管理
│  └─ src/
│     ├─ main/
│     │  ├─ AndroidManifest.xml
│     │  ├─ java/com/ambient/app/
│     │  │  ├─ MainActivity.kt          # 导航、状态栏、轻量依赖装配
│     │  │  ├─ domain/
│     │  │  │  ├─ environment/         # 区域服务/来源/独立海拔契约
│     │  │  │  ├─ history/             # 快照、记录与保存规则
│     │  │  │  ├─ location/            # 定位速度与精度、过期规则
│     │  │  │  └─ sensor/              # 传感器能力与事件模型
│     │  │  ├─ data/
│     │  │  │  ├─ environment/         # 演示、独立服务接口、和风适配与加密缓存
│     │  │  │  ├─ history/             # Room / SQLite 持久化
│     │  │  │  ├─ location/            # LocationManager 单次区域定位与卫星测速
│     │  │  │  └─ sensor/              # SensorManager 后台采样
│     │  │  └─ ui/
│     │  │     ├─ overview/            # 总览 ViewModel / StateFlow
│     │  │     ├─ history/             # 保存、列表与详情 ViewModel
│     │  │     ├─ sensors/             # 探测 ViewModel
│     │  │     ├─ location/            # 测速启停与前台生命周期
│     │  │     ├─ screens/             # 总览/探测/记录/设置
│     │  │     ├─ components/          # 统一卡片标签与线形图标
│     │  │     └─ theme/               # 深色/浅色/跟随系统
│     │  └─ res/
│     ├─ test/                         # 事件处理、生命周期、来源、缓存与保存规则测试
│     └─ androidTest/                  # 真实 Room / SQLite 与历史 ViewModel 测试
├─ gradle/                             # 版本目录与完整 Wrapper
├─ scripts/                            # 本项目构建/模拟器脚本
└─ docs/                               # 设计边界与验证证据
```

## 功能与数值来源

| 内容 | 来源与边界 |
| --- | --- |
| 室外温度、湿度、海平面气压、AQI、PM2.5、PM10、风速风向、紫外线 | 启用和风并选择区域后为 **区域 API**；中国 AQI 只取 `cn-mee`，缺失留空。关闭真实数据开关时为明确标注的固定演示 |
| 地形海拔 | **尚无数据**，显示 `—`；未用 GNSS 高度或气压推算 |
| 光照 lx、地磁场 XYZ μT、加速度 XYZ m/s²、陀螺仪 XYZ rad/s | 真机由 Android `SensorManager` 返回的事件；不支持时显示不支持 |
| 去重力加速度 XYZ m/s² | Android 系统提供的线性加速度，标为系统计算；默认显示，可切回含重力读数；不支持时不自行估算 |
| 定位速度 km/h、m/s | 系统 GPS 提供方的速度估计；需主动开启与精确定位授权，不使用加速度积分或两点距离推算；有精度则展示，无有效速度或过期则显示 `—` |
| 本机气压 hPa | 仅气压传感器存在并成功监听时显示 |
| 三轴合成强度 | 由真实 XYZ 计算的 `√(X²+Y²+Z²)`，单独标注“计算值”；不是第四项直接测量 |
| 模拟器中的传感器 | Android 模拟器产生的**虚拟传感器事件**，页面显著标识；不属于 Pura 70 Pro 实测 |
| 朝向、倾斜角 | 本阶段未实现，不将磁场模长、加速度或角速度误当作角度 |
| 环境快照与记录页 | 手动保存当前真实区域 API 数据；缓存快照保留缓存标记及原获取时间。演示数据不能保存。可查看详情、修改备注、确认删除单条记录；尚无传感器历史、CSV 或趋势图 |

安装包不含凭据。首次启动仍是演示；设置中依次导入配置 JSON 与 Ed25519 私钥 PEM，再搜索城市或点击“使用当前位置”。本机凭据、所选区域和单份天气缓存由 Android Keystore 中的 AES 密钥加密，排除备份；私钥只在签名时进入应用内存，未宣称 Ed25519 在硬件内签名。详见[配置步骤](docs/qweather.md)。

首页显示“获取于”或“缓存 · 获取于”；当前接口没有独立观测时间，不把获取时间冒充观测时间。断网时保留该区域缓存和原时间；换区域先清掉旧缓存，不用另一城市的数据填充。天气成功但空气失败时仍显示天气，空气指标留空并提示。自动加载优先使用 10 分钟内的缓存，手动刷新间隔至少 30 秒，不后台轮询。

在首页点击“保存环境快照”，确认当前区域、获取时间和缓存状态，可填写最多 300 字备注。确认页锁定打开时的数据，不会因随后刷新而替换保存内容；同一次保存失败后可重试，重复确认不会生成重复记录。记录页展示保存时间与区域，详情保留原数值、单位、来源、缺失值、风向与归因，并区分保存时间、获取时间和观测时间；服务未提供观测时间时明确说明。保存操作不额外请求网络或位置。

历史记录保存在 App 私有目录的普通 SQLite 数据库，**历史数据库尚未加密**，且排除云备份及设备迁移备份。关闭区域 API 或清除天气凭据与缓存不会删除历史；卸载 App 或清除 App 数据会丢失记录。当前不能导出，请勿把它作为唯一的长期数据备份。实现与边界见[历史记录说明](docs/history.md)。

设置提供三套渐变主题，并在本机保存主题和明暗选择：

| 主题 | 推荐外观 | 视觉与布局 |
| --- | --- | --- |
| 日光琥珀 | 浅色 | 奶油白到浅杏背景，无框衬线温度、独立暖白小卡 |
| 雾蓝暮色 | 浅色 | 雾蓝与粉杏背景，无框大数字、连接指标网格 |
| 流光玻璃 | 深色（首次启动默认） | 深靛与烟紫背景，玻璃层次温度主卡、紧凑指标列表 |

在“设置 → 主题风格”切换时会应用对应的推荐明暗，随后仍可单独选深色、浅色或跟随系统；三套主题都有完整深浅配色。旧版只保存过明暗选择时，保留该选择并使用流光玻璃主题。渐变由静态画笔绘制，卡片采用不透明渐变保证读数可读，没有模糊层或持续动画。

首页显示当前数据模式和每项来源标签；点击温度或指标可查看物理说明。底部导航、返回手势、系统栏安全区域和可滚动布局适配普通手机；华为 Pura 70 Pro 的最终读数、校准与手势体验需要真机验收。

页面导航暂用 Compose 保存状态和 Android 返回处理，适合当前三个并列页面与一个设置页。ViewModel 与服务通过轻量工厂装配，没有引入复杂路由或依赖注入框架。

## 性能与可信边界

传感器回调在独立 `HandlerThread`，请求采样间隔 50 ms。每个传感器独立 StateFlow：连续传感器按事件纳秒时间戳约每 100 ms 发布；光照属于 on-change 传感器，保留每次实际变化并去除同值同精度事件，避免节流丢掉末次变化。曲线最多 60 点。单卡收集状态，不让单个传感器事件驱动整个页面重组；不使用持续动画。

只在探测页可见且 Activity 处于 RESUMED 状态时监听。切换页面、打开设置、退到后台或手动暂停会注销监听并退出线程。暂停保留最后读数与原事件时间，但清空曲线；恢复清空旧读数，等待新事件。首次 5 秒无事件、监听失败、低精度和不支持均有状态提示。光照等 on-change 传感器长时间无变化不被当作故障。

历史数据库读写与快照编解码在 IO 调度器执行；列表只查询摘要列，打开单条详情时才读取完整快照。每次保存只写入一条记录，不持续写传感器事件，也不额外启用后台任务。schema v1 已提交到仓库，未启用破坏性迁移。

加速度卡默认显示系统计算的去重力读数，静置时应接近零但允许噪声和偏差；“含重力”模式保留原始加速度，静置时合成值约 9.8 m/s²。采集中切换模式会清空该卡旧读数和曲线；不会直接从模长减去常数或强行归零。角速度不是角度；照度不是紫外线；本机气压不是海平面气压。数据模型预留 `DEMO / DEVICE / REGIONAL_API / ESTIMATED` 四类来源。

定位速度独立于传感器采集开关，默认关闭。仅在探测页且 Activity 恢复时监听 GPS，离开、后台或关闭开关即停止；在探测页内滚动不会中断测速。不请求后台定位，不存储经纬度或轨迹，不上传位置。缺少速度字段不会当作 0；超过 10 秒未更新会隐藏速度。精度为系统报告的 68% 置信范围，缺失时明确提示。模拟器及系统标记的模拟位置明确显示为测试数据。室内、低速及静止时可能无读数或漂移，不用于精确速度或距离测量。

## 安装到华为手机

1. 在构建成功后，将 `app-debug.apk` 通过 USB 文件传输复制到手机。
2. 用手机“文件管理”打开 APK，并按系统提示允许该文件来源安装；首次安装按手机当前安全设置确认。
3. 或开启开发者选项和 USB 调试，在手机允许这台电脑调试后执行：

```powershell
. .\scripts\environment.ps1
adb devices -l
# 将设备序列号替换为 adb 列表中你自己的手机：
adb -s 你的手机序列号 install -r .\app\build\outputs\apk\debug\app-debug.apk
```

仅在确认目标序列号后安装；该包名与旧记账 App 不同。Debug 版用于个人测试，不是公开发布签名版本。

## 下一阶段

先在 Pura 70 Pro 验收环境快照、离线查看、备注修改和单条删除，并继续观察国内网络、单次定位、去重力精度与室外测速。随后实现历史记录 CSV 导出，再考虑按指标查看变化；海拔仍需独立选源并注明高程基准，不从定位高度直接填充。

定位仅使用 Android 自带 LocationManager，没有引入 Google Play Services、账号、AI 模型或自建服务器。首页区域定位会在主动点击后将两位小数坐标发送给和风；探测页测速不上传位置，两者分别控制。历史快照只保留区域名称及指标，不保存经纬度或轨迹。

参考：[AGP 8.13 兼容矩阵](https://developer.android.com/build/releases/agp-8-13-0-release-notes)、[Android 传感器概览](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)、[和风天气官方开发者文档](https://dev.qweather.com/)。
