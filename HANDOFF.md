# 简账项目交接文档

更新：2026-10-10 · 当前需求分支：`2026-10-10` · 状态：原生 APK 已构建，待 Android 设备验收

## 接手先读

1. 阅读 [`AGENTS.md`](AGENTS.md) 的分支、Wiki、提交与验收约定。
2. 阅读 [`wiki/2026-10-10/原生Android-PRD与验收.md`](wiki/2026-10-10/原生Android-PRD与验收.md) 和 [`开发进度.md`](wiki/2026-10-10/开发进度.md)。原始业务规则见 `wiki/2026-09-30-init/`，二版视觉与转场见 `wiki/2026-09-30-1/` 和 `wiki/2026-10-09/`。
3. 检查 `git status --short --branch`、远程分支及本地设备。每次开发改动同步更新本文件、AGENTS 待办及当前需求 Wiki，并与代码一起提交推送。

## 分支与工程

- `master-web`：已推送的 Web 二版验收基线，Web 后续需求从它开分支并验收后合回。
- `master`：原生 Android 主线。现在仍是原先 Web 基线；`2026-10-10` 待用户验收通过后合入，届时 Web 源码由本需求分支的删除操作从 `master` 移走。
- `2026-10-10`：本次原生 Android 需求分支，从已更新的 `master` 新建；只保留 `project/android/` 应用工程，历史需求 Wiki 继续保留。
- APK：[`project/android/SimpleLedger-debug.apk`](project/android/SimpleLedger-debug.apk)，Debug 签名测试包，包名 `com.wuhene.simpleledger`，最低 Android 8.0（API 26）。Web 浏览器 IndexedDB 不会自动迁入，可通过 JSON 备份恢复。

## 本次实现

- [`project/android/app/src/main/java/com/wuhene/simpleledger/`](project/android/app/src/main/java/com/wuhene/simpleledger/)：原生 View 页面与转场、Android 13+ 系统返回回调、本地 SQLite 数据层、整数分金额计算、报告分享 Provider。
- [`project/android/build-debug.ps1`](project/android/build-debug.ps1)：使用本机 JDK 17 与 Android SDK 35 直接编译、资源链接、D8、对齐、签名并核验 APK。保留 Gradle 8.13 工程、Wrapper 及 Android Studio 入口。
- [`project/README.md`](project/README.md)：原生分支入口说明。Web 源码已从本需求分支移除，留在 `master-web`。
- `wiki/2026-10-10/`、`AGENTS.md`：原生范围、验收点、测试状态和双主分支规则。

## 已验证

- `project/android/build-debug.ps1` 成功；`apksigner verify --verbose` 显示 v2/v3 签名通过；`aapt dump badging` 确认包名、版本、最低/目标 SDK、启动 Activity 和图标。
- 本地 JVM 金额规则冒烟测试通过：加减表达式、金额上限、非法小数和结果、闰日、跨年月份。
- 最新 APK SHA-256：`FC4F6FE230C1BAAC3C1110FEA867A661D98AFA345C0958D7FFCA1BA550F14D3A`。后续代码或 APK 变更必须重新记录哈希。
- Gradle 构建在本机 JDK 17/21 均于启动 Daemon 前报 `Unable to establish loopback connection`。直接 SDK 构建脚本成功；Android Studio/Gradle 在标准环境仍需复验。
- Android 35 x86_64 模拟器镜像和 AVD 已安装，但启动提示本机缺 Android Emulator hypervisor driver。没有设备安装、实际页面截图、返回手势或 10 万笔性能结论。

## 未决与接手动作

1. 在 Android 8.0+ 真机或有硬件加速的模拟器安装 APK，按当前 Wiki 的设备验收清单测试关键路径，特别是系统返回、记账日期、JSON 往返和报告分享。发现问题仍在 `2026-10-10` 修复并推送。
2. 验证 320dp 小屏、系统大字号、导航栏安全区及 10 年/10 万笔数据的启动和统计耗时。设备测试之前保持状态“待验证”。
3. 用户明确验收本次 Android 需求后，合并 `2026-10-10` 到 `master` 并推送；不要自行合并。Web 新需求只从 `master-web` 开分支。
4. 本机 Debug 签名密钥在被 Git 忽略的 `.tools/debug.keystore`。换机器重建可能无法覆盖安装旧 Debug APK；更新前让用户先导出 JSON 备份。
5. 旧软件导入缺导出样本；云同步、多账本和资产账户明细不在本轮。

## 持续交接规则

每次开发改动都更新本文件，写清分支、改动文件、验证、风险和下一步；同步更新 `AGENTS.md` 待办状态及对应 Wiki，并与代码一起提交推送。
