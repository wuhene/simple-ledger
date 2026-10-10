# 简账应用工程

此分支的应用位于 [`android/`](android/)：原生 Android View 界面、SQLite 本地账本和 APK 构建脚本。已验收的 Web 版源码在 `master-web` 分支的 `project/`，不随原生需求分支合入 `master`。

- Android Studio：打开 `project/android/`，使用 JDK 17 与 Android SDK 35，执行 `gradlew.bat assembleDebug`。
- 本机 Gradle 如遇本地套接字启动故障，可在 PowerShell 执行 `./build-debug.ps1`，使用 JDK 17、Android SDK 35 Build Tools 直接生成已签名 Debug APK。
- 下载文件：[`android/SimpleLedger-debug.apk`](android/SimpleLedger-debug.apk)。这是调试签名包；正式发布应使用私有发布签名重新构建。

本应用不申请网络权限，备份和恢复通过 Android 系统文件选择器完成。具体功能、验证和未决事项见 [`wiki/2026-10-10/`](../wiki/2026-10-10/) 与根目录 [`HANDOFF.md`](../HANDOFF.md)。
