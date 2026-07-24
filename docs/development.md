# 开发与发布

## 代码结构

```text
backend/app/
  routers/       FastAPI 路由与认证边界
  services/      扫描、解析、封面、存储和流式传输
  admin/         原生 HTML/CSS/JavaScript 管理后台

android/app/src/main/
  java/          Kotlin 业务、数据层、Compose UI 与后台任务
  assets/        g2pW、Piper 模型与文本规则
  g2pw-data/     g2pW 构建输入和许可证
  phrase-pinyin-data/  词典构建输入和许可证
```

详细的数据边界见 [architecture.md](architecture.md)。

## 后端开发

需要 Python 3.11 或更高版本：

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install --upgrade pip
.\.venv\Scripts\python -m pip install -e ".\backend[test]"
Set-Location backend
..\.venv\Scripts\python -m pytest -q
```

测试使用隔离的临时数据库和书库，不读取实际 `library/` 或 `data/`。

启动开发服务器：

```powershell
Set-Location backend
..\.venv\Scripts\python -m uvicorn app.main:app --reload
```

## Android 开发

要求：

- JDK 17；
- Android SDK 36；
- Git LFS；
- Android Studio 或 Gradle Wrapper。

```powershell
git lfs pull
Set-Location android
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintRelease
.\gradlew.bat assembleDebug
```

Linux/macOS 使用：

```bash
cd android
chmod +x gradlew
./gradlew testDebugUnitTest lintRelease assembleDebug
```

设备测试需要已连接的 API 26+ `arm64-v8a` 设备或模拟器：

```bash
./gradlew connectedDebugAndroidTest
```

部分 Windows JDK/Gradle 组合无法从含中文的绝对路径加载测试 class，可临时映射无中文盘符：

```powershell
subst P: 'C:\Users\your-name\Documents\小说docker'
Set-Location P:\android
.\gradlew.bat testDebugUnitTest
```

## 模型与生成资产

`g2pw/model.onnx` 使用 Git LFS。Piper ONNX、词典、FST 和 sherpa-onnx AAR 属于第三方分发内容，修改或
替换时必须同步更新：

- `THIRD_PARTY_NOTICES.md`；
- `docs/open-source-compliance.md`；
- 对应 LICENSE/MODEL_CARDS；
- SHA-256 校验值；
- Git LFS 规则（文件超过 100 MB 时）。

Gradle 的 `GeneratePhrasePinyinAssets` 任务会验证固定的 `pinyin.txt` 哈希，合并本地覆盖和 Piper
lexicon，并生成最长匹配 trie。更新上游词典时必须同步 revision、哈希和相关测试。

## CI

GitHub Actions 对每次 Pull Request 和向 `main`/`master` 的推送执行：

- Python 3.11/3.12 后端测试；
- Android JVM 单元测试；
- Android release lint。

Android Job 会拉取 Git LFS 模型，因此 Fork 首次运行前应确认 LFS 配额。

## 发布检查

1. 后端和 Android 测试全部通过。
2. 更新版本号、CHANGELOG 和 API 兼容性说明。
3. 复核最终 Gradle/Python 依赖和第三方许可证。
4. 使用独立发布密钥构建 Android release，绝不提交 keystore、密码或 `local.properties`。
5. 对 APK、镜像和大模型生成 SHA-256。
6. 创建与源码 commit/tag 对应的 GitHub Release。
7. 发布修改版网络服务时持续满足 AGPL 对应源码提供要求。

当前 `release` build type 仍使用开发签名，以便内部测试覆盖安装。公开发布前必须替换为自己的长期签名
配置；不要直接分发仓库默认配置生成的 release APK。

## 许可证变更

项目原创代码采用 `AGPL-3.0-only`。贡献默认在同一许可证下提交。第三方材料不能由本项目重新授权；
添加依赖、模型、数据或字体前，应确认许可证允许再分发并在第三方声明中记录。
