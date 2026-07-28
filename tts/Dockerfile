# syntax=docker/dockerfile:1
ARG UBUNTU_IMAGE=ubuntu:22.04
FROM ${UBUNTU_IMAGE}

ARG TORCH_VERSION=2.8.0
ARG TORCH_INDEX_URL=https://download.pytorch.org/whl/cpu
ARG INTEL_COMPUTE_RUNTIME_VERSION=24.35.30872.36
ARG INTEL_IGC_VERSION=1.0.17537.24
ARG OPENVINO_NOTEBOOKS_REVISION=473c26170051836d35fd235af7561c0daac737f4
ARG OPENVINO_HELPER_SHA256=d054da021f701707072d0b8c7b68fe476a2db20555a5f8b5bbd723af496991c8

ENV DEBIAN_FRONTEND=noninteractive \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/models/huggingface \
    TORCH_HOME=/models/torch \
    XDG_CACHE_HOME=/models/cache \
    NUMBA_CACHE_DIR=/models/numba \
    TTS_OPENVINO_HELPER_PATH=/opt/qwen-openvino/qwen_3_tts_helper.py

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        ca-certificates \
        clinfo \
        curl \
        libsndfile1 \
        libsox-fmt-all \
        ocl-icd-libopencl1 \
        python3 \
        python3-pip \
        python3-venv \
        sox \
    && rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    driver_dir=/tmp/intel-opencl; \
    mkdir -p "${driver_dir}"; \
    curl --fail --location --show-error \
        "https://github.com/intel/intel-graphics-compiler/releases/download/igc-${INTEL_IGC_VERSION}/intel-igc-core_${INTEL_IGC_VERSION}_amd64.deb" \
        --output "${driver_dir}/intel-igc-core.deb"; \
    curl --fail --location --show-error \
        "https://github.com/intel/intel-graphics-compiler/releases/download/igc-${INTEL_IGC_VERSION}/intel-igc-opencl_${INTEL_IGC_VERSION}_amd64.deb" \
        --output "${driver_dir}/intel-igc-opencl.deb"; \
    curl --fail --location --show-error \
        "https://github.com/intel/compute-runtime/releases/download/${INTEL_COMPUTE_RUNTIME_VERSION}/libigdgmm12_22.5.0_amd64.deb" \
        --output "${driver_dir}/libigdgmm12.deb"; \
    curl --fail --location --show-error \
        "https://github.com/intel/compute-runtime/releases/download/${INTEL_COMPUTE_RUNTIME_VERSION}/intel-opencl-icd-legacy1_${INTEL_COMPUTE_RUNTIME_VERSION}_amd64.deb" \
        --output "${driver_dir}/intel-opencl-icd-legacy1.deb"; \
    echo "c1e1ecdfe2064c047c552651cfdcdafc504f2033afafba65654338b880048b67  ${driver_dir}/intel-igc-core.deb" | sha256sum --check; \
    echo "dd016400f87fa2b6a9fa9fbcca7eb4a2629174a29de679709f9bec5cede88b0e  ${driver_dir}/intel-igc-opencl.deb" | sha256sum --check; \
    echo "cc29d14df83cff1b3c6a66baa39257f0211b168ab43a99c2dc62a3734431bc23  ${driver_dir}/libigdgmm12.deb" | sha256sum --check; \
    echo "bbe71e4f414259e06a10cde72c29a2bd78d41b2bb2f6f8463b1806797fe66e85  ${driver_dir}/intel-opencl-icd-legacy1.deb" | sha256sum --check; \
    apt-get update; \
    apt-get install --yes --no-install-recommends \
        "${driver_dir}/intel-igc-core.deb" \
        "${driver_dir}/intel-igc-opencl.deb" \
        "${driver_dir}/libigdgmm12.deb" \
        "${driver_dir}/intel-opencl-icd-legacy1.deb"; \
    rm -rf "${driver_dir}" /var/lib/apt/lists/*

RUN mkdir -p /opt/qwen-openvino \
    && curl --fail --location --show-error \
        "https://raw.githubusercontent.com/openvinotoolkit/openvino_notebooks/${OPENVINO_NOTEBOOKS_REVISION}/notebooks/qwen3-tts/qwen_3_tts_helper.py" \
        --output /opt/qwen-openvino/qwen_3_tts_helper.py \
    && echo "${OPENVINO_HELPER_SHA256}  /opt/qwen-openvino/qwen_3_tts_helper.py" \
        | sha256sum --check

WORKDIR /app

COPY pyproject.toml ./
COPY page_shelf_tts ./page_shelf_tts

RUN python3 -m pip install --upgrade pip setuptools wheel \
    && python3 -m pip install \
        --index-url "${TORCH_INDEX_URL}" \
        "torch==${TORCH_VERSION}" \
        "torchaudio==${TORCH_VERSION}" \
    && python3 -m pip install ".[qwen,openvino]"

RUN groupadd --system --gid 10001 tts \
    && useradd --system --uid 10001 --gid tts --home-dir /nonexistent tts \
    && mkdir -p \
        /models/huggingface \
        /models/torch \
        /models/cache \
        /models/numba \
        /models/openvino \
        /models/openvino-cache \
    && chown -R tts:tts /models

USER 10001:10001

EXPOSE 8010

CMD ["uvicorn", "page_shelf_tts.main:app", "--host", "0.0.0.0", "--port", "8010", "--workers", "1", "--no-server-header"]
