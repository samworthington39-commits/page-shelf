# Third-party components

The GPU image downloads the following pinned components during `docker build`.
They are not copied into this source tree.

## OpenVINO Qwen3-TTS notebook helper

- Project: `openvinotoolkit/openvino_notebooks`
- Revision: `473c26170051836d35fd235af7561c0daac737f4`
- File: `notebooks/qwen3-tts/qwen_3_tts_helper.py`
- License: Apache License 2.0
- Source: <https://github.com/openvinotoolkit/openvino_notebooks>

The downloaded file is verified with SHA-256 during the image build.

## Intel Graphics Compute Runtime

- Package: `intel-opencl-icd-legacy1`
- Version: `24.35.30872.36`
- Project license: MIT
- Source: <https://github.com/intel/compute-runtime>

This legacy OpenCL package is used for the Gen11-class integrated graphics in
the target low-power Intel NAS.

## Intel Graphics Compiler

- Packages: `intel-igc-core`, `intel-igc-opencl`
- Version: `1.0.17537.24`
- Project license: MIT
- Source: <https://github.com/intel/intel-graphics-compiler>

All downloaded Debian packages are pinned and verified with SHA-256 during the
image build.
