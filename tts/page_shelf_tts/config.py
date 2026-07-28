from pathlib import Path
from typing import Literal

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

DEFAULT_MODEL_ID = "Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice"

SUPPORTED_VOICES = (
    "Vivian",
    "Serena",
    "Uncle_Fu",
    "Dylan",
    "Eric",
    "Ryan",
    "Aiden",
    "Ono_Anna",
    "Sohee",
)

SUPPORTED_LANGUAGES = (
    "Auto",
    "Chinese",
    "English",
    "Japanese",
    "Korean",
    "German",
    "French",
    "Russian",
    "Portuguese",
    "Spanish",
    "Italian",
)


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="TTS_",
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    model_id: str = DEFAULT_MODEL_ID
    runtime: Literal["openvino", "pytorch"] = "openvino"
    device: Literal["cpu"] = "cpu"
    dtype: Literal["float32", "bfloat16"] = "float32"
    attention_implementation: Literal["eager", "sdpa"] = "eager"
    openvino_device: Literal["GPU", "CPU", "AUTO"] = "GPU"
    openvino_allow_cpu_fallback: bool = True
    openvino_auto_convert: bool = True
    openvino_model_dir: Path = Path("/models/openvino/qwen3-tts-0.6b-customvoice")
    openvino_cache_dir: Path = Path("/models/openvino-cache")
    openvino_helper_path: Path = Path("/opt/qwen-openvino/qwen_3_tts_helper.py")
    default_voice: str = "Serena"
    default_language: str = "Chinese"
    max_input_chars: int = Field(default=500, ge=1, le=10_000)
    load_on_startup: bool = True
    enable_api_docs: bool = False
    api_key: SecretStr | None = None
    torch_threads: int = Field(default=0, ge=0, le=256)

    @field_validator("api_key", mode="before")
    @classmethod
    def normalize_empty_api_key(cls, value: object) -> object:
        if isinstance(value, str) and not value.strip():
            return None
        return value

    @model_validator(mode="after")
    def validate_defaults(self) -> "Settings":
        if self.model_id != DEFAULT_MODEL_ID:
            raise ValueError(
                f"phase-one service only supports model_id={DEFAULT_MODEL_ID}"
            )
        if self.default_voice not in SUPPORTED_VOICES:
            raise ValueError(
                f"default_voice must be one of: {', '.join(SUPPORTED_VOICES)}"
            )
        if self.default_language not in SUPPORTED_LANGUAGES:
            raise ValueError(
                "default_language must be one of: " + ", ".join(SUPPORTED_LANGUAGES)
            )
        return self
