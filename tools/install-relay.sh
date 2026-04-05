#!/bin/bash
#
# TapClaw Image Relay — One-command installer for macOS
#
# Installs the image relay as a background service that auto-starts on login.
# The relay receives camera frames from the AR glasses and saves them to
# OpenClaw's workspace so the agent can analyze them.
#
# Usage:
#   curl -sL <url>/install-relay.sh | bash
#   — or —
#   bash tools/install-relay.sh
#
# To uninstall:
#   bash tools/install-relay.sh --uninstall

set -e

INSTALL_DIR="$HOME/.tapclaw"
RELAY_SCRIPT="$INSTALL_DIR/image-relay.py"
PLIST_NAME="com.tapinsight.image-relay"
PLIST_PATH="$HOME/Library/LaunchAgents/$PLIST_NAME.plist"
WORKSPACE="${OPENCLAW_WORKSPACE:-$HOME/.openclaw/workspace}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── Uninstall ────────────────────────────────────────────────────────────
if [[ "$1" == "--uninstall" ]]; then
    echo "Uninstalling TapClaw Image Relay..."
    launchctl unload "$PLIST_PATH" 2>/dev/null || true
    rm -f "$PLIST_PATH"
    rm -rf "$INSTALL_DIR"
    echo "Done. Relay service removed."
    exit 0
fi

# ── Install ──────────────────────────────────────────────────────────────
echo "Installing TapClaw Image Relay..."
echo "  Workspace: $WORKSPACE"

# Ensure directories exist
mkdir -p "$WORKSPACE"
mkdir -p "$INSTALL_DIR"

# Copy relay script
if [[ -f "$SCRIPT_DIR/image_relay.py" ]]; then
    cp "$SCRIPT_DIR/image_relay.py" "$RELAY_SCRIPT"
else
    echo "Error: image_relay.py not found in $SCRIPT_DIR"
    exit 1
fi
chmod +x "$RELAY_SCRIPT"
echo "  Installed: $RELAY_SCRIPT"

PYTHON3="$(which python3 2>/dev/null || echo /usr/bin/python3)"

# Create launchd plist with correct workspace path
mkdir -p "$HOME/Library/LaunchAgents"
cat > "$PLIST_PATH" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>$PLIST_NAME</string>
    <key>ProgramArguments</key>
    <array>
        <string>$PYTHON3</string>
        <string>$RELAY_SCRIPT</string>
        <string>--workspace</string>
        <string>$WORKSPACE</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>/tmp/tapclaw-image-relay.log</string>
    <key>StandardErrorPath</key>
    <string>/tmp/tapclaw-image-relay.log</string>
</dict>
</plist>
EOF
echo "  Installed: $PLIST_PATH"

# Stop existing service if running
launchctl unload "$PLIST_PATH" 2>/dev/null || true

# Start the service
launchctl load "$PLIST_PATH"
echo "  Service started"

# Verify
sleep 1
if curl -s "http://localhost:18790/status" > /dev/null 2>&1; then
    echo ""
    echo "✓ TapClaw Image Relay is running on port 18790"
    echo "  Frames will be saved to: $WORKSPACE/camera_frame.jpg"
    echo "  Logs: /tmp/tapclaw-image-relay.log"
    echo ""
    echo "  The relay starts automatically on login."
    echo "  To uninstall: bash $SCRIPT_DIR/install-relay.sh --uninstall"
else
    echo ""
    echo "⚠ Service started but not responding yet. Check: /tmp/tapclaw-image-relay.log"
fi
