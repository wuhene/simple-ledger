# 简账项目交接文档

更新：2026-10-10 · 当前需求分支：`2026-10-10-1` · 状态：Web 已验收；Android 导航与记账动效调整待设备复验

## 接手先读

1. 阅读 [`AGENTS.md`](AGENTS.md) 的分支、Wiki、提交与验收约定。每次开发改动同步更新本文件、AGENTS 待办和对应需求 Wiki，并与代码一起提交推送。
2. Web 需求阅读 `wiki/2026-09-30-init/`、`wiki/2026-09-30-1/` 与 `wiki/2026-10-09/`；Android 基线阅读 [`wiki/2026-10-10/原生Android-PRD与验收.md`](wiki/2026-10-10/原生Android-PRD与验收.md) 和 [`开发进度.md`](wiki/2026-10-10/开发进度.md)，当前反馈阅读 [`wiki/2026-10-10-1/Android导航与记账动效调整.md`](wiki/2026-10-10-1/Android导航与记账动效调整.md)。
3. 检查 `git status --short --branch`。当前分支 `2026-10-10-1` 从最新 `master` 创建，供用户复验；用户明确验收通过后才合并 `master`。新需求从更新后的 `master` 按日期开分支。

## 当前结构

- [`project/web/`](project/web/)：已由用户验收的固定手机画布 Web 二版。浏览器账本保存在当前地址的 IndexedDB。进入此目录运行 `python -m http.server 4173 --bind 127.0.0.1`，打开 `http://127.0.0.1:4173/`。
- [`project/android/`](project/android/)：原生 Android View + SQLite 工程；[`SimpleLedger-debug.apk`](project/android/SimpleLedger-debug.apk) 是 `0.1.1` 调试签名包，最低 Android 8.0。Web 数据不会自动进入 App，可用 JSON 备份迁移。
- `master` 同时保存两套代码和全部 Wiki。按用户最新要求，`master-web` 已删除；已有日期需求分支保留用于历史与后续验收反馈。
- Android 代码由 `2026-10-10` 需求分支整合到 `master` 是用户要求的目录/主线调整，**不代表 Android 已通过真机验收**。Android 反馈仍可在该需求分支修复，合入前核对主线最新状态。

## 本次 Android 反馈调整（`2026-10-10-1`）

- 用户已试用上一版 Android APK，要求底部入口放大、中央“记一笔”更突出、四主 Tab 顶部说明/标题移除、Tab 无过渡、保存后卡片下滑且底层刷新不闪。本次只修改 Android，Web 不变。
- `project/android/app/src/main/java/com/wuhene/simpleledger/MainActivity.java`：导航图标与文字分别布局；主页面内容上移；取消整页淡入；保存时卡片覆盖下先更新底层页面，再下滑移除卡片。
- `project/android/app/build.gradle` 和 `project/android/build-debug.ps1`：版本升到 `versionCode=2`、`versionName=0.1.1`。新需求、实现和设备复验清单在 `wiki/2026-10-10-1/`；`AGENTS.md` 同步待办状态。

## 先前结构调整

- 从原 Web 基线恢复浏览器源码，并放入 `project/web/`；`project/android/` 工程和 APK 不变。
- 更新 `project/README.md`、`project/web/README.md`、`AGENTS.md` 和 `wiki/2026-10-10/` 中的平台路径和单一主线规则。
- 保留“一需求一分支、验收后合并”的日常工作方式；分支不再区分 Web 和 Android。

## 验证与限制

- `project/web/` 移动后执行 `npm test`，5 项通过；`node --check app.js` 通过。此前 Web 二版浏览器交互和小屏验收记录见 `wiki/2026-10-09/进度与测试.md`。
- 本次 Android `build-debug.ps1` 编译成功，`apksigner` v2/v3 校验通过；`aapt dump badging` 确认包名、版本 `2 / 0.1.1`、最低 API 26。APK SHA-256：`6CDE5C7E44526FDBE160B0D7AADEEC8B27D9916F27430FD3E7D9D2D668464705`。
- 本机 Gradle 启动曾遇到 Java 本地套接字错误，直接 SDK 构建脚本可用；Android 35 模拟器缺硬件加速驱动。本次 `adb devices` 无连接设备。用户已经安装试用上一版并提出反馈；当前版本的布局、动效、返回、覆盖安装与 10 万笔性能尚待设备复验。
- Debug 签名密钥仅在被 Git 忽略的 `.tools/debug.keystore`；换机器重建的 Debug 包可能不能覆盖安装。更新前先导出 JSON 备份。

## 下一步

1. 在 Android 8.0+ 设备覆盖安装本分支 APK，按 `wiki/2026-10-10-1/Android导航与记账动效调整.md` 复验本次四项反馈，并按 `wiki/2026-10-10/开发进度.md` 验证记账、返回手势、日期、备份恢复、报告分享、小屏与性能；记录设备、结果和问题。
2. 验收反馈继续在 `2026-10-10-1` 修复并更新 Wiki / AGENTS / HANDOFF。用户明确通过后合并并推送 `master`；新需求在各自平台目录开发，从最新 `master` 开日期分支。
3. 旧软件数据导入仍缺实际导出样本；云同步、多账本与资产账户明细不在当前范围。

## 持续交接规则

每次开发改动都更新本文件，写清分支、改动文件、验证、未决问题和接手动作；同步更新 `AGENTS.md` 待办状态及对应 Wiki，并与代码一起提交推送。
