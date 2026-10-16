# 加速度切换与定位速度 · 0.3.0

验证日期：2026-10-16。延续现有三套渐变主题，本次只增加运动读数功能；区域天气仍为明确标记的演示数据。

## 已实现与来源

- 加速度卡默认「去重力」，读取设备实际提供的 `TYPE_LINEAR_ACCELERATION`，来源标为系统计算；静置接近零，允许噪声和偏差。
- 可以切换「含重力」，读取 `TYPE_ACCELEROMETER`；保留三轴分量与合成值，静置约 9.8 m/s²。只替换所选加速度监听，其他传感器曲线不被重置。
- 不支持去重力通道时明确提示，可切换原始读数；没有自行滤波回退、常数相减或强行归零。
- 定位速度由 Android `LocationManager.GPS_PROVIDER` 提供，不依赖 Google 服务，不通过加速度积分或两点距离推算。
- 默认关闭测速，主动开启后申请前台精确定位。大致位置权限、拒绝授权、定位关闭、设备无 GPS、无有效速度和异常均有独立提示。
- 仅系统明确提供速度字段时显示 km/h 和 m/s。若提供速度精度，则展示系统报告的 68% 置信范围；缺失时明确提示。
- 根据定位事件的单调时间戳判断新鲜度；超过 10 秒未更新即隐藏数值，无效时间戳会被拒绝。模拟器及系统标记的模拟位置明确标为测试数据。
- 测速开关独立于传感器采集开关，仅在探测页且应用处于前台时监听；进入其他页面、打开设置、退到后台或关闭开关即停止。在探测页内部滚动不会停止测速。
- 不申请后台定位、不保存经纬度或轨迹、不上传位置。本次没有接入和风天气，也没有加入新的第三方依赖。

## 构建与静态检查

`scripts/build.ps1 -Offline` 已成功完成 Debug APK、单元测试和 Android Lint。

- 36 项单元测试全部通过，无失败、无错误。包含去重力默认值/切换/缺失能力、速度单位换算/可选字段/无效值/过期时间及前台生命周期。
- Android Lint：`No issues found.`。
- APK 签名验证通过，包名 `com.ambient.app`，versionCode 3 / versionName 0.3.0，minSdk 26 / targetSdk 36。
- APK：`L:\Ambient\app\build\outputs\apk\debug\app-debug.apk`。
- 便于手机识别的同一安装包副本：`L:\Ambient\dist\Ambient-0.3.0-debug.apk`，校验值与构建输出一致。
- 大小：11,205,874 bytes。
- SHA-256：`9e009b8f107be50b4a4cbad11571ff749187f5ebf1b9f9656fed29b6eb7693de`。

## 设备交互检查

`scripts/test-motion-emulator.ps1` 完成 10 项 PASS：

1. 测速默认关闭，未授权时不生成数值。
2. 拒绝授权后展示明确的不可用状态。
3. 只授予大致位置时明确要求精确定位。
4. 输入 5.144444 m/s 后显示 18.5 km/h，并展示模拟来源与精度。
5. 定位缺少速度字段时显示不可用，不将其当作零。
6. 超过 10 秒未更新时隐藏速度并提示过期。
7. 关闭系统定位会清空速度，重新开启后可恢复。
8. 打开设置或退到后台时，系统中的 Ambient 定位监听注销。
9. 加速度默认去重力，可双向切换；含重力模式接收模拟事件 `0, 0, 9.81` 并显示合成值 9.81 m/s²。
10. 当前 Ambient 进程没有 AndroidRuntime 致命异常。

原页面基础检查脚本另有 8 项 PASS，涵盖光照事件、手动暂停恢复、前后台采样、主题保存、页面导航和运行异常检查。三套主题原始视觉验证保留在 `themes-validation.md`；本次未重复全部主题截图流程。

截图位于 `docs/screenshots/motion`。现有模拟器提供了线性加速度能力，但未返回有效的去重力事件，因此此模式验证的是切换与等待/异常状态，未宣称已验证真机去重力数值；界面没有将其伪造为 0。

## 测试定位工具与兼容问题

现有 Emulator 37 / API 31 的内置 GNSS 事件将日期时间误写入单调时间戳，曾出现 `et=+20741d...` 而开机仅数分钟。App 正确拒绝这类事件，没有为测试放宽生产校验。

因此使用独立包 `com.ambient.locationfixture` 注入带正确事件时间的模拟 GPS 数据。其源码位于 `test-fixtures/location`，工具脚本为 `scripts/inject-test-location.ps1`，构建输出全部位于 `.tools/location-fixture`。该包没有加入 Ambient Gradle 配置，不会出现在交付 APK 中。

脚本只操作核对身份后的 `emulator-5560 / Ambient_API_31`，不会向实体手机安装。`-Reset` 移除模拟 GPS 覆盖，恢复系统提供方。本次结束时已从系统状态确认原 GNSS 提供方恢复，并卸载测试辅助包、关闭专用模拟器。

## 真机验收边界

全部设备检查均使用本项目独立模拟器，测试速度不是华为 Pura 70 Pro 的实测。手机是否提供系统去重力通道、静止偏差、室外定位速度精度、首次定位时间和功耗仍需在 Pura 70 Pro 上验收。

安装可覆盖此前相同签名的 Ambient Debug 版。在探测页开启「定位速度」，选择使用期间的精确定位，并到室外开阔处等待信号。室内、隧道、低速及静止时可能无读数或漂移。

参考：[Android 运动传感器](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion)、[系统定位速度和精度字段](https://developer.android.com/reference/android/location/Location)、[定位权限](https://developer.android.com/develop/sensors-and-location/location/permissions)。
