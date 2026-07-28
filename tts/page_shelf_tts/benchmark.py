import argparse
import json
import os
import statistics
import urllib.request

DEFAULT_TEXT = "你好，这是一段用于测试页架在线语音速度的文本。"


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Benchmark Page Shelf TTS and report real-time factor."
    )
    parser.add_argument(
        "--url",
        default="http://127.0.0.1:8010/v1/audio/speech",
    )
    parser.add_argument("--text", default=DEFAULT_TEXT)
    parser.add_argument("--voice", default="Serena")
    parser.add_argument("--language", default="Chinese")
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument(
        "--api-key",
        default=os.environ.get("TTS_API_KEY", ""),
    )
    args = parser.parse_args()
    if args.runs < 1:
        parser.error("--runs must be at least 1")

    rtfs: list[float] = []
    for run in range(1, args.runs + 1):
        body = json.dumps(
            {
                "input": args.text,
                "voice": args.voice,
                "language": args.language,
                "seed": run,
            }
        ).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if args.api_key:
            headers["Authorization"] = f"Bearer {args.api_key}"
        request = urllib.request.Request(
            args.url,
            data=body,
            headers=headers,
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=1800) as response:
            response.read()
            result = {
                "run": run,
                "runtime": response.headers["X-TTS-Runtime"],
                "device": response.headers["X-TTS-Device"],
                "generation_ms": int(response.headers["X-TTS-Generation-Ms"]),
                "audio_ms": int(response.headers["X-TTS-Duration-Ms"]),
                "rtf": float(response.headers["X-TTS-RTF"]),
            }
        rtfs.append(result["rtf"])
        print(json.dumps(result, ensure_ascii=False))

    print(
        json.dumps(
            {
                "runs": args.runs,
                "median_rtf": round(statistics.median(rtfs), 3),
                "realtime": statistics.median(rtfs) <= 1.0,
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
