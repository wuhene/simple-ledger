# 简账项目交接文档

更新：2026-11-24 · 当前需求分支：`2026-11-24` · 状态：AI 助手 Web 调试版待验收；Android 基线待设备验收

## 接手先读

1. 阅读 [`AGENTS.md`](AGENTS.md) 的分支、Wiki、提交与验收约定。每次开发改动同步更新本文件、AGENTS 待办和对应需求 Wiki，并与代码一起提交推送。
2. Web 需求阅读 `wiki/2026-09-30-init/`、`wiki/2026-09-30-1/` 与 `wiki/2026-10-09/`；Android 需求阅读 [`wiki/2026-10-10/原生Android-PRD与验收.md`](wiki/2026-10-10/原生Android-PRD与验收.md) 和 [`开发进度.md`](wiki/2026-10-10/开发进度.md)。
3. 检查 `git status --short --branch` 并更新 `master`。Web、Android 新需求都从 `master` 按日期开需求分支；不再用平台专属主分支。
4. 当前 AI 需求阅读 [PRD](wiki/2026-11-24/AI助手-PRD.md)、[交互与技术方案](wiki/2026-11-24/AI助手-交互与技术方案.md) 和 [进度与验收](wiki/2026-11-24/进度与验收.md)。

## 当前结构

- [`project/web/`](project/web/)：固定手机画布 Web 二版与 AI 调试版。浏览器账本保存在当前地址的 IndexedDB。进入此目录运行 `npm start`，打开 `http://127.0.0.1:4173/`。
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
4. 在 `2026-11-24` 上配置兼容模型并测试连接，再验收记账、统计卡留存及新会话。设置路径是「我的 → 设置与数据 → AI 助手设置」。Web 验收后再确定原生实现与 Key 保存方式。

## AI 助手交接（`2026-11-24`）

- `project/web/app.js` 增加新会话。`assistant.js` 将入账回执和统计结果归入对应的聊天消息，保留旧统计和各卡的展开状态。原有 IndexedDB 流水读写和统计口径继续复用。
- 已移除本地规则识别。`assistant-model.js` 校验模型返回，`assistant-bridge.js` 负责本机中转并限制上下文字段。完整 [系统提示词](wiki/2026-11-24/AI助手-系统提示词.md) 随运行代码更新。
- 模型设置与 Key 只存在当前页面内存。新会话保留配置；刷新后需重新输入配置。真实模型的识别效果仍待用户复验。
- `storage.js` 升级 IndexedDB，`assistant.js` 保存并恢复聊天、上下文、卡片和未确认草稿，`app.js` 在聊天顶部提供「历史」。清空全部数据同时清除历史；原有账本备份格式不变且不包含聊天。此前已消失的内存会话没有可迁移的记录。
- Chrome 通过模拟模型验证了每条消息调用模型、卡片顺序、旧统计保留、撤销、跨轮追问及新会话清理上下文；详见 [进度与验收](wiki/2026-11-24/进度与验收.md)。
- `assistant.js` 与 `assistant-model.js` 增加同轮文字加卡片、纯文字建议及支出评价。按最新反馈，模型设置去掉两个勾选项。保存连接即启用识别；评价在数据充足且金额可见时会使用必要的汇总数字，真实模型回复待复验。
- Web 界面优化修改 `project/web/app.js`、`assistant.js`、`styles.css`。明细和统计压缩首屏，记账突出金额和日期，保存下滑时显示更新后的页面；AI 输入区固定在底部并保留阅读位置。设计、验证和待验收项见 [Web 界面优化](wiki/2026-11-24/Web界面优化.md)。
- `category-icons.js` 使用 Lucide 默认图标，保存和备份沿用原 `iconKey`。`model.js`、`storage.js` 在读旧账本或恢复备份时修复隐藏或缺失的餐饮，页面直接置顶展示。
- `app.js`、`styles.css` 恢复底栏中央「记一笔」，将 AI 移到明细与统计右下角并去掉首页标题行。新建先选分类再显示底部输入区；编辑沿用原分类和表单值。
- Android 交互反馈仍在 `2026-10-10-1` 分支待设备验收。该分支的交接和鲨鱼记账迁移讨论仍以该分支的 `HANDOFF.md` 为准。

## 持续交接规则

每次开发改动都更新本文件，写清分支、改动文件、验证、未决问题和接手动作；同步更新 `AGENTS.md` 待办状态及对应 Wiki，并与代码一起提交推送。
