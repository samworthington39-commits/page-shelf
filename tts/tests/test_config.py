import pytest
from pydantic import ValidationError

from page_shelf_tts.config import Settings


def test_blank_api_key_disables_authentication() -> None:
    settings = Settings(_env_file=None, api_key="   ")

    assert settings.api_key is None


def test_invalid_default_voice_is_rejected() -> None:
    with pytest.raises(ValidationError):
        Settings(_env_file=None, default_voice="not-a-voice")


def test_phase_one_device_is_cpu_only() -> None:
    with pytest.raises(ValidationError):
        Settings(_env_file=None, device="cuda")


def test_gpu_openvino_is_the_default_runtime() -> None:
    settings = Settings(_env_file=None)

    assert settings.runtime == "openvino"
    assert settings.openvino_device == "GPU"
    assert settings.openvino_allow_cpu_fallback is True


def test_unknown_model_is_rejected_to_protect_the_conversion_cache() -> None:
    with pytest.raises(ValidationError):
        Settings(_env_file=None, model_id="Qwen/another-model")
