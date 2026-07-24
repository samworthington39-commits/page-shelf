ARG PYTHON_IMAGE=python:3.12-slim
FROM ${PYTHON_IMAGE}

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app
COPY pyproject.toml ./
COPY app ./app
RUN pip install --no-cache-dir .

RUN mkdir -p /library /data/covers
ENV DATABASE_URL=sqlite:////data/bookshelf.db \
    LIBRARY_PATH=/library \
    COVER_PATH=/data/covers \
    ADMIN_CREDENTIALS_PATH=/data/admin_credentials.json

EXPOSE 8000
STOPSIGNAL SIGTERM
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
