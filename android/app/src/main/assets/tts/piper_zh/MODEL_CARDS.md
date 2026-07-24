# Piper Chinese voice provenance

The original voice collection is https://huggingface.co/rhasspy/piper-voices,
whose repository-level metadata declares the MIT license. The bundled weights
were converted to int8 by the sherpa-onnx export pipeline.

## Xiao Ya (medium)

- Language: zh_CN
- Sample rate: 22,050 Hz
- Original model: `zh_CN-xiao_ya-medium`
- Dataset: Data Baker / BZNSYP
- Dataset term stated by the upstream model card: non-commercial use
- Training: trained from scratch

## Chaowen (medium)

- Language: zh_CN
- Sample rate: 22,050 Hz
- Original model: `zh_CN-chaowen-medium`
- Dataset: https://github.com/OHF-Voice/voice-datasets
- Dataset license stated by the upstream model card: CC0-1.0
- Training: fine-tuned from the Xiao Ya medium voice

The dataset statements above describe training-data provenance. The model
weights are distributed under the voice repository's MIT license. Preserve
this file and `LICENSE` when redistributing the weights.
