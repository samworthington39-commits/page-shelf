import io

import numpy as np
import soundfile as sf

from page_shelf_tts.runtime import SynthesisResult


def encode_pcm16_wav(
    waveform: object,
    *,
    sample_rate: int,
    generation_ms: int,
    runtime: str,
    device: str,
) -> SynthesisResult:
    audio = np.asarray(waveform, dtype=np.float32).squeeze()
    if audio.ndim != 1 or audio.size == 0:
        raise ValueError("model returned an invalid waveform")

    output = io.BytesIO()
    sf.write(output, audio, int(sample_rate), format="WAV", subtype="PCM_16")
    duration_ms = round(audio.size / int(sample_rate) * 1000)

    return SynthesisResult(
        audio=output.getvalue(),
        sample_rate=int(sample_rate),
        duration_ms=duration_ms,
        generation_ms=generation_ms,
        runtime=runtime,
        device=device,
    )
