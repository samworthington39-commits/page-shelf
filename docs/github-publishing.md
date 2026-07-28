# 发布到 GitHub

本仓库已经包含项目许可证、第三方声明、Git LFS、CI、Dependabot、Issue/PR 模板和安全策略。
首次公开前，不要让 GitHub 自动生成 README、`.gitignore` 或 LICENSE，以免与本地文件冲突。

## 1. 发布前检查

在仓库根目录执行：

```powershell
git status --short
git check-attr -a -- android/app/src/main/assets/tts/matcha_zh_en/model-steps-3.onnx
git check-attr -a -- android/app/src/main/assets/tts/matcha_zh_en/vocos-16khz-univ.onnx
git lfs install
```

两个 ONNX 文件都应显示 `filter: lfs`。确认待提交内容中没有 `.env`、`data/`、`library/`、数据库、书籍、
APK、签名密钥、`local.properties`、私人路径或真实服务器地址。

运行验证：

```powershell
Set-Location backend
..\.venv\Scripts\python.exe -m pytest -q

Set-Location ..\android
.\gradlew.bat testDebugUnitTest lintRelease
Set-Location ..
```

Windows 下如果项目路径包含中文并导致测试类加载失败，按
[开发指南](development.md#android-开发)中的说明临时映射无中文盘符后重试。

## 2. 创建首次提交

```powershell
git add .
git status --short
git lfs status
git lfs ls-files
git commit -m "Initial open-source release"
git branch -M main
```

`git lfs ls-files` 应包含两个 Matcha ONNX 文件。提交前再次检查 `git status`
列出的文件；不要使用 `git add -f` 强行加入被忽略的本地数据。

## 3. 创建并推送仓库

在 GitHub 新建一个空的 Public repository，然后使用页面显示的仓库 URL：

```powershell
git remote add origin https://github.com/YOUR_NAME/YOUR_REPO.git
git push -u origin main
```

也可以在已登录 GitHub CLI 的环境中创建：

```powershell
gh repo create YOUR_REPO --public --source . --remote origin --push
```

大模型上传依赖 Git LFS。若推送提示 LFS 配额不足，需要为账户增加 LFS 配额，或把模型改为由
GitHub Release/外部模型仓库按固定校验值下载；不要把模型改为普通 Git 文件上传。

## 4. GitHub 仓库设置

建议在仓库设置中完成：

1. 确认默认分支为 `main`，并为其启用 Pull Request 和 CI 必须通过的保护规则；
2. 在 Security 设置中启用 Private vulnerability reporting；
3. 为仓库添加 topics，例如 `android`、`fastapi`、`ebook-reader`、`self-hosted`、`tts`；
4. 确认 Actions 已运行 Backend Python 3.11/3.12 和 Android tests and lint；
5. 检查 GitHub 的 License 页面识别为 `AGPL-3.0`。

## 5. 上传后验证

在另一个空目录完整克隆一次，验证 LFS 和构建入口：

```powershell
git lfs install
git clone https://github.com/YOUR_NAME/YOUR_REPO.git
Set-Location YOUR_REPO
git lfs pull
git lfs ls-files
Get-Item android/app/src/main/assets/tts/matcha_zh_en/model-steps-3.onnx | Select-Object Length
Get-Item android/app/src/main/assets/tts/matcha_zh_en/vocos-16khz-univ.onnx | Select-Object Length
```

两个 ONNX 文件的 SHA-256 应分别为
`524286bf6cf11be74329ae1c682ac69e34d6860c2ea9fd1290319d561540b16a` 和
`b599142a1fb8ff03de3e84ac35ff537c619e56f4267a6fe894851a42844acf9e`。只部署后端的用户可跳过模型下载：

```powershell
$env:GIT_LFS_SKIP_SMUDGE = "1"
git clone https://github.com/YOUR_NAME/YOUR_REPO.git
Remove-Item Env:GIT_LFS_SKIP_SMUDGE
```

## 6. 创建版本发布

首次源码公开可先保持 `Unreleased`。准备正式版本时，更新 `CHANGELOG.md`、Android `versionCode` /
`versionName` 和后端版本，再创建与源码提交对应的 tag 和 GitHub Release。公开发布 APK 前必须换成自己的
长期签名密钥；不要分发仓库默认开发签名生成的 release APK。

