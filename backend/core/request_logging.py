import logging
import os
import sys
import time
import uuid

from flask import g, request


def install_request_logging(app):
    logger = logging.getLogger("agrocontrol.http")
    logger.disabled = False
    logger.setLevel(logging.INFO)
    logger.propagate = False
    for handler in logger.handlers[:]:
        logger.removeHandler(handler)
        handler.close()
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(logging.Formatter("%(asctime)s %(message)s", "%H:%M:%S"))
    logger.addHandler(handler)

    @app.before_request
    def request_started():
        g.http_started = time.perf_counter()
        g.http_id = uuid.uuid4().hex[:8]
        g.http_route = request.url_rule.rule if request.url_rule else "<unmatched>"
        logger.info(
            "START pid=%s id=%s %s %s",
            os.getpid(),
            g.http_id,
            request.method,
            g.http_route,
        )

    @app.after_request
    def request_finished(response):
        elapsed = (time.perf_counter() - g.http_started) * 1000
        logger.info(
            "END   pid=%s id=%s %s %s status=%s time=%.0fms",
            os.getpid(),
            g.http_id,
            request.method,
            g.http_route,
            response.status_code,
            elapsed,
        )
        response.headers["X-Request-ID"] = g.http_id
        response.headers["X-AgroControl-PID"] = str(os.getpid())
        return response

    logger.info(
        "HTTP logging enabled pid=%s; START and END are printed for each request",
        os.getpid(),
    )
