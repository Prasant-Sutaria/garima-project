"""Minimal internal health API; participant and scoring routes are not designed."""

from __future__ import annotations

import os
from typing import Literal

from fastapi import FastAPI
from pydantic import BaseModel

from model_component import API_VERSION


class HealthResponse(BaseModel):
    status: Literal["ok"]
    service: Literal["digital-use-model"]
    api_version: Literal["1.0.0"]


def create_app(environment: str | None = None) -> FastAPI:
    env = (environment or os.getenv("APP_ENV", "dev")).lower()
    docs_enabled = env in {"dev", "test"}
    app = FastAPI(
        title="Digital-use model internal API",
        version=API_VERSION,
        description=(
            "Internal health endpoint for the offline model component. "
            "This API is for the scoring job and Spring Boot service only; "
            "the Android app never calls it. No participant data is accepted."
        ),
        contact={"name": "Model component maintainers"},
        servers=[
            {
                "url": "/",
                "description": (
                    "Internal deployment, accessible only to the scoring job and "
                    "Spring Boot service."
                ),
            }
        ],
        openapi_tags=[
            {
                "name": "health",
                "description": "Non-participant service health.",
            }
        ],
        docs_url="/docs" if docs_enabled else None,
        redoc_url="/redoc" if docs_enabled else None,
        openapi_url="/openapi.json" if docs_enabled else None,
    )

    @app.get(
        "/api/v1/health",
        response_model=HealthResponse,
        status_code=200,
        operation_id="getModelHealth",
        summary="Check model component health",
        description="Returns liveness for the model component without accepting participant data.",
        tags=["health"],
    )
    def health() -> HealthResponse:
        return HealthResponse(status="ok", service="digital-use-model", api_version=API_VERSION)

    return app


app = create_app()
