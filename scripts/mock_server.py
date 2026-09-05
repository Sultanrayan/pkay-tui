#!/usr/bin/env python3
"""Mock provider API server for local testing of the API Pooling proxy.

Echoes back the method, path, headers and body it received as JSON, so you can
verify the proxy forwarded the request correctly (including the injected
provider key in the Authorization header).

Usage:
    python mock_server.py [port]     (default port: 9999)
"""
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        # /sse emits events slowly, so a streaming proxy relays them as they
        # are produced (and a buffering proxy delivers them all at the end).
        if self.path.startswith("/sse"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            for i in range(5):
                payload = f"data: event {i} at {time.time():.3f}\n\n".encode()
                self.wfile.write(payload)
                self.wfile.flush()
                time.sleep(0.5)
            self.wfile.write(b"data: [done]\n\n")
            self.wfile.flush()
            return
        self._handle()

    def _handle(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        body = self.rfile.read(length).decode("utf-8", errors="replace") if length else ""
        payload = {
            "service": "mock-provider",
            "method": self.command,
            "path": self.path,
            "headers": {k: v for k, v in self.headers.items()},
            "body": body,
        }
        data = json.dumps(payload, indent=2).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        self._handle()

    def do_PUT(self):
        self._handle()

    def do_PATCH(self):
        self._handle()

    def do_DELETE(self):
        self._handle()

    def do_OPTIONS(self):
        self._handle()

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9999
    print(f"mock provider listening on http://127.0.0.1:{port}")
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()