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
- ONNX Runtime Android 1.27.0: MIT
- Retrofit 3.0.0, OkHttp 4.12.0 and Okio: Apache-2.0
- Gson: Apache-2.0
- Coil 3.3.0: Apache-2.0
- Guava and Accompanist transitive components: Apache-2.0
- Piper voice models: MIT (training-data terms are recorded below)

The complete Apache License 2.0 text included for the bundled g2pW material is
available at `android/app/src/main/g2pw-data/LICENSE`. The MIT license for the
bundled phrase-pinyin-data material is available at
`android/app/src/main/phrase-pinyin-data/LICENSE`.

## Bundled speech components and data

The Android application bundles the following components for fully on-device
text-to-speech. Application source-code licensing does not replace the licenses
or usage terms of these third-party binaries, model weights and data files.

## sherpa-onnx 1.13.4

- Project: https://github.com/k2-fsa/sherpa-onnx
- Bundled file: `android/app/libs/sherpa-onnx-1.13.4.aar`
- License: Apache License 2.0
- Packaging note: this AAR contains the unmodified `arm64-v8a` classes and
  native libraries selected from the official 1.13.4 release AAR; unused ABI
  directories were removed to reduce the application download size.
- Bundled AAR SHA-256: `daf532d343e741df96c15e25f6da90420fc1b54126ee23dc8d49dba617033552`

## Piper Chinese voice: chaowen-medium-int8

- Model: https://huggingface.co/csukuangfj2/vits-piper-zh_CN-chaowen-medium-int8
- Source dataset: https://github.com/OHF-Voice/voice-datasets
- Bundled file: `android/app/src/main/assets/tts/piper_zh/voices/chaowen.onnx`
- Pinned model repository revision: `19375e11252db7afffa04181e114adab3db4219b`
- Model-weight license: MIT, from the repository-level license metadata of
  https://huggingface.co/rhasspy/piper-voices
- Dataset license stated by the model card: CC0 1.0
- Training note: fine-tuned from the MIT-licensed Xiao Ya voice
- ONNX SHA-256: `d5ad252f165b26bcecb01759d07cc6cf4cf14045bac8752947a5fb35080c5e6b`

## Piper Chinese voice: xiao_ya-medium-int8

- Model: https://huggingface.co/csukuangfj2/vits-piper-zh_CN-xiao_ya-medium-int8
- Source dataset: Data Baker / BZNSYP
- Bundled file: `android/app/src/main/assets/tts/piper_zh/voices/xiao_ya.onnx`
- Pinned model repository revision: `c8fb5de2a0fc365031013688c39238402c88e3ab`
- Model-weight license: MIT, from the repository-level license metadata of
  https://huggingface.co/rhasspy/piper-voices
- Dataset usage term stated by the model card: non-commercial use only
- ONNX SHA-256: `d4145488f47914614116e4f77338532d827cfccf5f5d7a58c15c5e54cc5434ba`

The repository-level MIT license covers the model weights. The model card's
non-commercial statement describes the BZNSYP/Data Baker training dataset and
must remain visible. Re-audit that dataset provenance before commercial use.

## Shared Chinese text-processing data

`base_lexicon.txt`, `tokens.txt`, `phone.fst`, `date.fst`, and `number.fst`
were obtained from the sherpa-onnx Piper Chinese model package linked above.
The base lexicon is stored under `android/app/src/main/phrase-pinyin-data/` as
a build input; the other shared files are under the TTS asset directory. They
are distributed with the MIT-licensed Piper voice repository. The retained MIT
text and model-card summary are under `android/app/src/main/assets/tts/piper_zh/`.

## phrase-pinyin-data 0.19.0

- Project: https://github.com/mozillazg/phrase-pinyin-data
- Revision: `cee0ed6e6e4898580cafd2bd5e3723e20b214aa0`
- Bundled source: `android/app/src/main/phrase-pinyin-data/pinyin.txt`
- License: MIT
- `pinyin.txt` SHA-256: `dff030d54e9c9ba48d187fba037d00af410f01c9a867528db6899f539f6e86f7`
- Packaging note: the Android build converts the accented pinyin into Piper
  phoneme tokens, merges it ahead of the model lexicon, and creates a compact
  longest-match trie. No neural grapheme-to-phoneme model is included.

## g2pW v2 Chinese polyphone resolver

- Project: https://github.com/GitYCC/g2pW
- Model source: https://huggingface.co/fbpeng/G2PWModel-v2-onnx
- License: Apache License 2.0 (`android/app/src/main/g2pw-data/LICENSE`)
- Model mirror revision: `d8a5045bf29862e6740e01b52f73526b069075ec`
- Original ONNX model: 635,212,732 bytes.
- Bundled model: `android/app/src/main/assets/g2pw/model.onnx`, dynamically
  quantized to int8; 159,287,333 bytes.
- Bundled model SHA-256:
  `000a3dc34bebf3adf8a898d17b6be77c527b7d15ddd672b6abf028a73b81a8e1`
- The tokenizer vocabulary is `bert-base-chinese` from the Chinese BERT WWM
  distribution. g2pW is queried only for candidate polyphones and does not
  replace the Piper frontend.

The Hugging Face mirror has no model card or license metadata. The Apache-2.0
identification above is based on the upstream g2pW repository, which links the
official v2 ONNX checkpoint. Preserve the source, local quantization notice and
license text when redistributing it.

## ONNX Runtime Java API 1.27.0

- Project: https://github.com/microsoft/onnxruntime
- License: MIT
- The Java API is consumed from `com.microsoft.onnxruntime:onnxruntime-android:1.27.0`.
  Its `libonnxruntime.so` is deduplicated with the same 1.27.0 native runtime
  already bundled by `sherpa-onnx-1.13.4`; no second native runtime is added.
