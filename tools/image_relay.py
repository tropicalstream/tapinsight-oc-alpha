#!/usr/bin/env python3
"""
TapClaw Media Relay — Receives camera frames from AR glasses and serves
media files from the OpenClaw workspace.

The OpenClaw gateway strips binary attachments from WebSocket RPC calls.
This lightweight HTTP relay bypasses that limitation:
  1. Glasses POST a JPEG to http://<mac-ip>:18790/frame
  2. Relay saves it to ~/.openclaw/workspace/camera_frame.jpg
  3. Glasses send text-only agent call: "analyze camera_frame.jpg in workspace"
  4. OpenClaw agent reads the file and responds with vision analysis

Media serving (audio, video, images):
  - GET /media/<filename>  — serves any file from the workspace directory
  - OpenClaw agent saves a file to workspace, then tells glasses to open
    http://<relay>/media/<filename> via open_taplink
  - TapBrowser auto-detects audio/video extensions and opens the media player

Usage:
    python3 image_relay.py                   # default port 18790
    python3 image_relay.py --port 18791      # custom port
    python3 image_relay.py --workspace /path # custom workspace dir

Runs on the same Mac as OpenClaw. Bind to 0.0.0.0 so the glasses can reach it
over the local network.
"""

import argparse
import io
import mimetypes
import os
import sys
import time
from datetime import datetime
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import unquote

try:
    from PIL import Image
    HAS_PIL = True
except ImportError:
    HAS_PIL = False

DEFAULT_PORT = 18790
DEFAULT_WORKSPACE = os.path.expanduser("~/.openclaw/workspace")
FRAME_FILENAME = "camera_frame.jpg"
ROTATION_DEGREES = 90  # Rotate clockwise to make landscape frames upright


class RelayHandler(BaseHTTPRequestHandler):
    workspace = DEFAULT_WORKSPACE

    def do_POST(self):
        if self.path not in ("/frame", "/upload"):
            self.send_error(404, "Use POST /frame")
            return

        content_length = int(self.headers.get("Content-Length", 0))
        if content_length == 0:
            self.send_error(400, "Empty body")
            return

        if content_length > 10 * 1024 * 1024:  # 10MB limit
            self.send_error(413, "Image too large (max 10MB)")
            return

        body = self.rfile.read(content_length)

        # Validate it looks like a JPEG
        if not body[:2] == b"\xff\xd8":
            self.send_error(400, "Not a valid JPEG (missing FFD8 header)")
            return

        # Rotate the image 90° clockwise so landscape frames appear upright
        if HAS_PIL:
            try:
                img = Image.open(io.BytesIO(body))
                img = img.rotate(-ROTATION_DEGREES, expand=True)
                buf = io.BytesIO()
                img.save(buf, format="JPEG", quality=85)
                image_bytes = buf.getvalue()
            except Exception as e:
                print(f"[WARN] PIL rotation failed, saving raw: {e}")
                image_bytes = body
        else:
            image_bytes = body

        save_path = os.path.join(self.workspace, FRAME_FILENAME)
        try:
            with open(save_path, "wb") as f:
                f.write(image_bytes)
            size_kb = len(image_bytes) / 1024
            print(f"[{time.strftime('%H:%M:%S')}] Saved {size_kb:.0f}KB frame -> {save_path}")

            # Also save a permanent copy in dated archive folder
            archive_dir = os.path.join(
                self.workspace,
                "saved_photos",
                datetime.now().strftime('%Y-%m-%d')
            )
            os.makedirs(archive_dir, exist_ok=True)
            timestamp = datetime.now().strftime("%H%M%S")
            archive_path = os.path.join(archive_dir, f"frame_{timestamp}.jpg")
            with open(archive_path, "wb") as f:
                f.write(image_bytes)
            print(f"[{time.strftime('%H:%M:%S')}] Archived -> {archive_path}")

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(
                f'{{"ok":true,"path":"{save_path}","archive":"{archive_path}","size":{len(image_bytes)}}}'.encode()
            )
        except Exception as e:
            print(f"[ERROR] Failed to save frame: {e}")
            self.send_error(500, f"Write failed: {e}")

    def do_OPTIONS(self):
        """CORS preflight for cross-origin requests from the companion app."""
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        """Health check, status, image serving, and media file serving."""
        # Strip query string for path matching
        clean_path = self.path.split("?")[0]

        if clean_path == "/latest":
            # Serve the latest camera frame as JPEG for the companion app gallery
            frame_path = os.path.join(self.workspace, FRAME_FILENAME)
            if not os.path.exists(frame_path):
                self.send_error(404, "No frame available")
                return
            try:
                with open(frame_path, "rb") as f:
                    data = f.read()
                self.send_response(200)
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(data)
            except Exception as e:
                self.send_error(500, f"Read failed: {e}")
            return

        if clean_path == "/status":
            frame_path = os.path.join(self.workspace, FRAME_FILENAME)
            exists = os.path.exists(frame_path)
            size = os.path.getsize(frame_path) if exists else 0
            mtime = os.path.getmtime(frame_path) if exists else 0
            age = int(time.time() - mtime) if exists else -1

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(
                f'{{"ok":true,"hasFrame":{str(exists).lower()},"sizeBytes":{size},"ageSeconds":{age}}}'.encode()
            )
            return

        if clean_path.startswith("/media/"):
            # Serve any file from the workspace directory.
            # Security: only serve files directly inside workspace (no ../ traversal).
            filename = unquote(clean_path[len("/media/"):])
            if not filename or ".." in filename or filename.startswith("/"):
                self.send_error(400, "Invalid filename")
                return
            file_path = os.path.join(self.workspace, filename)
            # Ensure the resolved path is still inside the workspace
            real_workspace = os.path.realpath(self.workspace)
            real_file = os.path.realpath(file_path)
            if not real_file.startswith(real_workspace + os.sep) and real_file != real_workspace:
                self.send_error(403, "Access denied — path outside workspace")
                return
            if not os.path.isfile(file_path):
                self.send_error(404, f"File not found: {filename}")
                return
            try:
                mime_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
                file_size = os.path.getsize(file_path)
                with open(file_path, "rb") as f:
                    data = f.read()
                self.send_response(200)
                self.send_header("Content-Type", mime_type)
                self.send_header("Content-Length", str(file_size))
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(data)
                print(f"[{time.strftime('%H:%M:%S')}] Served {file_size/1024:.0f}KB {mime_type} -> /media/{filename}")
            except Exception as e:
                self.send_error(500, f"Read failed: {e}")
            return

        # Default: help text
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(b"TapClaw Media Relay is running.\n"
                         b"POST /frame with a JPEG body.\n"
                         b"GET /status for health check.\n"
                         b"GET /media/<filename> to serve workspace files.\n")

    def log_message(self, format, *args):
        """Quieter logs — skip noisy 200s, keep errors."""
        if args and str(args[1]) not in ("200", "204"):
            super().log_message(format, *args)


def main():
    parser = argparse.ArgumentParser(description="TapClaw Image Relay")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"Port (default {DEFAULT_PORT})")
    parser.add_argument("--workspace", default=DEFAULT_WORKSPACE, help=f"OpenClaw workspace (default {DEFAULT_WORKSPACE})")
    args = parser.parse_args()

    # Ensure workspace exists
    if not os.path.isdir(args.workspace):
        print(f"[WARN] Workspace '{args.workspace}' not found — creating it")
        os.makedirs(args.workspace, exist_ok=True)

    RelayHandler.workspace = args.workspace

    if not HAS_PIL:
        print("[WARN] Pillow not installed — frames will NOT be rotated.")
        print("       Install with: pip3 install Pillow")
    else:
        print(f"Image rotation: {ROTATION_DEGREES}° clockwise")

    server = HTTPServer(("0.0.0.0", args.port), RelayHandler)
    print(f"TapClaw Image Relay listening on 0.0.0.0:{args.port}")
    print(f"Workspace: {args.workspace}")
    print(f"Frames will be saved to: {os.path.join(args.workspace, FRAME_FILENAME)}")
    print(f"POST http://<mac-ip>:{args.port}/frame with JPEG body")
    print()

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down.")
        server.shutdown()


if __name__ == "__main__":
    main()
