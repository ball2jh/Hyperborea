#!/usr/bin/env python3
"""
Local HTTP server for testing the Hyperborea in-app update system.

Serves a dynamically-generated manifest.json alongside static update files
(APKs, ZIPs). The manifest is built from update.json config with SHA-256
hashes computed at request time.

Usage: python3 serve.py [--port 8080] [--host 192.168.1.x] [--dir /path]
"""

import argparse
import hashlib
import http.server
import json
import os
import socket
import sys


def detect_lan_ip():
    """Detect LAN IP via UDP socket trick (no traffic sent)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))
            return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"


def sha256_file(path):
    """Compute SHA-256 hex digest of a file."""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()


def build_manifest(config_path, base_url):
    """Build manifest JSON from update.json config.

    Computes SHA-256 of each referenced file and constructs download URLs.
    Sections with missing files are omitted with a warning.
    """
    with open(config_path) as f:
        config = json.load(f)

    directory = os.path.dirname(config_path)
    manifest = {}

    for section in ("app", "firmware"):
        entry = config.get(section)
        if entry is None:
            continue

        filename = entry["file"]
        filepath = os.path.join(directory, filename)

        if not os.path.isfile(filepath):
            print(f"WARNING: {section}.file '{filename}' not found, omitting from manifest")
            continue

        section_data = {
            "url": f"{base_url}/{filename}",
            "sha256": sha256_file(filepath),
        }

        if section == "app":
            section_data["versionCode"] = entry["versionCode"]
            section_data["versionName"] = entry["versionName"]
        elif section == "firmware":
            section_data["version"] = entry["version"]

        if "releaseNotes" in entry:
            section_data["releaseNotes"] = entry["releaseNotes"]

        manifest[section] = section_data

    return manifest


class UpdateHandler(http.server.SimpleHTTPRequestHandler):
    """HTTP handler that generates manifest.json dynamically."""

    def do_GET(self):
        if self.path == "/manifest.json":
            self.serve_manifest()
        else:
            super().do_GET()

    def serve_manifest(self):
        config_path = os.path.join(self.directory, "update.json")

        if not os.path.isfile(config_path):
            self.send_error(
                404,
                "update.json not found. Copy update.json.example to update.json "
                "and edit it for your test scenario.",
            )
            return

        try:
            manifest = build_manifest(config_path, self.server.base_url)
        except (json.JSONDecodeError, KeyError) as e:
            self.send_error(500, f"Error reading update.json: {e}")
            return

        body = json.dumps(manifest, indent=2).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        print(f"[{self.log_date_time_string()}] {format % args}")


def main():
    parser = argparse.ArgumentParser(description="Hyperborea update test server")
    parser.add_argument("--port", type=int, default=8080, help="Port to listen on (default: 8080)")
    parser.add_argument("--host", type=str, default=None, help="LAN IP override for manifest URLs")
    parser.add_argument("--dir", type=str, default=".", help="Directory to serve from (default: cwd)")
    args = parser.parse_args()

    host = args.host or detect_lan_ip()
    base_url = f"http://{host}:{args.port}"
    serve_dir = os.path.abspath(args.dir)

    server = http.server.HTTPServer(("0.0.0.0", args.port), UpdateHandler)
    server.base_url = base_url

    # Set the directory for the handler
    UpdateHandler.directory = serve_dir

    manifest_url = f"{base_url}/manifest.json"
    print(f"Serving from: {serve_dir}")
    print(f"Manifest URL: {manifest_url}")
    print()
    print(f"Add to local.properties:")
    print(f"  update.manifest.url={manifest_url}")
    print()

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down.")
        server.shutdown()


if __name__ == "__main__":
    main()
