# 贡献指南

感谢参与页架。提交代码即表示你有权贡献该内容，并同意按项目的
`AGPL-3.0-only` 许可证发布贡献。

## 开始之前

- 使用 Issue 搜索确认问题或需求尚未重复提交。
- 安全漏洞不要创建公开 Issue，请按 `.github/SECURITY.md` 报告。
- 涉及 API、数据库 schema、模型文件或许可证的修改，先在 Issue 中说明兼容性与迁移方案。
- 不提交书籍、数据库、日志、截图中的私人内容、`.env`、签名密钥或其他凭证。

## 本地环境

```bash
git lfs install
git clone <your-fork-url>
cd <repository-directory>
git lfs pull
```

后端和 Android 环境配置见 `docs/development.md`。

## 分支和提交

- 从最新默认分支创建短生命周期分支。
- 每个 Pull Request 聚焦一个问题，不混入无关重构或格式化。
- 提交信息使用简短祈使句，例如 `Fix EPUB chapter ordering`。
- 修改行为时同步测试和文档。
- 不修改或删除现有第三方版权与许可证声明。

## Pull Request 检查表

- [ ] 后端测试通过：`pytest -q`。
- [ ] Android 单元测试通过：`testDebugUnitTest`。
- [ ] Android lint 通过：`lintRelease`。
- [ ] 用户可见行为、环境变量和部署步骤已更新文档。
- [ ] 新依赖或模型已记录上游、版本、许可证和校验值。
- [ ] 没有提交凭证、个人路径、数据库、APK 或非必要二进制。

维护者可能要求拆分过大的 PR、补充复现用例或说明许可证来源。是否合并取决于兼容性、测试、维护成本
和项目范围。
