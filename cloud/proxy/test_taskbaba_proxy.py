"""Tests for the Task Baba proxy against a fake llama.cpp upstream. Run: python3 -m unittest -v test_taskbaba_proxy"""

import json
import os
import sys
import threading
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import taskbaba_proxy as proxy  # noqa: E402

KEY = "k" * 40
UPSTREAM_KEY = "u" * 40


class FakeLlama(BaseHTTPRequestHandler):
    loading = False
    last_payload = None
    last_auth = None

    def log_message(self, *a):
        pass

    def _reply(self, status, obj):
        data = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            self._reply(503 if FakeLlama.loading else 200, {"status": "x"})

    def do_POST(self):
        FakeLlama.last_auth = self.headers.get("Authorization")
        FakeLlama.last_payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self._reply(200, {"choices": [{"message": {"role": "assistant", "content": "Namaste, deadline-dodger."}}]})


def start(server):
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    return server


class ProxyTest(unittest.TestCase):
    def setUp(self):
        self.up = start(ThreadingHTTPServer(("127.0.0.1", 0), FakeLlama))
        self.limits = proxy.Limits(per_minute=5, auth_failures_per_minute=3)
        handler = proxy.make_handler(KEY, f"http://127.0.0.1:{self.up.server_port}", UPSTREAM_KEY, self.limits, threading.BoundedSemaphore(2))
        self.px = start(ThreadingHTTPServer(("127.0.0.1", 0), handler))
        self.base = f"http://127.0.0.1:{self.px.server_port}"
        FakeLlama.loading = False

    def tearDown(self):
        for s in (self.px, self.up):
            s.shutdown()
            s.server_close()

    def call(self, method, path, body=None, key=KEY, raw=None):
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        req = urllib.request.Request(self.base + path, data=data, method=method)
        if key:
            req.add_header("Authorization", "Bearer " + key)
        req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def chat(self, **extra):
        return {"messages": [{"role": "user", "content": "hi"}], **extra}

    def test_livez_needs_no_auth_and_leaks_nothing(self):
        self.assertEqual(self.call("GET", "/livez", key=None), (200, b"ok"))

    def test_health_requires_auth_and_maps_loading(self):
        self.assertEqual(self.call("GET", "/health", key=None)[0], 401)
        self.assertEqual(self.call("GET", "/health")[0], 200)
        FakeLlama.loading = True
        self.assertEqual(self.call("GET", "/health")[0], 503)

    def test_wrong_key_rejected_and_locked_out(self):
        for _ in range(3):
            self.assertEqual(self.call("POST", "/v1/chat/completions", self.chat(), key="nope")[0], 401)
        self.assertEqual(self.call("POST", "/v1/chat/completions", self.chat(), key="nope")[0], 429)

    def test_forwards_sanitized_payload_with_upstream_key(self):
        status, body = self.call("POST", "/v1/chat/completions", self.chat(max_tokens=5000, stream=True, n=4, grammar="x", temperature=9))
        self.assertEqual(status, 200)
        self.assertIn("Namaste", json.loads(body)["choices"][0]["message"]["content"])
        p = FakeLlama.last_payload
        self.assertEqual(p["max_tokens"], 200)
        self.assertFalse(p["stream"])
        self.assertEqual(p["n"], 1)
        self.assertEqual(p["temperature"], 1.5)
        self.assertNotIn("grammar", p)
        self.assertEqual(p["chat_template_kwargs"], {"enable_thinking": False})
        self.assertEqual(FakeLlama.last_auth, "Bearer " + UPSTREAM_KEY)

    def test_rejects_oversized_and_malformed_bodies(self):
        self.assertEqual(self.call("POST", "/v1/chat/completions", raw=b"x" * (17 * 1024))[0], 413)
        self.assertEqual(self.call("POST", "/v1/chat/completions", raw=b"not json")[0], 400)
        self.assertEqual(self.call("POST", "/v1/chat/completions", {"messages": []})[0], 400)
        self.assertEqual(self.call("POST", "/v1/chat/completions", {"messages": [{"role": "tool", "content": "x"}]})[0], 400)

    def test_rate_limit(self):
        codes = [self.call("POST", "/v1/chat/completions", self.chat())[0] for _ in range(6)]
        self.assertEqual(codes[:5], [200] * 5)
        self.assertEqual(codes[5], 429)

    def test_unknown_paths_404(self):
        self.assertEqual(self.call("GET", "/slots")[0], 404)
        self.assertEqual(self.call("POST", "/completion", self.chat())[0], 404)


if __name__ == "__main__":
    unittest.main()
