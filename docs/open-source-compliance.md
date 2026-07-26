# 开源依赖与第三方材料清单

审计日期：2026-07-24

本文根据仓库中的 `backend/pyproject.toml`、Android Gradle 配置、Gradle
`releaseRuntimeClasspath`、Dockerfile、随 APK 打包的 AAR/模型/词典文件以及上游项目元数据整理。
它是发布清单，不构成法律意见。依赖升级、基础镜像更新或模型替换后应重新审计。

清单覆盖应用直接依赖、发布产物的传递依赖项目族、测试/构建直接依赖、随仓库分发的二进制与数据，
以及基础运行环境。Gradle 会为同一上游项目解析大量平台变体和历史约束版本，因此按上游项目族归并，
不把每个 Maven artifact 重复当成一个项目。JDK、Android SDK 和 Debian 自身的内部逐包依赖应以最终
构建产物的 SBOM 为准。

## 发布结论

项目原创代码统一采用根目录 `LICENSE` 中的 `AGPL-3.0-only`。第三方库、模型和数据继续使用各自的
许可证，不能被项目根许可证重新授权。公开发布时仍需注意以下事项：

1. 后端直接依赖 PyMuPDF。PyMuPDF 采用
   `AGPL-3.0-only OR Artifex-Commercial-License` 双授权。未购买商业许可时，分发包含该依赖的
   后端，或通过网络提供修改后的版本，需要满足 AGPL 的对应源码提供等义务。项目采用 AGPL 与其
   保持一致。
2. 原始 `rhasspy/piper-voices` 模型集合在仓库级元数据中声明 MIT，小雅与超文权重据此按 MIT
   记录。模型卡中的 CC0/非商业条款描述的是训练数据集，不替代权重的仓库级 MIT；仍应完整保留
   模型卡。小雅的 BZNSYP/Data Baker 数据来源限制为非商业，商业化前必须重新核查。
3. `python:3.12-slim` 是可变标签，Python 依赖也只有版本范围、没有锁文件。不同构建日期产生的
   Debian 包和 Python 传递依赖可能不同，因而无法为未来镜像给出固定的逐包清单。

## 后端直接依赖

版本范围来自 `backend/pyproject.toml`。括号中的版本是本次审计时本地环境实际解析到的版本，仅用于
核对；由于没有锁文件，Docker 重新构建时可能不同。

| 项目 | 声明版本 / 审计版本 | 用途 | SPDX 许可证 | 上游 |
| --- | --- | --- | --- | --- |
| FastAPI | `>=0.134,<0.135` / 0.134.0 | Web API | MIT | https://github.com/fastapi/fastapi |
| Uvicorn | `>=0.35,<0.36` / 0.35.0 | ASGI 服务 | BSD-3-Clause | https://github.com/encode/uvicorn |
| SQLAlchemy | `>=2.0.51,<2.1` / 2.0.51 | ORM/SQLite | MIT | https://github.com/sqlalchemy/sqlalchemy |
| pydantic-settings | `>=2.10,<3` / 2.14.2 | 配置解析 | MIT | https://github.com/pydantic/pydantic-settings |
| pypdf | `>=6.13.3,<7` / 6.14.2 | PDF 元数据与目录 | BSD-3-Clause | https://github.com/py-pdf/pypdf |
| PyMuPDF / MuPDF | `>=1.26,<2` / 1.28.0 | PDF/EPUB 封面渲染 | AGPL-3.0-only OR 商业许可 | https://github.com/pymupdf/PyMuPDF |
| defusedxml | `>=0.7.1,<1` / 0.7.1 | 安全解析 EPUB XML | PSF-2.0 | https://github.com/tiran/defusedxml |

### 后端运行时传递依赖

下表覆盖上述直接依赖和 `uvicorn[standard]` 会带入的项目。Linux 容器通常还会安装 uvloop；本次
Windows 审计环境不会安装它。

| 项目/包 | 审计版本或范围 | SPDX 许可证 |
| --- | --- | --- |
| Starlette | 1.3.1 | BSD-3-Clause |
| Pydantic / pydantic-core | 2.13.4 / 2.46.4 | MIT |
| annotated-doc / annotated-types | 0.0.4 / 0.7.0 | MIT |
| typing-inspection | 0.4.2 | MIT |
| typing_extensions | 4.16.0 | PSF-2.0 |
| AnyIO | 4.14.2 | MIT |
| idna | 3.18 | BSD-3-Clause |
| Click | 8.4.2 | BSD-3-Clause |
| Colorama | 0.4.6，仅 Windows | BSD-3-Clause |
| h11 | 0.16.0 | MIT |
| httptools | 0.8.0 | MIT |
| PyYAML | 6.0.3 | MIT |
| watchfiles | 1.2.0 | MIT |
| websockets | 16.1.1 | BSD-3-Clause |
| uvloop | 由 Linux 构建解析 | MIT |
| greenlet | 3.5.3 | MIT AND PSF-2.0 |
| python-dotenv | 1.2.2 | BSD-3-Clause |

### 后端测试与构建依赖

这些包不应进入正式运行镜像，但参与开发、测试或构建。

| 项目 | 声明版本 | SPDX 许可证 | 主要传递依赖及许可证 |
| --- | --- | --- | --- |
| HTTPX | `>=0.28,<0.29` | BSD-3-Clause | httpcore (BSD-3-Clause)、certifi (MPL-2.0)、h11 (MIT)、AnyIO、idna |
| pytest | `>=9.0.3,<10` | MIT | iniconfig (MIT)、packaging (Apache-2.0 OR BSD-2-Clause)、pluggy (MIT)、Pygments (BSD-2-Clause)、Colorama |
| pytest-cov | `>=6.2,<7` | MIT | coverage.py (Apache-2.0)、pluggy、pytest |
| Hatchling | `>=1.27` | MIT | packaging、pathspec (MIT)、pluggy、trove-classifiers (Apache-2.0)；具体版本由隔离构建环境解析 |

## Android 直接依赖

AndroidX 与 Jetpack Compose 均由 Android Open Source Project/Google 维护，表中列出的相关模块采用
Apache-2.0。Compose BOM 解析出的版本记录为本次 Gradle 审计结果。

| 项目/坐标 | 版本 | 用途 | SPDX 许可证 |
| --- | --- | --- | --- |
| sherpa-onnx (`libs/sherpa-onnx-1.13.4.aar`) | 1.13.4 | 端侧 TTS | Apache-2.0 |
| AndroidX Core KTX | 1.18.0 | Android 基础扩展 | Apache-2.0 |
| AndroidX Activity Compose | 1.13.0 | Compose Activity | Apache-2.0 |
| AndroidX Lifecycle Runtime/ViewModel Compose | 2.9.4 | 生命周期和状态 | Apache-2.0 |
| AndroidX Navigation Compose | 2.9.8 | 页面导航 | Apache-2.0 |
| Jetpack Compose BOM | 2026.06.00 | Compose 版本约束 | Apache-2.0 |
| Compose UI/Foundation | 1.11.3 | UI 与布局 | Apache-2.0 |
| Compose Material Icons Extended | 1.7.8 | 图标 | Apache-2.0 |
| Compose Material 3 | 1.4.0 | Material 组件 | Apache-2.0 |
| AndroidX Room Runtime/KTX/Compiler | 2.8.4 | 本地数据库 | Apache-2.0 |
| AndroidX WorkManager KTX | 2.11.2 | 后台任务 | Apache-2.0 |
| AndroidX DataStore Preferences | 1.1.7 | 设置存储 | Apache-2.0 |
| AndroidX Media3 ExoPlayer | 1.8.0 | TTS 音频播放 | Apache-2.0 |
| Retrofit / converter-gson | 3.0.0 | HTTP API | Apache-2.0 |
| OkHttp logging-interceptor | 4.12.0 | 开发期 HTTP 日志拦截器 | Apache-2.0 |
| Coil Compose / network-okhttp | 3.3.0 | 封面加载 | Apache-2.0 |

项目链接：

- AndroidX/Compose：https://github.com/androidx/androidx
- sherpa-onnx：https://github.com/k2-fsa/sherpa-onnx
- Retrofit：https://github.com/square/retrofit
- OkHttp/Okio：https://github.com/square/okhttp 和 https://github.com/square/okio
- Coil：https://github.com/coil-kt/coil

### Android 运行时传递依赖

Gradle 会把一个项目拆成很多 Maven artifact。为避免把同一许可证项目的几十个模块误写成几十个独立
项目，下面按上游项目族归并，同时列出实际出现的模块族。

| 上游项目族 | 解析到的模块族 | SPDX 许可证 |
| --- | --- | --- |
| AndroidX | activity、annotation、appcompat、arch-core、autofill、collection、concurrent、core、customview、datastore、documentfile、dynamicanimation、emoji2、exifinterface、graphics、interpolator、legacy、lifecycle、loader、localbroadcastmanager、media3、navigation、navigationevent、print、profileinstaller、room、savedstate、sqlite、startup、tracing、transition、vectordrawable、versionedparcelable、window、work | Apache-2.0 |
| Jetpack Compose / JetBrains Compose compatibility artifacts | animation、annotation、collection、foundation、lifecycle、material、material3、runtime、savedstate、ui 及 Android 变体 | Apache-2.0 |
| Kotlin | stdlib、Android extensions/parcelize runtime、coroutines、serialization | Apache-2.0 |
| OkHttp / Okio | okhttp、okio | Apache-2.0 |
| Gson | gson | Apache-2.0 |
| Google Guava | guava、failureaccess、listenablefuture | Apache-2.0 |
| Google Accompanist | drawablepainter | Apache-2.0 |
| Protocol Buffers | DataStore 使用的 protobuf 运行时/生成代码 | BSD-3-Clause |
| Error Prone annotations | error_prone_annotations | Apache-2.0 |
| JetBrains annotations | annotations | Apache-2.0 |
| JSpecify | jspecify | Apache-2.0 |

### Android 测试依赖

| 项目 | 版本 | SPDX 许可证 |
| --- | --- | --- |
| JUnit 4 | 4.13.2 | EPL-1.0 |
| Hamcrest | 由 JUnit 传递引入 | BSD-3-Clause |
| kotlinx-coroutines-test | 1.10.2 | Apache-2.0 |
| AndroidX Test Ext JUnit | 1.3.0 | Apache-2.0 |
| Compose UI Test / Test Manifest / Tooling | 由 Compose BOM 解析 | Apache-2.0 |

## 随仓库和 APK 分发的模型与数据

这部分不是普通的软件包依赖。模型权重、训练数据、词典和代码可能分别拥有不同权利；源码许可证不会
自动覆盖它们。

| 材料 | 仓库路径 | 来源与版本 | 许可证/使用条款 | 结论 |
| --- | --- | --- | --- | --- |
| Matcha 声学模型、词典、eSpeak 与 FST | `android/app/src/main/assets/tts/matcha_zh_en/` | [ModelScope 原模型](https://modelscope.cn/models/dengcunqin/matcha_tts_zh_en_20251010)；[sherpa-onnx tts-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models) 归档 SHA-256 `271b804af570400d3bcdcb53bf6e53cc9f75180ee763b9f13eb5eaf2b0d086ef` | 模型元数据标注 Apache-2.0；训练数据来源信息不完整 | 已附 Apache-2.0 文本、上游 README、来源和校验值；商业发布前重新核查训练数据来源 |
| Vocos 16 kHz universal ONNX | `android/app/src/main/assets/tts/matcha_zh_en/vocos-16khz-univ.onnx` | [sherpa-onnx vocoder-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/vocoder-models)；上游 [gemelo-ai/vocos](https://github.com/gemelo-ai/vocos) | MIT | 已附 Vocos MIT 文本、来源和校验值 |
| phrase-pinyin-data 派生短语词典 | `android/app/src/main/assets/tts/matcha_zh_en/novel-phrase-lexicon.txt` | [mozillazg/phrase-pinyin-data](https://github.com/mozillazg/phrase-pinyin-data) 0.19.0，提交 `cee0ed6e6e4898580cafd2bd5e3723e20b214aa0` | MIT | 与项目小说多音字覆盖表合并并过滤为上下文相关短语；已附生成脚本、来源校验值和 MIT 文本 |

文件校验值：

| 文件 | SHA-256 |
| --- | --- |
| `sherpa-onnx-1.13.4.aar` | `daf532d343e741df96c15e25f6da90420fc1b54126ee23dc8d49dba617033552` |
| `matcha_zh_en/model-steps-3.onnx` | `524286bf6cf11be74329ae1c682ac69e34d6860c2ea9fd1290319d561540b16a` |
| `matcha_zh_en/vocos-16khz-univ.onnx` | `b599142a1fb8ff03de3e84ac35ff537c619e56f4267a6fe894851a42844acf9e` |
| `matcha_zh_en/novel-phrase-lexicon.txt` | `210b7793ab3251103a13f7af4613bf00f38fe0528087ff0ba49cec9af72a8c63` |

## 构建、平台与容器工具

这些项目参与构建或构成运行环境，但通常不作为应用代码的一部分再分发。

| 项目 | 版本 | SPDX 许可证/说明 |
| --- | --- | --- |
| Gradle Wrapper / Gradle | 8.13 | Apache-2.0 |
| Android Gradle Plugin | 8.13.2 | Apache-2.0 |
| Kotlin Gradle/Compose/Kapt plugins | 2.2.21 | Apache-2.0 |
| AndroidX Room Gradle Plugin | 2.8.4 | Apache-2.0 |
| Android SDK / AOSP API | compile/target 36 | 主要为 Apache-2.0；SDK 中个别工具另有声明 |
| Python | Docker 默认 3.12 | PSF-2.0 |
| Official Python Docker image | `python:3.12-slim`，未固定 digest | 镜像组合内容采用多种许可证，需按实际镜像 SBOM/`/usr/share/doc/*/copyright` 审计 |
| Debian slim 基础系统 | 由当时的 Python 镜像决定 | 多种自由软件许可证，不能用单一许可证概括 |
| SQLite | Python 标准库间接使用 | Public Domain |

管理后台由仓库内原生 HTML/CSS/JavaScript 构成，没有 npm、外部 JavaScript/CSS CDN 或随仓库分发的
Web 字体依赖。CSS 里列出的系统字体只是运行设备上的候选字体，并未打包。

## 发布前检查表

- [x] 根目录使用 `AGPL-3.0-only`，与 PyMuPDF 的开源授权路径保持一致。
- [x] 记录 Matcha、Vocos 的来源、版本、校验值和随包许可证文本。
- [ ] 商业化前重新核查 Matcha 模型训练数据的来源与使用条款。
- [ ] 保留根目录 `THIRD_PARTY_NOTICES.md` 和 `assets/tts/matcha_zh_en/` 下的许可证文本。
- [ ] 在 App 的“关于/开源许可”页面或随 APK 文档中提供第三方声明；当前 App 尚未实现该页面。
- [ ] 检查 `packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"` 是否移除了依赖要求保留的
  许可证/NOTICE；不要只为了去重而丢弃全部声明。
- [ ] 为 Python 依赖生成锁文件；为 Docker 基础镜像固定 digest，并按最终镜像生成 SBOM。
- [ ] 不将 `.env`、数据库、测试截图、构建目录、历史 APK 或开发签名密钥提交到源码仓库。
- [x] Matcha 声学模型和 Vocos ONNX 使用 Git LFS；归档内全部 TTS 资产约 142 MiB。
- [ ] 发布 APK/镜像前，以最终产物重新生成依赖清单，并核对 Apache NOTICE、MPL、EPL、AGPL 等条款。

## 复核命令

```powershell
# 后端声明依赖
Get-Content backend/pyproject.toml

# 当前 Python 环境；这不是锁文件
.\.venv\Scripts\python -m pip list

# Android 发布运行时与测试依赖
Set-Location android
.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath
.\gradlew.bat :app:dependencies --configuration testDebugUnitTestRuntimeClasspath
.\gradlew.bat buildEnvironment
```

推荐在 CI 中对最终镜像/APK 运行 SBOM 和许可证扫描（例如 Syft、ScanCode 或 ORT），但扫描结果仍需
人工核对模型、数据集和字体等非传统软件资产。
