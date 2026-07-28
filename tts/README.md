# 页架 Qwen TTS 服务

这是与现有页架后端完全分离的第二个 Docker 项目。默认使用 OpenVINO
调用 Intel 核显生成完整 WAV，再由调用方传给手机播放。本目录不会修改或
替换 Android App 现有的 Matcha 端侧离线朗读链路。当前网页阅读器和
Android App 均未接入本服务。

## N5105 结论

Intel N5105 是 10W、4 核 4 线程处理器，最高 2.9GHz；核显为 24 EU、
最高 800MHz。Qwen3-TTS 可以在 CPU 上运行，但不应预设它能实时朗读。
默认配置因此采用：

1. OpenVINO `GPU` 优先。
2. GPU 不可用或模型编译失败时，自动回退到 OpenVINO `CPU`。
3. 每次合成返回实际 runtime、device 和 RTF，必须以 NAS 实测决定是否
   达到可用标准。

RTF 小于 1 表示生成速度快于音频播放速度；等于 1 为刚好实时；大于 1
表示手机播放完当前音频前，下一段可能还没有生成完成。

## 首版范围

- 模型：`Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice`
- 推理：OpenVINO 2025.4.1，默认设备 `GPU`
- 驱动：N5105/Gen11 对应的 Intel legacy1 OpenCL 运行时
- 输出：单次请求返回完整 PCM16 WAV
- 音色：Vivian、Serena、Uncle_Fu、Dylan、Eric、Ryan、Aiden、
  Ono_Anna、Sohee
- 可选 Bearer Token 鉴权
- 独立模型缓存卷，包含 Hugging Face 文件、OpenVINO IR 和编译缓存
- 单进程、单推理并发，避免低功耗 NAS 被并发请求压垮

0.6B CustomVoice 的当前官方 Python 实现不会应用 `instruct` 参数，因此
API 不提供“情感指令”。手机播放速度仍由手机客户端本地控制。

## NAS 前置检查

宿主机必须已经加载 `i915` 驱动，并暴露 render device：

```bash
ls -l /dev/dri
stat -c '%g' /dev/dri/renderD128
stat -c '%g' /dev/dri/card0
```

把后两条命令返回的数字分别写入 `.env` 的 `TTS_DRI_GID` 和
`TTS_VIDEO_GID`。如果
`/dev/dri/renderD128` 不存在，Docker 容器无法调用核显，需要先处理 NAS
系统的 i915 驱动或设备透传。

镜像内固定安装 Intel `24.35.30872.36 legacy1` OpenCL 驱动。内核驱动来自
NAS 宿主机，用户态 OpenCL 驱动来自容器，两者缺一不可。

## 启动

```bash
cd /path/to/page-shelf/tts
cp .env.example .env
# 编辑 .env，至少确认 TTS_DRI_GID 和 TTS_API_KEY
docker compose up --detach --build
docker compose logs --follow qwen
```

构建完成后可以先单独检查容器是否识别核显：

```bash
docker compose run --rm --entrypoint clinfo qwen -l
```

输出中必须能看到 Intel OpenCL GPU。服务启动后检查：

```bash
curl http://127.0.0.1:8010/health
curl --fail http://127.0.0.1:8010/ready
```

首次启动会先下载模型，再把 PyTorch 模型转换为 OpenVINO IR。转换本身主要
使用 CPU 和内存，可能明显慢于后续启动。成功后文件保存在模型卷中，不会在
每次容器更新时重复转换。

`/ready` 的关键字段示例：

```json
{
  "ready": true,
  "runtime": "openvino",
  "requested_device": "GPU",
  "active_device": "GPU",
  "available_devices": ["CPU", "GPU"],
  "fallback_reason": null
}
```

如果 `active_device` 是 `CPU`，说明 GPU 路径没有真正启用，应根据
`fallback_reason` 和容器日志排查，不能把它当作 GPU 测试结果。

## 生成语音

```bash
curl --fail-with-body \
  -X POST http://127.0.0.1:8010/v1/audio/speech \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${TTS_API_KEY}" \
  -d '{"input":"你好，这是一段页架在线语音。","voice":"Serena","language":"Chinese"}' \
  --output sample.wav
```

请求结构：

```json
{
  "model": "Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice",
  "input": "需要合成的文本",
  "voice": "Serena",
  "language": "Chinese",
  "response_format": "wav",
  "seed": 7
}
```

响应头包括：

- `X-TTS-Runtime`：`openvino` 或 `pytorch`
- `X-TTS-Device`：实际使用的 `GPU` 或 `CPU`
- `X-TTS-Generation-Ms`：生成耗时
- `X-TTS-Duration-Ms`：生成的音频时长
- `X-TTS-RTF`：生成耗时除以音频时长

## 在 N5105 上跑基准

模型就绪后至少运行三次，第一遍通常包含额外预热开销：

```bash
docker compose exec qwen \
  python3 -m page_shelf_tts.benchmark --runs 3
```

如果设置了 API Key，该命令会直接读取容器内的 `TTS_API_KEY`。最后一行会
输出 `median_rtf` 和 `realtime`。建议分别测试 30、100、300 个中文字，
同时观察 NAS 温度、降频和内存占用。

对于在线按句朗读，建议目标是预热后中位 RTF 小于 0.8，给网络传输和手机
缓冲留余量。RTF 介于 0.8 和 1.2 时可以继续尝试短句预生成；明显大于 1.2
则不适合作为稳定的实时在线 TTS。

## 接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/health` | 进程存活、转换阶段和设备状态 |
| GET | `/ready` | 模型就绪检查，未就绪时返回 503 |
| GET | `/v1/voices` | 查询音色和语言 |
| POST | `/v1/audio/speech` | 生成完整 WAV |

`/health` 和 `/ready` 始终公开；配置 API Key 后，两个 `/v1` 接口需要鉴权。
默认关闭交互式 API 文档，可信开发环境可设置
`TTS_ENABLE_API_DOCS=true` 后访问 `/docs`。

## GPU 回退与故障判断

- `available_devices` 没有 `GPU`：检查 `/dev/dri` 映射、`TTS_DRI_GID`、
  宿主机 i915 和容器内 `clinfo`。
- 有 `GPU` 但 `active_device=CPU`：GPU 模型编译失败，查看
  `fallback_reason` 和日志。
- 需要强制暴露问题而不是回退：设置
  `TTS_OPENVINO_ALLOW_CPU_FALLBACK=false`。
- 需要对照 CPU：设置 `TTS_OPENVINO_DEVICE=CPU`。
- 需要回到原 PyTorch CPU 实现：设置 `TTS_RUNTIME=pytorch`。

OpenVINO 官方 Qwen3-TTS 转换方案仍标记为实验性，因此 GPU 可用性必须以
目标 NAS 的 `/ready` 和 RTF 基准为准，不能只看容器成功启动。

## 缓存

缓存位于 Compose 卷 `page-shelf-tts_qwen-model-cache`。其中 OpenVINO IR
是一次性转换结果。删除该卷会导致模型重新下载和重新转换：

```bash
docker compose down
# 仅在确认需要完全重建模型缓存时执行：
docker volume rm page-shelf-tts_qwen-model-cache
```

## 参考

- [Intel N5105 官方规格](https://www.intel.com/content/www/us/en/products/sku/212328/intel-celeron-processor-n5105-4m-cache-up-to-2-90-ghz/specifications.html)
- [Qwen3-TTS 官方仓库](https://github.com/QwenLM/Qwen3-TTS)
- [0.6B CustomVoice 官方模型](https://huggingface.co/Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice)
- [OpenVINO Qwen3-TTS 实验笔记本](https://github.com/openvinotoolkit/openvino_notebooks/tree/latest/notebooks/qwen3-tts)
- [Intel legacy compute runtime](https://github.com/intel/compute-runtime)
