FROM python:3.12.10-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_DEFAULT_TIMEOUT=120 \
    TIKTOKEN_CACHE_DIR=/opt/tiktoken-cache

WORKDIR /app

RUN groupadd --system agent \
    && useradd --system --gid agent --home-dir /app agent

COPY agent-service/pyproject.toml agent-service/README.md ./
COPY agent-service/src ./src
COPY docs/help /app/help

RUN python -m pip install --no-cache-dir . \
    && mkdir -p "${TIKTOKEN_CACHE_DIR}" \
    && python -c "import tiktoken; tiktoken.get_encoding('cl100k_base')"

USER agent:agent

EXPOSE 8086

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8086/health', timeout=2)"]

ENTRYPOINT ["msb-agent-service"]
