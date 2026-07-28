from dataclasses import dataclass

import pytest

from page_shelf_tts.config import (
    DEFAULT_MODEL_ID,
    SUPPORTED_LANGUAGES,
    SUPPORTED_VOICES,
    Settings,
)
from page_shelf_tts.runtime import (
    RuntimeSnapshot,
    RuntimeState,
    SynthesisResult,
)


@dataclass
class FakeRuntime:
    state: RuntimeState = RuntimeState.READY
    error: str | None = None
    initialize_calls: int = 0
    last_request: dict[str, object] | None = None

    @property
    def supported_voices(self) -> tuple[str, ...]:
        return SUPPORTED_VOICES

    @property
    def supported_languages(self) -> tuple[str, ...]:
        return SUPPORTED_LANGUAGES

    def snapshot(self) -> RuntimeSnapshot:
        return RuntimeSnapshot(
            state=self.state,
            model_id=DEFAULT_MODEL_ID,
            runtime="fake",
            phase=self.state.value,
            requested_device="GPU",
            active_device="GPU" if self.state is RuntimeState.READY else None,
            available_devices=("CPU", "GPU"),
            error=self.error,
        )

    async def initialize(self) -> None:
        self.initialize_calls += 1
        self.state = RuntimeState.READY

    async def synthesize(
        self,
        *,
        text: str,
        voice: str,
        language: str,
        seed: int | None,
    ) -> SynthesisResult:
        self.last_request = {
            "text": text,
            "voice": voice,
            "language": language,
            "seed": seed,
        }
        return SynthesisResult(
            audio=b"RIFF-test-wave",
            sample_rate=24_000,
            duration_ms=850,
            generation_ms=420,
            runtime="openvino",
            device="GPU",
        )


@pytest.fixture
def settings() -> Settings:
    return Settings(_env_file=None, load_on_startup=False)


@pytest.fixture
def fake_runtime() -> FakeRuntime:
    return FakeRuntime()
