from __future__ import annotations

import argparse
import asyncio
import base64
import mimetypes
import sys
import uuid
from pathlib import Path

from .config import Settings
from .errors import LlmProviderError
from .provider import OpenAIProvider

MAX_IMAGE_BYTES = 10 * 1024 * 1024
ALLOWED_IMAGE_TYPES = {"image/jpeg", "image/png", "image/webp"}


async def _execute(args: argparse.Namespace) -> int:
    provider = OpenAIProvider(Settings.from_env())
    correlation_id = str(uuid.uuid4())
    try:
        if args.mode == "text":
            result = await provider.structured_text(
                "This demo listing is a walnut writing desk. The price is negotiable.",
                correlation_id=correlation_id,
            )
        elif args.mode == "image":
            result = await provider.structured_image(
                _image_data_url(args.image), correlation_id=correlation_id
            )
        else:
            result = await provider.function_tool(correlation_id=correlation_id)
    except (LlmProviderError, ValueError) as error:
        code = getattr(error, "code", "INVALID_SMOKE_INPUT")
        code_text = code.value if hasattr(code, "value") else str(code)
        print(f"Smoke check failed: {code_text}", file=sys.stderr)
        return 2

    print(result.model_dump_json(indent=2))
    return 0


def _image_data_url(image_path: str | None) -> str:
    if image_path is None:
        raise ValueError("--image is required for image mode")
    path = Path(image_path)
    content_type, _ = mimetypes.guess_type(path.name)
    if content_type not in ALLOWED_IMAGE_TYPES:
        raise ValueError("image must be JPEG, PNG, or WebP")
    image_bytes = path.read_bytes()
    if not image_bytes or len(image_bytes) > MAX_IMAGE_BYTES:
        raise ValueError("image must be between 1 byte and 10 MiB")
    encoded = base64.b64encode(image_bytes).decode("ascii")
    return f"data:{content_type};base64,{encoded}"


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run an operator-only OpenAI smoke check")
    parser.add_argument("mode", choices=("text", "image", "tool"))
    parser.add_argument("--image", help="Local JPEG, PNG, or WebP path for image mode")
    return parser


def run() -> None:
    raise SystemExit(asyncio.run(_execute(_parser().parse_args())))


if __name__ == "__main__":
    run()

