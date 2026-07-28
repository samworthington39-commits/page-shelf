from fastapi.testclient import TestClient

from page_shelf_tts.api import create_app
from page_shelf_tts.config import Settings
from page_shelf_tts.runtime import RuntimeState


def test_health_is_live_while_model_is_cold(settings, fake_runtime) -> None:
    fake_runtime.state = RuntimeState.COLD
    app = create_app(settings=settings, runtime=fake_runtime)

    with TestClient(app) as client:
        response = client.get("/health")

    assert response.status_code == 200
    assert response.json()["model_state"] == "cold"


def test_ready_returns_503_until_model_is_ready(settings, fake_runtime) -> None:
    fake_runtime.state = RuntimeState.LOADING
    app = create_app(settings=settings, runtime=fake_runtime)

    with TestClient(app) as client:
        response = client.get("/ready")

    assert response.status_code == 503
    assert response.json()["ready"] is False


def test_ready_returns_200_for_loaded_model(settings, fake_runtime) -> None:
    app = create_app(settings=settings, runtime=fake_runtime)

    with TestClient(app) as client:
        response = client.get("/ready")

    assert response.status_code == 200
    assert response.json()["ready"] is True


def test_speech_returns_wav_and_metrics(settings, fake_runtime) -> None:
    app = create_app(settings=settings, runtime=fake_runtime)

    with TestClient(app) as client:
        response = client.post(
            "/v1/audio/speech",
            json={
                "input": "你好，世界。",
                "voice": "serena",
                "language": "chinese",
                "seed": 7,
            },
        )

    assert response.status_code == 200
    assert response.headers["content-type"] == "audio/wav"
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["x-tts-generation-ms"] == "420"
    assert response.headers["x-tts-runtime"] == "openvino"
    assert response.headers["x-tts-device"] == "GPU"
    assert response.headers["x-tts-rtf"] == "0.494"
    assert response.content == b"RIFF-test-wave"
    assert fake_runtime.last_request == {
        "text": "你好，世界。",
        "voice": "Serena",
        "language": "Chinese",
        "seed": 7,
    }


def test_api_key_protects_v1_routes(fake_runtime) -> None:
    settings = Settings(
        _env_file=None,
        load_on_startup=False,
        api_key="correct-horse",
    )
    app = create_app(settings=settings, runtime=fake_runtime)

    with TestClient(app) as client:
        missing = client.get("/v1/voices")
        wrong = client.get(
            "/v1/voices",
            headers={"Authorization": "Bearer wrong"},
        )
        accepted = client.get(
            "/v1/voices",
            headers={"Authorization": "Bearer correct-horse"},
        )

    assert missing.status_code == 401
    assert wrong.status_code == 401
    assert accepted.status_code == 200


def test_speech_rejects_unsupported_and_oversized_input(
    fake_runtime,
) -> None:
    settings = Settings(
        _env_file=None,
        load_on_startup=False,
        max_input_chars=5,
    )
    app = create_app(settings=settings, runtime=fake_runtime)

    with TestClient(app) as client:
        voice = client.post(
            "/v1/audio/speech",
            json={"input": "你好", "voice": "missing"},
        )
        language = client.post(
            "/v1/audio/speech",
            json={"input": "你好", "language": "Klingon"},
        )
        oversized = client.post(
            "/v1/audio/speech",
            json={"input": "123456"},
        )

    assert voice.status_code == 422
    assert language.status_code == 422
    assert oversized.status_code == 422
    assert fake_runtime.last_request is None


def test_unsupported_fields_are_rejected(settings, fake_runtime) -> None:
    app = create_app(settings=settings, runtime=fake_runtime)

    with TestClient(app) as client:
        response = client.post(
            "/v1/audio/speech",
            json={
                "input": "你好",
                "instructions": "开心地说",
            },
        )

    assert response.status_code == 422
