#!/usr/bin/env python3
"""
Mock server for testing the device-initiated pairing flow.

Handles:
  POST /api/device/pair         → returns pairingToken + pairingCode + expiresAt
  GET  /api/device/pair/status  → returns pending, then linked after confirmation
  GET  /api/device/status       → returns a signed license status (optional)

Usage:
  python3 tools/mock-pairing-server.py [--port 8080] [--auto-link SECONDS]

  --auto-link N   Automatically mark pairing as linked after N seconds (default: off, manual)

Without --auto-link, press Enter in the terminal to simulate a user confirming the pairing.

Set in local.properties:
  server.url=http://<YOUR_IP>:8080
"""

import argparse
import json
import os
import secrets
import threading
import time
from http.server import HTTPServer, BaseHTTPRequestHandler

# State
pairing_requests: dict[str, dict] = {}
lock = threading.Lock()


class PairingHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path == "/api/device/pair":
            self.handle_pair()
        else:
            self.send_error(404)

    def do_GET(self):
        if self.path.startswith("/api/device/pair/status"):
            self.handle_pair_status()
        elif self.path.startswith("/api/device/status"):
            self.handle_device_status()
        else:
            self.send_error(404)

    def handle_pair(self):
        body = self._read_json()
        if not body or "deviceUuid" not in body:
            self._json_response({"error": "deviceUuid is required"}, 400)
            return

        device_uuid = body["deviceUuid"]

        with lock:
            # Idempotency: return existing pending request
            for req in pairing_requests.values():
                if req["deviceUuid"] == device_uuid and req["status"] == "pending" and req["expiresAt"] > time.time() * 1000:
                    self._json_response({
                        "pairingToken": req["pairingToken"],
                        "pairingCode": req["pairingCode"],
                        "expiresAt": req["expiresAt"],
                    })
                    return

            token = secrets.token_hex(32)
            code = f"{secrets.randbelow(1_000_000):06d}"
            expires_at = int((time.time() + 600) * 1000)  # 10 minutes

            pairing_requests[token] = {
                "deviceUuid": device_uuid,
                "pairingToken": token,
                "pairingCode": code,
                "status": "pending",
                "authToken": None,
                "expiresAt": expires_at,
            }

        print(f"\n{'='*60}")
        print(f"  New pairing request from device: {device_uuid}")
        print(f"  Code: {code[:3]} {code[3:]}")
        print(f"  Token: {token[:16]}...")
        if not auto_link_seconds:
            print(f"  Press Enter to simulate user confirming...")
        else:
            print(f"  Auto-linking in {auto_link_seconds}s...")
        print(f"{'='*60}\n")

        if auto_link_seconds:
            threading.Timer(auto_link_seconds, lambda: _auto_confirm(token)).start()

        self._json_response({
            "pairingToken": token,
            "pairingCode": code,
            "expiresAt": expires_at,
        })

    def handle_pair_status(self):
        # Parse ?token=...
        from urllib.parse import urlparse, parse_qs
        query = parse_qs(urlparse(self.path).query)
        token = query.get("token", [None])[0]

        if not token:
            self._json_response({"error": "token required"}, 400)
            return

        with lock:
            req = pairing_requests.get(token)

        if not req:
            self._json_response({"status": "expired"})
            return

        if req["status"] == "linked":
            print(f"  [poll] Device received authToken for {token[:16]}...")
            self._json_response({"status": "linked", "authToken": req["authToken"]})
            return

        if req["expiresAt"] <= time.time() * 1000:
            self._json_response({"status": "expired"})
            return

        self._json_response({"status": "pending"})

    def handle_device_status(self):
        # Minimal: return active license so the app proceeds after linking
        auth_header = self.headers.get("Authorization", "")
        if not auth_header.startswith("Bearer "):
            self._json_response({"error": "unauthorized"}, 401)
            return

        # Return an unsigned response — the app will fail signature verification
        # and fall back to cache. To make it work fully, we'd need Ed25519 signing.
        # For testing the pairing UI flow, the app just needs to get past the linking.
        # After linking, it will call check() which will hit this endpoint.
        # Since we can't sign, return null-ish so it falls back to cached state.
        self.send_error(503, "Mock server: license status not implemented (use cached state)")

    def _read_json(self) -> dict | None:
        length = int(self.headers.get("Content-Length", 0))
        if length == 0:
            return None
        try:
            return json.loads(self.rfile.read(length))
        except json.JSONDecodeError:
            return None

    def _json_response(self, data: dict, status: int = 200):
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        # Quieter logging — only show method + path
        print(f"  [{self.command}] {self.path}")


def _auto_confirm(token: str):
    with lock:
        req = pairing_requests.get(token)
        if req and req["status"] == "pending":
            req["status"] = "linked"
            req["authToken"] = secrets.token_hex(32)
            print(f"\n  Auto-confirmed pairing for {token[:16]}...")
            print(f"  authToken: {req['authToken'][:16]}...\n")


def _manual_confirm_loop():
    """Wait for Enter keypresses to confirm the most recent pending request."""
    while True:
        input()
        with lock:
            pending = [r for r in pairing_requests.values() if r["status"] == "pending"]
            if not pending:
                print("  No pending pairing requests.")
                continue
            req = pending[-1]  # most recent
            req["status"] = "linked"
            req["authToken"] = secrets.token_hex(32)
            print(f"\n  Confirmed pairing for code {req['pairingCode']}")
            print(f"  authToken: {req['authToken'][:16]}...\n")


def main():
    global auto_link_seconds

    parser = argparse.ArgumentParser(description="Mock pairing server for Hyperborea testing")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--auto-link", type=int, default=0,
                        help="Auto-confirm pairing after N seconds (0 = manual, press Enter)")
    args = parser.parse_args()

    auto_link_seconds = args.auto_link

    server = HTTPServer(("0.0.0.0", args.port), PairingHandler)
    print(f"Mock pairing server on port {args.port}")
    print(f"Set in local.properties: server.url=http://<YOUR_IP>:{args.port}")
    if not auto_link_seconds:
        print("Press Enter to confirm pairing requests.\n")
        threading.Thread(target=_manual_confirm_loop, daemon=True).start()
    else:
        print(f"Auto-confirming after {auto_link_seconds}s.\n")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped.")


auto_link_seconds = 0

if __name__ == "__main__":
    main()
