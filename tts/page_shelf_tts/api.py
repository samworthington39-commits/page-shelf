import asyncio
import secrets
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager, suppress

from fastapi import Depends, FastAPI, Header, HTTPException, status
from fastapi.responses import JSONResponse, Response

from page_shelf_tts import __version__
from page_shelf_tts.config import Settings
from page_shelf_tts.models import (
    HealthResponse,
    ReadyResponse,
    SpeechRequest,
    VoiceInfo,
    VoicesResponse,
)
from page_shelf_tts.openvino_runtime import OpenVinoRuntime
from page_shelf_tts.qwen_runtime import QwenRuntime
from page_shelf_tts.runtime import (
    RuntimeState,
    RuntimeSynthesisError,
    RuntimeUnavailableError,
    TtsRuntime,
)

VOICE_NATIVE_LANGUAGES = {
    "Vivian": "Chinese",
    "Serena": "Chinese",
    "Uncle_Fu": "Chinese",
    "Dylan": "Chinese",
    "Eric": "Chinese",
    "Ryan": "English",
    "Aiden": "English",
    "Ono_Anna": "Japanese",
    "Sohee": "Korean",
}


def _canonical(value: str, supported: tuple[str, ...]) -> str | None:
    normalized = value.casefold()
    return next((item for item in supported if item.casefold() == normalized), None)


def create_app(
    *,
    settings: Settings | None = None,
    runtime: TtsRuntime | None = None,
) -> FastAPI:
    current_settings = settings or Settings()
    if runtime is not None:
        current_runtime = runtime
    elif current_settings.runtime == "openvino":
        current_runtime = OpenVinoRuntime(current_settings)
    else:
        current_runtime = QwenRuntime(current_settings)
    startup_task: asyncio.Task[None] | None = None

    def consume_startup_result(task: asyncio.Task[None]) -> None:
        if not task.cancelled():
            task.exception()

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        nonlocal startup_task
        if current_settings.load_on_startup:
            startup_task = asyncio.create_task(
                current_runtime.initialize(),
                name="qwen-tts-model-loader",
            )
            startup_task.add_done_callback(consume_startup_result)
        yield
        if startup_task is not None and not startup_task.done():
            startup_task.cancel()
            with suppress(asyncio.CancelledError):
                await startup_task

    docs_url = "/docs" if current_settings.enable_api_docs else None
    openapi_url = "/openapi.json" if current_settings.enable_api_docs else None
    app = FastAPI(
        title="Page Shelf Qwen TTS",
        version=__version__,
        docs_url=docs_url,
        redoc_url=None,
        openapi_url=openapi_url,
        lifespan=lifespan,
    )

    async def require_api_key(
        authorization: str | None = Header(default=None),
    ) -> None:
        configured = current_settings.api_key
        if configured is None:
            return
        scheme, _, supplied = (authorization or "").partition(" ")
        valid = (
            scheme.casefold() == "bearer"
            and bool(supplied)
            and secrets.compare_digest(
                supplied.encode("utf-8"),
                configured.get_secret_value().encode("utf-8"),
            )
        )
        if not valid:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="invalid or missing bearer token",
                headers={"WWW-Authenticate": "Bearer"},
            )

    @app.get("/health", response_model=HealthResponse)
    async def health() -> HealthResponse:
        snapshot = current_runtime.snapshot()
        return HealthResponse(
            status="ok",
            service="page-shelf-qwen-tts",
            model_state=snapshot.state.value,
            phase=snapshot.phase,
            model=snapshot.model_id,
            runtime=snapshot.runtime,
            requested_device=snapshot.requested_device,
            active_device=snapshot.active_device,
            available_devices=list(snapshot.available_devices),
            fallback_reason=snapshot.fallback_reason,
        )

    @app.get(
        "/ready",
        response_model=ReadyResponse,
        responses={503: {"model": ReadyResponse}},
    )
    async def ready() -> ReadyResponse | JSONResponse:
        snapshot = current_runtime.snapshot()
        payload = ReadyResponse(
            ready=snapshot.state is RuntimeState.READY,
            model_state=snapshot.state.value,
            phase=snapshot.phase,
            model=snapshot.model_id,
            runtime=snapshot.runtime,
            requested_device=snapshot.requested_device,
            active_device=snapshot.active_device,
            available_devices=list(snapshot.available_devices),
            fallback_reason=snapshot.fallback_reason,
            error=snapshot.error,
        )
        if not payload.ready:
            return JSONResponse(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                content=payload.model_dump(),
            )
        return payload

    @app.get(
        "/v1/voices",
        response_model=VoicesResponse,
        dependencies=[Depends(require_api_key)],
    )
    async def voices() -> VoicesResponse:
        return VoicesResponse(
            model=current_settings.model_id,
            voices=[
                VoiceInfo(
                    id=voice,
                    native_language=VOICE_NATIVE_LANGUAGES.get(voice, "Unknown"),
                )
                for voice in current_runtime.supported_voices
            ],
            languages=list(current_runtime.supported_languages),
        )

    @app.post(
        "/v1/audio/speech",
        response_class=Response,
        responses={
            200: {"content": {"audio/wav": {}}},
            503: {"description": "Model is not ready"},
        },
        dependencies=[Depends(require_api_key)],
    )
    async def speech(request: SpeechRequest) -> Response:
        requested_model = request.model or current_settings.model_id
        if requested_model != current_settings.model_id:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=f"model must be {current_settings.model_id}",
            )
        if len(request.input) > current_settings.max_input_chars:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=(f"input exceeds {current_settings.max_input_chars} characters"),
            )

        requested_voice = request.voice or current_settings.default_voice
        voice = _canonical(requested_voice, current_runtime.supported_voices)
        if voice is None:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail="unsupported voice",
            )

        requested_language = request.language or current_settings.default_language
        language = _canonical(
            requested_language,
            current_runtime.supported_languages,
        )
        if language is None:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail="unsupported language",
            )

        try:
            result = await current_runtime.synthesize(
                text=request.input,
                voice=voice,
                language=language,
                seed=request.seed,
            )
        except RuntimeUnavailableError as exc:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail=str(exc),
            ) from exc
        except RuntimeSynthesisError as exc:
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail=str(exc),
            ) from exc

        return Response(
            content=result.audio,
            media_type="audio/wav",
            headers={
                "Cache-Control": "no-store",
                "X-TTS-Generation-Ms": str(result.generation_ms),
                "X-TTS-Duration-Ms": str(result.duration_ms),
                "X-TTS-Sample-Rate": str(result.sample_rate),
                "X-TTS-Runtime": result.runtime,
                "X-TTS-Device": result.device,
                "X-TTS-RTF": (
                    f"{result.generation_ms / max(result.duration_ms, 1):.3f}"
                ),
            },
        )

    return app
