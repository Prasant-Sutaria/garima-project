from __future__ import annotations

from pathlib import Path

import jsonschema
import yaml
from fastapi.testclient import TestClient

from model_component.api import create_app


def test_health_route_matches_committed_openapi_contract() -> None:
    app = create_app("test")
    client = TestClient(app)
    response = client.get("/api/v1/health")
    assert response.status_code == 200

    openapi = app.openapi()
    operation = openapi["paths"]["/api/v1/health"]["get"]
    response_schema = operation["responses"]["200"]["content"]["application/json"]["schema"]
    schema_with_components = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "components": openapi["components"],
        **response_schema,
    }
    jsonschema.validate(response.json(), schema_with_components)

    contract_path = Path(__file__).parents[1] / "api" / "model-api.yaml"
    assert yaml.safe_load(contract_path.read_text(encoding="utf-8")) == openapi
    assert set(openapi["paths"]) == {"/api/v1/health"}
    assert openapi["servers"][0]["description"].startswith("Internal deployment")
    assert "security" not in operation


def test_production_disables_openapi_and_interactive_docs() -> None:
    client = TestClient(create_app("prod"))

    assert client.get("/api/v1/health").status_code == 200
    assert client.get("/openapi.json").status_code == 404
    assert client.get("/docs").status_code == 404
    assert client.get("/redoc").status_code == 404
