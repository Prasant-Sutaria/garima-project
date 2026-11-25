FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

RUN useradd --uid 10001 --create-home app

WORKDIR /app
COPY pyproject.toml README.md ./
COPY src ./src
COPY scripts ./scripts
RUN pip install .

RUN mkdir -p /output && chown app:app /output
VOLUME ["/output"]

USER app
CMD ["python", "scripts/run_usage_patterns.py", "--output-dir", "/output"]
