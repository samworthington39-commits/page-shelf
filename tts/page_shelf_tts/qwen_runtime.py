import asyncio
import logging
import time
from typing import Any

from page_shelf_tts.audio import encode_pcm16_wav
from page_shelf_tts.config import (
    SUPPORTED_LANGUAGES,
    SUPPORTED_VOICES,
    Settings,
)
from page_shelf_tts.runtime import (
    RuntimeSnapshot,
    RuntimeState,
    RuntimeSynthesisError,
    RuntimeUnavailableError,
    SynthesisResult,
)

logger = logging.getLogger(__name__)


class QwenRuntime:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._model: Any | None = None
        self._state = RuntimeState.COLD
        self._phase = "idle"
        self._error: str | None = None
        self._load_lock = asyncio.Lock()
        self._inference_lock = asyncio.Lock()

    @property
    def supported_voices(self) -> tuple[str, ...]:
        return SUPPORTED_VOICES

    @property
    def supported_languages(self) -> tuple[str, ...]:
        return SUPPORTED_LANGUAGES

    def snapshot(self) -> RuntimeSnapshot:
        return RuntimeSnapshot(
            state=self._state,
            model_id=self._settings.model_id,
            runtime="pytorch",
            phase=self._phase,
            requested_device=self._settings.device.upper(),
            active_device=(
                self._settings.device.upper()
                if self._state is RuntimeState.READY
                else None
            ),
            available_devices=("CPU",),
            error=self._error,
        )

    async def initialize(self) -> None:
        if self._state is RuntimeState.READY:
            return
        if self._state is RuntimeState.FAILED:
            raise RuntimeUnavailableError(self._error or "model initialization failed")

        async with self._load_lock:
            if self._state is RuntimeState.READY:
                return
            if self._state is RuntimeState.FAILED:
                raise RuntimeUnavailableError(
                    self._error or "model initialization failed"
                )

            self._state = RuntimeState.LOADING
            self._phase = "loading"
            logger.info("Loading Qwen TTS model on %s", self._settings.device)
            try:
                self._model = await asyncio.to_thread(self._load_model)
            except Exception as exc:
                error_type = type(exc).__name__
                self._error = f"model initialization failed ({error_type})"
                self._state = RuntimeState.FAILED
                self._phase = "failed"
                logger.error("Qwen TTS model initialization failed: %s", error_type)
                raise RuntimeUnavailableError(self._error) from exc

            self._error = None
            self._state = RuntimeState.READY
            self._phase = "ready"
            logger.info("Qwen TTS model is ready")

    def _load_model(self) -> Any:
        import torch
        from qwen_tts import Qwen3TTSModel

        if self._settings.torch_threads:
            torch.set_num_threads(self._settings.torch_threads)

        dtype = {
            "float32": torch.float32,
            "bfloat16": torch.bfloat16,
        }[self._settings.dtype]

        return Qwen3TTSModel.from_pretrained(
            self._settings.model_id,
            device_map=self._settings.device,
            dtype=dtype,
            attn_implementation=self._settings.attention_implementation,
        )

    async def synthesize(
        self,
        *,
        text: str,
        voice: str,
        language: str,
        seed: int | None,
    ) -> SynthesisResult:
        await self.initialize()
        async with self._inference_lock:
            try:
                return await asyncio.to_thread(
                    self._synthesize_sync,
                    text=text,
                    voice=voice,
                    language=language,
                    seed=seed,
                )
            except Exception as exc:
                error_type = type(exc).__name__
                logger.error("Qwen TTS synthesis failed: %s", error_type)
                raise RuntimeSynthesisError(
                    f"audio generation failed ({error_type})"
                ) from exc

    def _synthesize_sync(
        self,
        *,
        text: str,
        voice: str,
        language: str,
        seed: int | None,
    ) -> SynthesisResult:
        import torch

        if self._model is None:
            raise RuntimeUnavailableError("model is not loaded")
        if seed is not None:
            torch.manual_seed(seed)

        started = time.perf_counter()
        with torch.inference_mode():
            wavs, sample_rate = self._model.generate_custom_voice(
                text=text,
                language=language,
                speaker=voice,
            )
        generation_ms = round((time.perf_counter() - started) * 1000)

        return encode_pcm16_wav(
            wavs[0],
            sample_rate=int(sample_rate),
            generation_ms=generation_ms,
            runtime="pytorch",
            device=self._settings.device.upper(),
        )
