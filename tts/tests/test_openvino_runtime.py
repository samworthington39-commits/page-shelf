import asyncio
from types import SimpleNamespace

import pytest

from page_shelf_tts.config import Settings
from page_shelf_tts.openvino_runtime import (
    EXPECTED_OPENVINO_FILES,
    OpenVinoRuntime,
)
from page_shelf_tts.runtime import RuntimeState


class FakeCore:
    def __init__(self, devices: tuple[str, ...]) -> None:
        self.available_devices = devices
        self.properties: dict[str, str] = {}

    def set_property(self, properties: dict[str, str]) -> None:
        self.properties.update(properties)


def create_converted_model_tree(model_dir) -> None:
    for relative in EXPECTED_OPENVINO_FILES:
        path = model_dir / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("test", encoding="utf-8")


def test_gpu_is_selected_when_exposed() -> None:
    selected = OpenVinoRuntime.select_device(
        requested="GPU",
        available=("CPU", "GPU.0"),
        allow_cpu_fallback=True,
    )

    assert selected == "GPU"


def test_cpu_is_selected_when_gpu_is_missing_and_fallback_is_enabled() -> None:
    selected = OpenVinoRuntime.select_device(
        requested="GPU",
        available=("CPU",),
        allow_cpu_fallback=True,
    )

    assert selected == "CPU"


def test_missing_gpu_is_rejected_when_fallback_is_disabled() -> None:
    with pytest.raises(RuntimeError, match="unavailable"):
        OpenVinoRuntime.select_device(
            requested="GPU",
            available=("CPU",),
            allow_cpu_fallback=False,
        )


def test_no_openvino_devices_is_rejected() -> None:
    with pytest.raises(RuntimeError, match="no inference devices"):
        OpenVinoRuntime.select_device(
            requested="AUTO",
            available=(),
            allow_cpu_fallback=True,
        )


def test_runtime_loads_the_converted_model_on_gpu(tmp_path) -> None:
    model_dir = tmp_path / "model"
    create_converted_model_tree(model_dir)
    calls: list[str] = []

    class FakeModelFactory:
        @staticmethod
        def from_pretrained(_: str, *, device: str):
            calls.append(device)
            return object()

    helper = SimpleNamespace(
        core=FakeCore(("CPU", "GPU.0")),
        OVQwen3TTSModel=FakeModelFactory,
    )
    settings = Settings(
        _env_file=None,
        openvino_model_dir=model_dir,
        openvino_cache_dir=tmp_path / "cache",
        openvino_auto_convert=False,
    )
    runtime = OpenVinoRuntime(settings, helper_loader=lambda: helper)

    asyncio.run(runtime.initialize())
    snapshot = runtime.snapshot()

    assert snapshot.state is RuntimeState.READY
    assert snapshot.active_device == "GPU"
    assert snapshot.available_devices == ("CPU", "GPU.0")
    assert calls == ["GPU"]


def test_gpu_compilation_failure_falls_back_to_openvino_cpu(tmp_path) -> None:
    model_dir = tmp_path / "model"
    create_converted_model_tree(model_dir)
    calls: list[str] = []

    class FakeModelFactory:
        @staticmethod
        def from_pretrained(_: str, *, device: str):
            calls.append(device)
            if device == "GPU":
                raise RuntimeError("test GPU failure")
            return object()

    helper = SimpleNamespace(
        core=FakeCore(("CPU", "GPU")),
        OVQwen3TTSModel=FakeModelFactory,
    )
    settings = Settings(
        _env_file=None,
        openvino_model_dir=model_dir,
        openvino_cache_dir=tmp_path / "cache",
        openvino_auto_convert=False,
    )
    runtime = OpenVinoRuntime(settings, helper_loader=lambda: helper)

    asyncio.run(runtime.initialize())
    snapshot = runtime.snapshot()

    assert snapshot.state is RuntimeState.READY
    assert snapshot.active_device == "CPU"
    assert snapshot.fallback_reason is not None
    assert calls == ["GPU", "CPU"]


def test_missing_ir_is_converted_once_before_gpu_compilation(tmp_path) -> None:
    model_dir = tmp_path / "model"
    conversion_calls: list[tuple[str, object]] = []

    class FakeModelFactory:
        @staticmethod
        def from_pretrained(_: str, *, device: str):
            assert device == "GPU"
            return object()

    def convert(model_id: str, output_dir, *, quantization_config) -> None:
        conversion_calls.append((model_id, quantization_config))
        create_converted_model_tree(output_dir)

    helper = SimpleNamespace(
        core=FakeCore(("CPU", "GPU")),
        OVQwen3TTSModel=FakeModelFactory,
        convert_qwen3_tts_model=convert,
    )
    settings = Settings(
        _env_file=None,
        openvino_model_dir=model_dir,
        openvino_cache_dir=tmp_path / "cache",
        openvino_auto_convert=True,
    )
    runtime = OpenVinoRuntime(settings, helper_loader=lambda: helper)

    asyncio.run(runtime.initialize())

    assert runtime.snapshot().active_device == "GPU"
    assert len(conversion_calls) == 1
    assert (model_dir / "page-shelf-conversion.json").is_file()
