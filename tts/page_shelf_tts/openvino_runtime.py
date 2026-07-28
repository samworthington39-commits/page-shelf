import asyncio
import importlib.util
import json
import logging
import sys
import time
from collections.abc import Callable
from pathlib import Path
from types import ModuleType
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

OPENVINO_HELPER_REVISION = "473c26170051836d35fd235af7561c0daac737f4"

EXPECTED_OPENVINO_FILES = (
    "openvino_talker_language_model.xml",
    "openvino_talker_language_model.bin",
    "openvino_talker_embedding_model.xml",
    "openvino_talker_embedding_model.bin",
    "openvino_talker_text_embedding_model.xml",
    "openvino_talker_text_embedding_model.bin",
    "openvino_talker_text_projection_model.xml",
    "openvino_talker_text_projection_model.bin",
    "openvino_talker_code_predictor_embedding_model.xml",
    "openvino_talker_code_predictor_embedding_model.bin",
    "openvino_talker_code_predictor_model.xml",
    "openvino_talker_code_predictor_model.bin",
    "speech_tokenizer/openvino_speech_tokenizer_encoder_model.xml",
    "speech_tokenizer/openvino_speech_tokenizer_encoder_model.bin",
    "speech_tokenizer/openvino_speech_tokenizer_decoder_model.xml",
    "speech_tokenizer/openvino_speech_tokenizer_decoder_model.bin",
)


class OpenVinoRuntime:
    def __init__(
        self,
        settings: Settings,
        *,
        helper_loader: Callable[[], ModuleType] | None = None,
    ) -> None:
        self._settings = settings
        self._helper_loader = helper_loader or self._load_helper_module
        self._helper: ModuleType | None = None
        self._model: Any | None = None
        self._state = RuntimeState.COLD
        self._phase = "idle"
        self._error: str | None = None
        self._active_device: str | None = None
        self._available_devices: tuple[str, ...] = ()
        self._fallback_reason: str | None = None
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
            runtime="openvino",
            phase=self._phase,
            requested_device=self._settings.openvino_device,
            active_device=self._active_device,
            available_devices=self._available_devices,
            fallback_reason=self._fallback_reason,
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
            self._phase = "detecting"
            logger.info(
                "Loading OpenVINO Qwen TTS model; requested device=%s",
                self._settings.openvino_device,
            )
            try:
                self._model = await asyncio.to_thread(self._load_model)
            except Exception as exc:
                error_type = type(exc).__name__
                self._error = f"OpenVINO initialization failed ({error_type})"
                self._state = RuntimeState.FAILED
                self._phase = "failed"
                logger.exception("OpenVINO TTS initialization failed")
                raise RuntimeUnavailableError(self._error) from exc

            self._error = None
            self._state = RuntimeState.READY
            self._phase = "ready"
            logger.info(
                "OpenVINO Qwen TTS model is ready on %s",
                self._active_device,
            )

    def _load_helper_module(self) -> ModuleType:
        helper_path = self._settings.openvino_helper_path
        if not helper_path.is_file():
            raise FileNotFoundError(f"OpenVINO Qwen helper not found: {helper_path}")

        module_name = "_page_shelf_openvino_qwen_helper"
        existing = sys.modules.get(module_name)
        if existing is not None:
            return existing

        spec = importlib.util.spec_from_file_location(module_name, helper_path)
        if spec is None or spec.loader is None:
            raise ImportError("cannot load OpenVINO Qwen helper")
        module = importlib.util.module_from_spec(spec)
        sys.modules[module_name] = module
        try:
            spec.loader.exec_module(module)
        except Exception:
            sys.modules.pop(module_name, None)
            raise
        return module

    def _load_model(self) -> Any:
        helper = self._helper_loader()
        self._helper = helper
        self._settings.openvino_cache_dir.mkdir(parents=True, exist_ok=True)
        try:
            helper.core.set_property(
                {"CACHE_DIR": str(self._settings.openvino_cache_dir)}
            )
        except Exception as exc:
            logger.warning(
                "OpenVINO compiled-model cache could not be enabled: %s",
                type(exc).__name__,
            )

        self._available_devices = tuple(
            str(device).upper() for device in helper.core.available_devices
        )
        selected = self.select_device(
            requested=self._settings.openvino_device,
            available=self._available_devices,
            allow_cpu_fallback=self._settings.openvino_allow_cpu_fallback,
        )
        if selected == "CPU" and self._settings.openvino_device == "GPU":
            self._fallback_reason = "GPU is not exposed to OpenVINO"

        self._ensure_converted(helper)
        self._phase = "compiling"
        try:
            model = helper.OVQwen3TTSModel.from_pretrained(
                str(self._settings.openvino_model_dir),
                device=selected,
            )
            self._active_device = selected
            return model
        except Exception as exc:
            can_fallback = (
                selected == "GPU"
                and self._settings.openvino_allow_cpu_fallback
                and self._has_device("CPU", self._available_devices)
            )
            if not can_fallback:
                raise

            error_type = type(exc).__name__
            self._fallback_reason = f"GPU compilation failed ({error_type}); using CPU"
            logger.warning(
                "OpenVINO GPU compilation failed; falling back to CPU",
                exc_info=True,
            )
            model = helper.OVQwen3TTSModel.from_pretrained(
                str(self._settings.openvino_model_dir),
                device="CPU",
            )
            self._active_device = "CPU"
            return model

    @staticmethod
    def _has_device(device: str, available: tuple[str, ...]) -> bool:
        requested = device.upper()
        return any(
            candidate == requested or candidate.startswith(f"{requested}.")
            for candidate in available
        )

    @classmethod
    def select_device(
        cls,
        *,
        requested: str,
        available: tuple[str, ...],
        allow_cpu_fallback: bool,
    ) -> str:
        requested = requested.upper()
        if requested == "AUTO":
            if not available:
                raise RuntimeError("OpenVINO reports no inference devices")
            return "AUTO"
        if cls._has_device(requested, available):
            return requested
        if (
            requested == "GPU"
            and allow_cpu_fallback
            and cls._has_device("CPU", available)
        ):
            return "CPU"
        raise RuntimeError(f"requested OpenVINO device {requested} is unavailable")

    def _ensure_converted(self, helper: ModuleType) -> None:
        model_dir = self._settings.openvino_model_dir
        missing = self._missing_converted_files(model_dir)
        if not missing:
            return
        if not self._settings.openvino_auto_convert:
            raise RuntimeError(
                "OpenVINO model is not converted and auto conversion is disabled"
            )

        self._phase = "converting"
        model_dir.mkdir(parents=True, exist_ok=True)
        logger.info(
            "Converting %s to OpenVINO IR; this is a one-time operation",
            self._settings.model_id,
        )
        helper.convert_qwen3_tts_model(
            self._settings.model_id,
            model_dir,
            quantization_config=None,
        )

        tokenizer_files = (
            model_dir
            / "speech_tokenizer"
            / "openvino_speech_tokenizer_encoder_model.xml",
            model_dir
            / "speech_tokenizer"
            / "openvino_speech_tokenizer_decoder_model.xml",
        )
        if not all(path.is_file() for path in tokenizer_files):
            from huggingface_hub import snapshot_download

            checkpoint = Path(
                snapshot_download(
                    self._settings.model_id,
                    allow_patterns=[
                        "speech_tokenizer/**",
                        "*.json",
                        "*.txt",
                    ],
                    ignore_patterns=["*.safetensors", "*.bin"],
                )
            )
            helper.convert_speech_tokenizer(
                str(checkpoint / "speech_tokenizer"),
                model_dir / "speech_tokenizer",
            )

        missing = self._missing_converted_files(model_dir)
        if missing:
            raise RuntimeError(
                "OpenVINO conversion is incomplete: " + ", ".join(missing)
            )

        manifest = {
            "model_id": self._settings.model_id,
            "helper_revision": OPENVINO_HELPER_REVISION,
        }
        (model_dir / "page-shelf-conversion.json").write_text(
            json.dumps(manifest, indent=2) + "\n",
            encoding="utf-8",
        )

    @staticmethod
    def _missing_converted_files(model_dir: Path) -> list[str]:
        return [
            relative
            for relative in EXPECTED_OPENVINO_FILES
            if not (model_dir / relative).is_file()
        ]

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
                logger.error("OpenVINO TTS synthesis failed: %s", error_type)
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

        if self._model is None or self._active_device is None:
            raise RuntimeUnavailableError("OpenVINO model is not loaded")
        if seed is not None:
            torch.manual_seed(seed)

        started = time.perf_counter()
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
            runtime="openvino",
            device=self._active_device,
        )
