# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 的基本格式。正式发布后在这里
记录用户可见变化；尚未发布的修改放在 `Unreleased`。

## Unreleased

### Changed

- Android 离线朗读测试分支改用 `matcha-icefall-zh-en` 与 Vocos，支持中英文混合文本。
- Debug 包使用独立应用 ID 和“页架 Matcha 测试”名称，可与正式版并存。
- 移除 Piper 双音色、g2pW 模型及其词典生成链路；Matcha 当前只提供一个女声音色。

## 1.0.9 - 2026-07-25

### Fixed

- 修复 Docker 只读挂载的书库被错误要求写权限，导致后台无法登记存储位置的问题。

## 1.0.8 - 2026-07-24

### Added

- AGPL-3.0-only 项目许可证与完整第三方许可证清单。
- GitHub Issue/PR 模板、Dependabot、CI、Git LFS 和贡献文档。
- 首次登录强制修改默认管理密码，并提供可持久化的哈希凭据和后台改密入口。
- GitHub Release 自动发布 Android APK、Docker 部署包和 SHA-256 校验文件。
- GHCR 提供 `linux/amd64` 与 `linux/arm64` 后端镜像。
- README 展示管理界面，并明确项目不包含或分发任何图书资源。
- TXT/EPUB/PDF 阅读、离线下载和跨设备阅读进度同步。
- 网页管理后台、书架扫描、单书章节策略和封面管理。
- sherpa-onnx、Piper 与 g2pW 完全离线中文朗读。

### Changed

- 公开仓库使用通用 Docker 挂载和 Android 默认服务器地址，不包含开发者本地路径或 IP。
- Docker Compose 项目和容器统一命名为 `page-shelf`，README 补充管理地址、默认凭据、目录与 App 连接信息。
