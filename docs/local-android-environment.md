# Android 开发环境

## 标准构建

安装 JDK 21、Android SDK Platform 36 与 Android SDK Build Tools，使用仓库 Gradle Wrapper（8.11.1）。Android Studio 可自动配置 SDK；命令行可通过 `ANDROID_HOME` 或未提交的 `local.properties` 指向 SDK。

```sh
./gradlew :server:testDebugUnitTest :app:assembleDebug
./gradlew :app:assembleRelease
adb devices -l
adb install -r app/build/outputs/apk/release/<实际生成的 APK 文件名>
```

Release 为 arm64，Debug 还支持 x86_64。项目内部版本号按构建日期计算。默认 Release 使用开发签名，正式维护需单独配置安全存储的签名。

## 本地隔离环境

`scripts/android-local.sh` 支持把 SDK、JDK、Gradle 缓存、AVD 和日志集中放在项目 `.local/` 目录，目录不提交 Git。脚本面向已准备好的本地环境，不是 SDK 自动安装器；新开发者可直接使用标准 Android Studio/SDK。

## 真机调试

USB 调试需在手机确认主机授权。覆盖安装后部分系统会关闭无障碍服务，请在手机设置中重新检查。通知读取、悬浮窗和后台运行权限必须按所用功能开启。

设备测试通过 `adb forward tcp:52046 tcp:52045` 连接手机内本地服务；模拟模型测试另外使用 `adb reverse tcp:52047 tcp:52047`。结束后移除这些端口。不要公开调试端口、设备截图、采集数据、日志、API Key 或签名私钥。
