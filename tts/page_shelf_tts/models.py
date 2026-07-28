from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class SpeechRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    model: str | None = None
    input: str = Field(min_length=1)
    voice: str | None = None
    language: str | None = None
    response_format: Literal["wav"] = "wav"
    seed: int | None = Field(default=None, ge=0, le=2_147_483_647)


class HealthResponse(BaseModel):
    status: Literal["ok"]
    service: str
    model_state: str
    phase: str
    model: str
    runtime: str
    requested_device: str
    active_device: str | None
    available_devices: list[str]
    fallback_reason: str | None


class ReadyResponse(BaseModel):
    ready: bool
    model_state: str
    phase: str
    model: str
    runtime: str
    requested_device: str
    active_device: str | None
    available_devices: list[str]
    fallback_reason: str | None
    error: str | None = None


class VoiceInfo(BaseModel):
    id: str
    native_language: str


class VoicesResponse(BaseModel):
    model: str
    voices: list[VoiceInfo]
    languages: list[str]
