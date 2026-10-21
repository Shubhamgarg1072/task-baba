#!/usr/bin/env python3
"""Task Baba inference proxy.

A small, dependency-free gatekeeper in front of a llama.cpp server bound to 127.0.0.1.
Only this proxy is exposed (through a Cloudflare tunnel in Colab, or a load balancer elsewhere).

It enforces:
  * Bearer API key (constant-time comparison) on every data endpoint
  * request body <= 16 KB, <= 8 messages, max_tokens <= 200, non-streaming, n = 1
  * a whitelist of request fields (everything else is dropped)
  * per-minute rate limits (overall and for failed auth attempts per client)
  * a small concurrency cap with a bounded wait
  * no logging of prompts, responses, headers or keys: only method, path, status and latency

Endpoints:
  GET  /livez                  -> 200 "ok" (no auth, no data; for uptime checks)
  GET  /health                 -> 200 when the model is loaded, 503 while loading (auth required)
  POST /v1/chat/completions    -> forwarded to llama.cpp (auth required)

Configuration (environment):
  TB_API_KEY        required; the key clients must send
  TB_UPSTREAM       default http://127.0.0.1:8081
  TB_UPSTREAM_KEY   key for the llama.cpp server's own --api-key (defense in depth)
  TB_LISTEN_HOST    default 127.0.0.1
  TB_LISTEN_PORT    default 8080
  TB_RATE_PER_MIN   default 30
  TB_MAX_CONCURRENT default 2
"""

import hmac
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import defaultdict, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY = 16 * 1024
MAX_MESSAGES = 8
MAX_CONTENT_CHARS = 8000
MAX_TOKENS_CAP = 200
ALLOWED_FIELDS = {"messages", "max_tokens", "temperature", "top_p", "chat_template_kwargs", "model"}
ALLOWED_ROLES = {"system", "user", "assistant"}


class Limits:
    def __init__(self, per_minute: int, auth_failures_per_minute: int = 10):
        self.per_minute = per_minute
        self.auth_failures_per_minute = auth_failures_per_minute
        self._lock = threading.Lock()
        self._requests = deque()
        self._failures = defaultdict(deque)

    @staticmethod
    def _prune(q, now):
        while q and now - q[0] > 60:
            q.popleft()

    def allow_request(self) -> bool:
        now = time.monotonic()
        with self._lock:
            self._prune(self._requests, now)
            if len(self._requests) >= self.per_minute:
                return False
            self._requests.append(now)
            return True

    def auth_blocked(self, client: str) -> bool:
        now = time.monotonic()
        with self._lock:
            q = self._failures[client]
            self._prune(q, now)
            return len(q) >= self.auth_failures_per_minute

    def record_auth_failure(self, client: str):
        with self._lock:
            self._failures[client].append(time.monotonic())


def sanitize_request(raw: bytes):
    """Return (payload, error). The payload only contains whitelisted, bounded fields."""
    try:
        body = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None, "body must be JSON"
    if not isinstance(body, dict):
        return None, "body must be a JSON object"
    messages = body.get("messages")
    if not isinstance(messages, list) or not (1 <= len(messages) <= MAX_MESSAGES):
        return None, f"messages must be a list of 1-{MAX_MESSAGES} items"
    total = 0
    clean_messages = []
    for m in messages:
        if not isinstance(m, dict) or m.get("role") not in ALLOWED_ROLES or not isinstance(m.get("content"), str):
            return None, "each message needs a role and string content"
        total += len(m["content"])
        clean_messages.append({"role": m["role"], "content": m["content"]})
    if total > MAX_CONTENT_CHARS:
        return None, "messages too long"
    payload = {k: v for k, v in body.items() if k in ALLOWED_FIELDS}
    payload["messages"] = clean_messages
    try:
        max_tokens = int(payload.get("max_tokens", 80))
    except (TypeError, ValueError):
        return None, "max_tokens must be an integer"
    payload["max_tokens"] = max(1, min(max_tokens, MAX_TOKENS_CAP))
    try:
        payload["temperature"] = max(0.0, min(float(payload.get("temperature", 0.8)), 1.5))
    except (TypeError, ValueError):
        return None, "temperature must be a number"
    if "top_p" in payload:
        try:
            payload["top_p"] = max(0.0, min(float(payload["top_p"]), 1.0))
        except (TypeError, ValueError):
            payload.pop("top_p")
    kwargs = payload.get("chat_template_kwargs")
    payload["chat_template_kwargs"] = {"enable_thinking": bool(kwargs.get("enable_thinking", False))} if isinstance(kwargs, dict) else {"enable_thinking": False}
    payload["stream"] = False
    payload["n"] = 1
    return payload, None


def make_handler(api_key: str, upstream: str, upstream_key: str, limits: Limits, slots: threading.BoundedSemaphore):
    expected = ("Bearer " + api_key).encode()

    class Handler(BaseHTTPRequestHandler):
        server_version = "TaskBabaProxy/1.0"
        sys_version = ""
        protocol_version = "HTTP/1.1"

        # Only method, path (no query), status and latency are logged.
        def log_message(self, fmt, *args):
            pass

        def _log(self, status, started):
            path = self.path.split("?", 1)[0]
            sys.stderr.write(f"{self.command} {path} {status} {int((time.monotonic() - started) * 1000)}ms\n")

        def _client(self):
            return self.headers.get("CF-Connecting-IP") or self.client_address[0]

        def _send(self, status, obj=None, text=None, started=None):
            data = text.encode() if text is not None else json.dumps(obj or {}).encode()
            self.send_response(status)
            self.send_header("Content-Type", "text/plain" if text is not None else "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)
            if started is not None:
                self._log(status, started)

        def _authorized(self, started) -> bool:
            client = self._client()
            if limits.auth_blocked(client):
                self._send(429, {"error": "too many failed attempts"}, started=started)
                return False
            got = (self.headers.get("Authorization") or "").encode()
            if not hmac.compare_digest(got, expected):
                limits.record_auth_failure(client)
                self._send(401, {"error": "unauthorized"}, started=started)
                return False
            return True

        def _upstream(self, method, path, body=None, timeout=90):
            req = urllib.request.Request(upstream + path, data=body, method=method)
            req.add_header("Content-Type", "application/json")
            if upstream_key:
                req.add_header("Authorization", "Bearer " + upstream_key)
            return urllib.request.urlopen(req, timeout=timeout)

        def do_GET(self):
            started = time.monotonic()
            path = self.path.split("?", 1)[0]
            if path == "/livez":
                return self._send(200, text="ok", started=started)
            if path != "/health":
                return self._send(404, {"error": "not found"}, started=started)
            if not self._authorized(started):
                return
            try:
                with self._upstream("GET", "/health", timeout=5) as r:
                    status = r.status
            except urllib.error.HTTPError as e:
                status = e.code
            except Exception:
                status = 502
            if status == 200:
                self._send(200, {"status": "ok"}, started=started)
            elif status == 503:
                self._send(503, {"status": "loading"}, started=started)
            else:
                self._send(502, {"status": "upstream unavailable"}, started=started)

        def do_POST(self):
            started = time.monotonic()
            path = self.path.split("?", 1)[0]
            if path != "/v1/chat/completions":
                return self._send(404, {"error": "not found"}, started=started)
            if not self._authorized(started):
                return
            try:
                length = int(self.headers.get("Content-Length") or "-1")
            except ValueError:
                length = -1
            if length < 0:
                return self._send(411, {"error": "content-length required"}, started=started)
            if length > MAX_BODY:
                return self._send(413, {"error": "request too large"}, started=started)
            raw = self.rfile.read(length)
            payload, err = sanitize_request(raw)
            if err:
                return self._send(400, {"error": err}, started=started)
            if not limits.allow_request():
                return self._send(429, {"error": "rate limit"}, started=started)
            if not slots.acquire(timeout=20):
                return self._send(503, {"error": "busy"}, started=started)
            try:
                with self._upstream("POST", "/v1/chat/completions", json.dumps(payload).encode()) as r:
                    data = r.read(256 * 1024)
                    status = r.status
            except urllib.error.HTTPError as e:
                status = 503 if e.code == 503 else 502
                data = json.dumps({"error": "upstream error"}).encode()
            except Exception:
                status, data = 504, json.dumps({"error": "upstream timeout"}).encode()
            finally:
                slots.release()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)
            self._log(status, started)

    return Handler


def main():
    api_key = os.environ.get("TB_API_KEY", "")
    if len(api_key) < 24:
        sys.exit("TB_API_KEY must be set to a long random value")
    upstream = os.environ.get("TB_UPSTREAM", "http://127.0.0.1:8081").rstrip("/")
    upstream_key = os.environ.get("TB_UPSTREAM_KEY", "")
    host = os.environ.get("TB_LISTEN_HOST", "127.0.0.1")
    port = int(os.environ.get("TB_LISTEN_PORT", "8080"))
    limits = Limits(int(os.environ.get("TB_RATE_PER_MIN", "30")))
    slots = threading.BoundedSemaphore(int(os.environ.get("TB_MAX_CONCURRENT", "2")))
    server = ThreadingHTTPServer((host, port), make_handler(api_key, upstream, upstream_key, limits, slots))
    server.daemon_threads = True
    sys.stderr.write(f"Task Baba proxy listening on {host}:{port} -> {upstream}\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
