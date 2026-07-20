from __future__ import annotations

import uvicorn

from .api import app
from .config import Settings


def run() -> None:
    settings = Settings.from_env()
    uvicorn.run(app, host="0.0.0.0", port=settings.port)


if __name__ == "__main__":
    run()

