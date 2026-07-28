import sys
from types import SimpleNamespace

import numpy as np

from page_shelf_tts.qwen_runtime import QwenRuntime


class FakeInferenceMode:
    def __enter__(self) -> None:
        return None

    def __exit__(self, *args: object) -> None:
        return None


class FakeModel:
    def generate_custom_voice(self, **_: object) -> tuple[list[np.ndarray], int]:
        return [np.zeros(2_400, dtype=np.float32)], 24_000


def test_sync_synthesis_encodes_pcm16_wav(settings, monkeypatch) -> None:
    manual_seeds: list[int] = []
    fake_torch = SimpleNamespace(
        inference_mode=FakeInferenceMode,
        manual_seed=manual_seeds.append,
    )
    monkeypatch.setitem(sys.modules, "torch", fake_torch)

    runtime = QwenRuntime(settings)
    runtime._model = FakeModel()
    result = runtime._synthesize_sync(
        text="测试",
        voice="Serena",
        language="Chinese",
        seed=12,
    )

    assert result.audio.startswith(b"RIFF")
    assert result.sample_rate == 24_000
    assert result.duration_ms == 100
    assert result.runtime == "pytorch"
    assert result.device == "CPU"
    assert manual_seeds == [12]
