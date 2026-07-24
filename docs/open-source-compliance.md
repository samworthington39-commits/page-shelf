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
| ONNX Runtime Android | 1.27.0 | g2pW/语音模型推理 | MIT |
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
- ONNX Runtime：https://github.com/microsoft/onnxruntime
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
| chaowen Piper ONNX | `android/app/src/main/assets/tts/piper_zh/voices/chaowen.onnx` | 原始集合 [rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices)，int8 镜像 revision `19375e11252db7afffa04181e114adab3db4219b` | 权重 MIT；模型卡称训练数据集为 CC0-1.0，并注明由小雅微调 | 保留 MIT 文本、模型卡、来源和转换说明 |
| xiao_ya Piper ONNX | `android/app/src/main/assets/tts/piper_zh/voices/xiao_ya.onnx` | 原始集合 [rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices)，int8 镜像 revision `c8fb5de2a0fc365031013688c39238402c88e3ab` | 权重 MIT；模型卡称 BZNSYP/Data Baker 训练数据仅限非商业使用 | 非商业发布保留权重与数据来源声明；商业化前重新核查 |
| Piper 中文 lexicon/tokens/FST | `phrase-pinyin-data/base_lexicon.txt`、`assets/tts/piper_zh/common/*` | 与上述 MIT Piper 中文模型包一起取得 | MIT | 保留 Piper MIT 文本和来源 |
| g2pW v2 ONNX 与规则 | `assets/g2pw/*`、`g2pw-data/*` | [g2pW](https://github.com/GitYCC/g2pW)，模型镜像 revision `d8a5045bf29862e6740e01b52f73526b069075ec`；本地模型做过 int8 动态量化 | 上游 g2pW 为 Apache-2.0；Hugging Face 镜像自身无模型卡和 license 元数据 | 保留 Apache-2.0 文本、来源和修改说明；最好从上游正式模型链接复核哈希 |
| bert-base-chinese vocabulary | `assets/g2pw/vocab.txt` | Google BERT / bert-base-chinese | Apache-2.0 | 保留来源与许可证 |
| phrase-pinyin-data | `phrase-pinyin-data/pinyin.txt` | 0.19.0，revision `cee0ed6e6e4898580cafd2bd5e3723e20b214aa0` | MIT | 已附 `phrase-pinyin-data/LICENSE` |
| 本项目本地发音修正 | `phrase-pinyin-data/local_overrides.txt` | 本项目原创 | AGPL-3.0-only | 由根目录 `LICENSE` 覆盖 |

文件校验值：

| 文件 | SHA-256 |
| --- | --- |
| `sherpa-onnx-1.13.4.aar` | `daf532d343e741df96c15e25f6da90420fc1b54126ee23dc8d49dba617033552` |
| `chaowen.onnx` | `d5ad252f165b26bcecb01759d07cc6cf4cf14045bac8752947a5fb35080c5e6b` |
| `xiao_ya.onnx` | `d4145488f47914614116e4f77338532d827cfccf5f5d7a58c15c5e54cc5434ba` |
| `g2pw/model.onnx` | `000a3dc34bebf3adf8a898d17b6be77c527b7d15ddd672b6abf028a73b81a8e1` |
| `phrase-pinyin-data/pinyin.txt` | `dff030d54e9c9ba48d187fba037d00af410f01c9a867528db6899f539f6e86f7` |

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
- [x] 按原始 `rhasspy/piper-voices` 仓库级 MIT 记录两个 Piper 权重，并随资产保留模型卡摘要。
- [ ] 商业化前重新核查小雅的 BZNSYP/Data Baker 数据来源条款；当前项目按非商业方式发布。
- [ ] 保留根目录 `THIRD_PARTY_NOTICES.md`、`phrase-pinyin-data/LICENSE` 和 `g2pw-data/LICENSE`。
- [ ] 在 App 的“关于/开源许可”页面或随 APK 文档中提供第三方声明；当前 App 尚未实现该页面。
- [ ] 检查 `packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"` 是否移除了依赖要求保留的
  许可证/NOTICE；不要只为了去重而丢弃全部声明。
- [ ] 为 Python 依赖生成锁文件；为 Docker 基础镜像固定 digest，并按最终镜像生成 SBOM。
- [ ] 不将 `.env`、数据库、测试截图、构建目录、历史 APK 或开发签名密钥提交到源码仓库。
- [x] `android/app/src/main/assets/g2pw/model.onnx` 为 159,287,333 字节，使用 Git LFS，避免超过
  GitHub 普通 Git 的 100 MB 单文件限制。
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
