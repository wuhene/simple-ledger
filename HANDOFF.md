# 简账项目交接文档

更新：2026-10-10 · 主线：`master` · 状态：Web 已验收；Android APK 已构建，待设备验收

## 接手先读

1. 阅读 [`AGENTS.md`](AGENTS.md) 的分支、Wiki、提交与验收约定。每次开发改动同步更新本文件、AGENTS 待办和对应需求 Wiki，并与代码一起提交推送。
2. Web 需求阅读 `wiki/2026-09-30-init/`、`wiki/2026-09-30-1/` 与 `wiki/2026-10-09/`；Android 需求阅读 [`wiki/2026-10-10/原生Android-PRD与验收.md`](wiki/2026-10-10/原生Android-PRD与验收.md) 和 [`开发进度.md`](wiki/2026-10-10/开发进度.md)。
3. 检查 `git status --short --branch` 并更新 `master`。Web、Android 新需求都从 `master` 按日期开需求分支；不再用平台专属主分支。

## 当前结构

- [`project/web/`](project/web/)：已由用户验收的固定手机画布 Web 二版。浏览器账本保存在当前地址的 IndexedDB。进入此目录运行 `python -m http.server 4173 --bind 127.0.0.1`，打开 `http://127.0.0.1:4173/`。
- [`project/android/`](project/android/)：原生 Android View + SQLite 工程；[`SimpleLedger-debug.apk`](project/android/SimpleLedger-debug.apk) 是可下载的调试签名包，最低 Android 8.0。Web 数据不会自动进入 App，可用 JSON 备份迁移。
- `master` 同时保存两套代码和全部 Wiki。按用户最新要求，`master-web` 已删除；已有日期需求分支保留用于历史与后续验收反馈。
- Android 代码由 `2026-10-10` 需求分支整合到 `master` 是用户要求的目录/主线调整，**不代表 Android 已通过真机验收**。Android 反馈仍可在该需求分支修复，合入前核对主线最新状态。

## 本次结构调整

- 从原 Web 基线恢复浏览器源码，并放入 `project/web/`；`project/android/` 工程和 APK 不变。
- 更新 `project/README.md`、`project/web/README.md`、`AGENTS.md` 和 `wiki/2026-10-10/` 中的平台路径和单一主线规则。
- 保留“一需求一分支、验收后合并”的日常工作方式；分支不再区分 Web 和 Android。

## 验证与限制

- `project/web/` 移动后执行 `npm test`，5 项通过；`node --check app.js` 通过。此前 Web 二版浏览器交互和小屏验收记录见 `wiki/2026-10-09/进度与测试.md`。
- Android APK 曾通过 `build-debug.ps1` 编译、`apksigner` v2/v3 校验和 `aapt dump badging` 包信息检查。APK SHA-256：`FC4F6FE230C1BAAC3C1110FEA867A661D98AFA345C0958D7FFCA1BA550F14D3A`；移动 Web 文件不改变 APK。
- 本机 Gradle 启动遇到 Java 本地套接字错误，直接 SDK 构建脚本可用；Android 35 模拟器缺硬件加速驱动。尚无设备安装、返回手势、屏幕截图或 10 万笔原生性能结论。
- Debug 签名密钥仅在被 Git 忽略的 `.tools/debug.keystore`；换机器重建的 Debug 包可能不能覆盖安装。更新前先导出 JSON 备份。

## 下一步

1. 在 Android 8.0+ 设备安装 APK，按 `wiki/2026-10-10/开发进度.md` 验证记账、返回手势、日期、备份恢复、报告分享、小屏与性能。把反馈和结果记入对应需求 Wiki。
2. Web 新需求在 `project/web/` 开发，Android 新需求在 `project/android/` 开发；都从最新 `master` 开日期分支。
3. 旧软件数据导入仍缺实际导出样本；云同步、多账本与资产账户明细不在当前范围。

## 持续交接规则

每次开发改动都更新本文件，写清分支、改动文件、验证、未决问题和接手动作；同步更新 `AGENTS.md` 待办状态及对应 Wiki，并与代码一起提交推送。
