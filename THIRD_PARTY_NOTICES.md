# Third-party notices

本文件是随源码、Docker 镜像和 Android 二进制发布的第三方声明入口。完整的依赖与许可证审计、
传递依赖、构建工具和发布检查表见
[`docs/open-source-compliance.md`](docs/open-source-compliance.md)。

项目原创代码采用 `AGPL-3.0-only`。第三方许可证和使用条款不会被项目根许可证替代。

## Software dependencies

### Backend runtime

- FastAPI: MIT
- Uvicorn: BSD-3-Clause
- SQLAlchemy: MIT
- pydantic-settings: MIT
- pypdf: BSD-3-Clause
- PyMuPDF/MuPDF: AGPL-3.0-only OR Artifex Commercial License
- defusedxml: PSF-2.0

PyMuPDF is a strong-copyleft dependency. This repository uses AGPL-3.0-only for
its original code and provides the corresponding source and build files.

### Android runtime

- AndroidX and Jetpack Compose: Apache-2.0
- Kotlin standard library, coroutines and serialization: Apache-2.0
- sherpa-onnx 1.13.4: Apache-2.0
- Retrofit 3.0.0, OkHttp 4.12.0 and Okio: Apache-2.0
- Gson: Apache-2.0
- Coil 3.3.0: Apache-2.0
- Guava and Accompanist transitive components: Apache-2.0

## Bundled speech components and data

The Android application bundles the following components for fully on-device
text-to-speech. Application source-code licensing does not replace the licenses
or usage terms of these third-party binaries, model weights and data files.

### sherpa-onnx 1.13.4

- Project: https://github.com/k2-fsa/sherpa-onnx
- Bundled file: `android/app/libs/sherpa-onnx-1.13.4.aar`
- License: Apache License 2.0
- Packaging note: this AAR contains the unmodified `arm64-v8a` classes and
  native libraries selected from the official 1.13.4 release AAR; unused ABI
  directories were removed to reduce the application download size.
- Bundled AAR SHA-256: `daf532d343e741df96c15e25f6da90420fc1b54126ee23dc8d49dba617033552`

### matcha-icefall-zh-en

- Original model: https://modelscope.cn/models/dengcunqin/matcha_tts_zh_en_20251010
- Distribution: https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
- Bundled directory: `android/app/src/main/assets/tts/matcha_zh_en/`
- Release asset: `matcha-icefall-zh-en.tar.bz2`
- Release archive SHA-256:
  `271b804af570400d3bcdcb53bf6e53cc9f75180ee763b9f13eb5eaf2b0d086ef`
- Acoustic ONNX SHA-256:
  `524286bf6cf11be74329ae1c682ac69e34d6860c2ea9fd1290319d561540b16a`
- Metadata: one speaker, Chinese and English, 16 kHz, three ODE steps.
- License metadata: Apache-2.0; a copy is bundled as
  `LICENSE_APACHE-2.0.txt`.

The model repository and release archive do not provide enough information to
independently audit every training-data source. Re-check dataset provenance
before a commercial release even though the model metadata lists Apache-2.0.

### Vocos 16 kHz universal vocoder

- Project: https://github.com/gemelo-ai/vocos
- Distribution:
  https://github.com/k2-fsa/sherpa-onnx/releases/tag/vocoder-models
- Bundled file:
  `android/app/src/main/assets/tts/matcha_zh_en/vocos-16khz-univ.onnx`
- ONNX SHA-256:
  `b599142a1fb8ff03de3e84ac35ff537c619e56f4267a6fe894851a42844acf9e`
- License: MIT; the upstream license is bundled as `LICENSE_VOCOS.txt`.

### phrase-pinyin-data 0.19.0

- Project: https://github.com/mozillazg/phrase-pinyin-data
- Source revision: `cee0ed6e6e4898580cafd2bd5e3723e20b214aa0`
- Source file SHA-256:
  `4cf565635a092f3911a7f560fc604aa1a05c95a121488fb28562f3dd2d1441f7`
- Derived bundled file:
  `android/app/src/main/assets/tts/matcha_zh_en/novel-phrase-lexicon.txt`
- Derived file SHA-256:
  `210b7793ab3251103a13f7af4613bf00f38fe0528087ff0ba49cec9af72a8c63`
- License: MIT; the upstream license is bundled as
  `LICENSE_PHRASE_PINYIN_DATA.txt`.
- Derivation: accented pinyin is converted to Matcha numbered tokens, then
  merged with the project's reviewed novel overrides and filtered to
  context-sensitive 2-10 character phrases.
