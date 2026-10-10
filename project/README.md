# 简账应用工程

- [`web/`](web/)：已验收的浏览器版本，用于在电脑上预览功能和交互。
- [`android/`](android/)：原生 Android View + SQLite 工程，包含可下载安装的 [`SimpleLedger-debug.apk`](android/SimpleLedger-debug.apk)。

两套实现位于同一 `master` 主线，按需求分支开发并在验收后合入。Web 浏览器内的 IndexedDB 数据不会自动进入 Android App；可通过 JSON 备份导出和恢复。需求、UI 与验收记录在根目录 [`wiki/`](../wiki/)，当前交接见 [`HANDOFF.md`](../HANDOFF.md)。
