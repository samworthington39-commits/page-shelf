from dataclasses import dataclass
from enum import Enum
from typing import Protocol


class RuntimeState(str, Enum):
    COLD = "cold"
    LOADING = "loading"
    READY = "ready"
    FAILED = "failed"


@dataclass(frozen=True)
class RuntimeSnapshot:
    state: RuntimeState
    model_id: str
    runtime: str
    phase: str
    requested_device: str
    active_device: str | None = None
    available_devices: tuple[str, ...] = ()
    fallback_reason: str | None = None
    error: str | None = None


@dataclass(frozen=True)
class SynthesisResult:
    audio: bytes
    sample_rate: int
    duration_ms: int
    generation_ms: int
    runtime: str
    device: str


class RuntimeUnavailableError(RuntimeError):
    """The model is not available for inference."""


class RuntimeSynthesisError(RuntimeError):
    """The model failed while generating audio."""


class TtsRuntime(Protocol):
    @property
    def supported_voices(self) -> tuple[str, ...]: ...

    @property
    def supported_languages(self) -> tuple[str, ...]: ...

    def snapshot(self) -> RuntimeSnapshot: ...

    async def initialize(self) -> None: ...

    async def synthesize(
        self,
        *,
        text: str,
        voice: str,
        language: str,
        seed: int | None,
    ) -> SynthesisResult: ...
