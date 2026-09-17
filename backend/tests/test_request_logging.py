import os
from flask import Flask
from backend.core.request_logging import install_request_logging


def test_requests_are_logged_immediately_without_secrets(capsys):
    app = Flask(__name__)
    install_request_logging(app)

    @app.post("/probe")
    def probe():
        assert "START" in capsys.readouterr().out
        return {"ok": True}

    response = app.test_client().post(
        "/probe?token=secret-query",
        json={"password": "secret-password"},
        headers={"Authorization": "Bearer secret-token"},
    )
    output = capsys.readouterr().out
    assert "END" in output and "POST /probe status=200" in output
    assert "secret-" not in output
    assert response.headers["X-AgroControl-PID"] == str(os.getpid())
    assert response.headers["X-Request-ID"]


def test_error_responses_are_logged(capsys):
    app = Flask(__name__)
    install_request_logging(app)
    response = app.test_client().get("/missing?token=secret-query")
    output = capsys.readouterr().out
    assert response.status_code == 404
    assert "START" in output and "status=404" in output
    assert "secret-query" not in output
