from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health_returns_up():
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def test_runtime_refuses_to_start_without_a_token_unless_told_it_is_local_development():
    import pytest
    from fastapi.testclient import TestClient

    from app.config import Settings, get_settings
    from app.main import app

    try:
        app.dependency_overrides[get_settings] = lambda: Settings(_env_file=None)
        import app.main as main

        original = main.get_settings
        main.get_settings = lambda: Settings(_env_file=None)
        with pytest.raises(RuntimeError, match="RUNTIME_TOKEN is not set"):
            with TestClient(app):
                pass
        main.get_settings = lambda: Settings(_env_file=None, runtime_allow_no_token=True)
        with TestClient(app) as client:
            assert client.get("/health").status_code == 200
    finally:
        main.get_settings = original
        app.dependency_overrides.clear()
