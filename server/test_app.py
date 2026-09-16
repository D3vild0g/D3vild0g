import os

from fastapi.testclient import TestClient

# Make sure no real key leaks in from the environment running the tests.
os.environ.pop("SEARCH_API_KEY", None)

from app import app  # noqa: E402  (import after env cleanup, deliberately)

client = TestClient(app)


def test_health():
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}


def test_betting_sentiment_not_configured():
    resp = client.get(
        "/betting-sentiment", params={"league": "NHL", "home": "TOR", "away": "MTL"}
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["available"] is False
    assert "reason" in body
    assert "SEARCH_API_KEY" in body["reason"]
