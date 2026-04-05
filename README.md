# TapInsight — AI-Powered AR Companion for RayNeo X3 Pro

> **ALPHA SOFTWARE — Use at your own risk.** This project is under active development. Features may be incomplete, unstable, or change without notice. No warranty is provided.

TapInsight transforms your RayNeo X3 Pro AR glasses into an AI-powered smart assistant with voice and vision capabilities, hands-free navigation, internet radio, and a full web browser — all controlled by simple gestures.

---

## What It Does

**TapInsight** is a companion layer that runs on the RayNeo X3 Pro, adding AI capabilities on top of the existing glasses experience. At its core, it uses Google's Gemini API for both text/vision and live voice conversations, letting you interact with an AI assistant that can see what you see through the glasses camera.

### AI Assistant (Gemini-Powered)

The AI assistant runs through Gemini's models and supports two modes. Standard mode handles text and vision queries — point the camera at something and ask about it. Live mode enables real-time voice conversation with the assistant, which can respond naturally while seeing through your camera. The assistant can look up nearby places, get directions with traffic, identify objects, read text, and answer questions about what's in view.

The key here is simplicity: the Gemini API currently offers generous free tiers, so you can get started without any cost. And if you want to swap in a different model later, you can do that right from the companion app — no code changes needed.

### Gesture Controls

Everything is designed for hands-free use on AR glasses. The X3 Pro's touchpad on the temple handles all navigation:

- **Single Tap** — Select, click links, focus inputs, interact with UI elements
- **Double Tap** — Go back (browser history, close dialogs, return to list view)
- **Swipe Left/Right** — Scroll horizontally, switch tabs
- **Swipe Up/Down** — Scroll through content

No phone needed once you're set up.

### TapRadio — Internet Radio Player

TapRadio is a built-in internet radio player optimized for the glasses' 960x480 display. It comes preloaded with 18 stations across genres like Chill, Jazz, Electronic, Rock, Classical, News, and more (SomaFM, Radio Paradise, NPR, BBC World Service, NASA Third Rock Radio).

Features:
- Favorites system — star any station for quick access, Favorites tab is always first
- Genre filtering — tap genre tabs to browse by category
- Play/pause, next/previous, volume control — all gesture-friendly
- Add/edit/delete stations directly on the glasses
- Search 30,000+ stations from the companion app using the Radio Browser API
- Stations sync between the companion app and glasses automatically

### TapBrowser — Full Web Browser

Built on [TapLinkX3](https://github.com/informalTechCode/TAPLINKX3) (see acknowledgments), TapBrowser provides a full web browsing experience on the glasses with a customizable dashboard of quick-launch links organized by category (AI/Chatbots, Music/Streaming, Social, Productivity, and more). It includes bookmarks, desktop/mobile mode switching, and a QR code scanner for quick URL entry.

### TapClaw — OpenClaw AI Agent Integration

TapClaw connects your glasses to an [OpenClaw](https://openclaw.ai) AI agent running on your Mac. This gives the glasses access to a powerful, tool-equipped agent that can control smart home devices, check emails, run automations, and analyze what you see through the camera — all by voice.

The integration uses a lightweight image relay service that runs on the same Mac as OpenClaw. When you ask the agent to look at something, the glasses capture a camera frame, POST it to the relay over your local network, and the agent reads the saved image from its workspace. This bypasses the OpenClaw gateway's attachment limitations entirely.

Key components:
- **Image Relay** — A Python HTTP server (port 18790) that receives camera frames and saves them to the OpenClaw workspace. Installs as a macOS launchd service that auto-starts on login.
- **Remote Access** — Cloudflare Tunnel support for accessing your OpenClaw agent from anywhere, not just your home network.
- **Image Archival** — Every analyzed camera frame is permanently saved in dated folders (`saved_photos/YYYY-MM-DD/`) with timestamps.
- **Media Display** — The agent can display images, videos, web pages, and text files on your glasses using the `open_taplink` tool through TapBrowser.

### Companion App (Phone/Laptop WiFi Configuration)

The companion app is a web interface served from the glasses over WiFi. Open it on your phone or laptop by navigating to the glasses' IP address on port 19110. From here you can:

- **Setup** — Enter your Gemini API key, configure OAuth for Google services (Maps, Calendar, etc.), set Spotify credentials, adjust the AI model and system prompt, and configure HUD display settings
- **Browser** — Manage bookmarks and browser settings, view supported media types for TapBrowser display
- **Dashboard** — Customize the TapBrowser homepage links and layout
- **TapRadio** — Search for stations online, manage your station list, toggle favorites, and sync everything to the glasses with one button
- **TapClaw** — Configure OpenClaw gateway connection, set up Cloudflare Tunnel for remote access, check image relay status, view saved images gallery, and install the relay service

There are also diagnostic tools: Test Location (verify GPS) and Test Traffic (verify directions API).

---

## Download

**[`TapInsight.apk`](TapInsight.apk)** — Pre-built debug APK, ready to sideload onto your RayNeo X3 Pro via ADB. No Android Studio required.

---

## Getting Started

### Prerequisites

- RayNeo X3 Pro AR glasses
- A Google Gemini API key ([get one free at Google AI Studio](https://aistudio.google.com/apikey))
- A phone or laptop on the same WiFi network as the glasses
- Android Studio only needed if building from source — otherwise just grab the APK above

### Quick Setup

1. **Download [`TapInsight.apk`](TapInsight.apk)** from this repo
2. Sideload it onto your RayNeo X3 Pro via ADB: `adb install TapInsight.apk`
3. Launch TapInsight on the glasses
4. **Open [`companion.html`](companion.html)** in any browser on your phone or laptop — it's a one-page setup wizard that connects to the glasses over WiFi
5. Enter your glasses' IP address (default: `<glasses-ip>`) and click **Connect**
6. In the Setup tab, enter your Gemini API key
7. Start talking — tap the glasses touchpad to activate the AI

The companion app runs entirely over WiFi at `http://openclaw.ai:19110` (adjust the IP to match your glasses — check Settings → WiFi on the glasses to find it). From there you can configure everything: API keys, AI model, OAuth, TapRadio stations, and more.

### Connecting via USB (Recommended)

USB is the fastest and most reliable way to access the companion app. Connect the glasses via USB and run:

```bash
adb forward tcp:19110 tcp:19110
```

Then open **http://localhost:19110** in any browser. The `companion.html` page defaults to USB mode and auto-detects when the forwarding is active.

**The port forward resets** when the glasses disconnect, reboot, or ADB restarts. To fix this permanently, use the included helper script:

```bash
./setup_usb.sh --watch
```

This runs in the background and automatically re-establishes the port forward whenever the glasses reconnect. You can also run `./setup_usb.sh` (without `--watch`) for a one-shot forward.

### Connecting via WiFi

If you prefer wireless, your computer and glasses must be on the same WiFi network. Find the glasses' IP in Settings → WiFi, then open `http://<glasses-ip>:19110` in your browser. The `companion.html` page has a WiFi tab for this.

### Using The Phone GPS Bridge

The phone GPS bridge only works when the phone browser can reach the glasses companion server. In practice, that means the phone and the glasses must be on the **same network** and able to talk to each other directly.

Basic steps:

1. Put the phone and glasses on the same network.
2. Find the glasses IP in the companion app `Connection Info` block or in the glasses Wi-Fi settings.
3. On the phone, open `https://<glasses-ip>:19110`.
4. Accept the local certificate warning once if the browser asks.
5. In the companion app, turn **Phone GPS Bridge** on.
6. Tap **Use This Phone's GPS**.
7. Allow location permission in the phone browser.

Common ways to use it:

- **Home / office Wi-Fi**
  - Connect both the phone and the glasses to the same Wi-Fi network.
- **iPhone Personal Hotspot**
  - Turn on Personal Hotspot on the iPhone and connect the glasses to it.
  - Open `https://<glasses-ip>:19110` on that iPhone.
- **Android hotspot**
  - Turn on the Android hotspot and connect the glasses to it.
  - Open `https://<glasses-ip>:19110` on that Android phone.
- **Other same-network arrangements**
  - Travel router, mobile hotspot device, or any shared LAN where both devices can reach each other.

Important notes:

- If the phone and glasses are **not** on the same network, the bridge will not work.
- If your router or hotspot uses **client isolation**, the bridge will not work until that is disabled or you switch networks.
- Prefer the `https://` companion URL for phone GPS bridge use, especially on iPhone.

### Optional Configuration

- **Google OAuth** — Enable Google Maps, Places, and Calendar integration by setting up OAuth credentials in the companion app's Setup tab
- **Spotify** — Connect your Spotify account for music control
- **Custom AI Prompt** — Modify the system prompt to customize the AI's personality and behavior
- **HUD Settings** — Adjust font size, display duration, and formatting for the heads-up display

---

## Architecture

The project has two main modules:

- **`app`** — The main TapInsight application (AI assistant, companion server, tool system)
- **`tapbrowser`** — The web browser module (based on TapLinkX3)

Key technical details:

- **AI Models**: Gemini Flash (text/vision) and Gemini 2.5 Flash Native Audio (live voice) — configurable via companion app or routed through OpenClaw gateway, no cost on free tier
- **Tool System**: Google Places, Routes/Directions with traffic, Location, Weather, Calendar, Spotify, Web Search, and media display via `open_taplink` — all accessible to the AI through natural conversation
- **ToolAssist Engine**: Client-side tool execution that proactively detects when you're asking about places, directions, or location and injects results into the conversation
- **OpenClaw Client**: WebSocket client that connects to an OpenClaw gateway for agent-based AI, with image relay support for camera frame analysis
- **Image Relay**: HTTP relay service (port 18790) bridging camera frames from glasses to the OpenClaw workspace, with automatic rotation and archival
- **Companion Server**: NanoHTTPD server on port 19110 serving HTML configuration and management pages
- **HUD**: Real-time heads-up display showing AI responses formatted for the glasses' compact viewport

---

## TapClaw Image Relay Setup

The image relay is required for camera-based AI analysis through OpenClaw. It runs on the same Mac as your OpenClaw instance.

### One-Command Install

```bash
bash tools/install-relay.sh
```

This installs the relay to `~/.tapclaw/`, creates a macOS launchd service that auto-starts on login, and verifies the relay is running. Frames are saved to `~/.openclaw/workspace/camera_frame.jpg` and archived in dated folders.

### Manual Start

```bash
python3 tools/image_relay.py
```

### Uninstall

```bash
bash tools/install-relay.sh --uninstall
```

### Endpoints

- `POST /frame` — Receive a JPEG camera frame (raw body), rotate 90° clockwise, save to workspace
- `GET /latest` — Serve the most recent camera frame as JPEG
- `GET /status` — JSON health check with frame availability and age

### Remote Access via Cloudflare Tunnel

To access your OpenClaw agent from outside your home network, set up a Cloudflare named tunnel. This requires a domain on Cloudflare (from ~$2/year).

```bash
# 1. Install cloudflared
brew install cloudflared

# 2. Authenticate with your Cloudflare account
cloudflared tunnel login

# 3. Create a named tunnel
cloudflared tunnel create tapclaw

# 4. Route DNS to your domain (gateway + image relay)
cloudflared tunnel route dns tapclaw tapclaw.yourdomain.com
cloudflared tunnel route dns tapclaw relay.yourdomain.com

# 5. Create ~/.cloudflared/config.yml:
#    tunnel: YOUR_TUNNEL_ID
#    credentials-file: /Users/YOU/.cloudflared/YOUR_TUNNEL_ID.json
#    ingress:
#      - hostname: tapclaw.yourdomain.com
#        service: http://localhost:18789
#      - hostname: relay.yourdomain.com
#        service: http://localhost:18790
#      - service: http_status:404

# 6. Allow the tunnel origin in OpenClaw
openclaw config set gateway.controlUi.allowedOrigins "https://tapclaw.yourdomain.com"

# 7. Start the tunnel
cloudflared tunnel run tapclaw

# Auto-start on login:
brew services start cloudflared
```

Your gateway is now reachable at `wss://tapclaw.yourdomain.com`. Use this as the Gateway URL in the companion app.

**Note:** The `relay.` subdomain tunnels port 18790 so camera vision works remotely. The app auto-detects remote gateways and routes frames through `https://relay.yourdomain.com` instead of a local IP. Without the relay tunnel entry, camera vision only works on the local network.

**Troubleshooting:**
- **"origin not allowed"** — Two fixes needed: (1) Add your tunnel URL to the `allowedOrigins` array in `~/.openclaw/openclaw.json` under `gateway.controlUi`, (2) Retrieve your gateway token with `grep '"token"' ~/.openclaw/openclaw.json`, enter it in the dashboard, then approve the device with `openclaw devices list` and `openclaw devices approve <device-id>`. Restart the gateway after.
- **"503 Service Unavailable"** — Missing `~/.cloudflared/config.yml`. Create it with the ingress rules above.
- **"Error 1033"** — The tunnel isn't running. Start with `cloudflared tunnel run tapclaw`.

**Alternative:** [Tailscale](https://tailscale.com) provides a free mesh VPN with stable private IPs — no domain needed. Use your Tailscale IP with `ws://` protocol in the companion app.

---

## Acknowledgments

Special thanks to **InformalTech** and **glxblt76**, the developers of [TapLinkX3](https://github.com/informalTechCode/TAPLINKX3), for creating the browser foundation and for allowing integration of their work into this project. The `tapbrowser` module is built on their excellent AR-optimized web browser for the RayNeo X3 Pro.

---

## Disclaimer

This is alpha software provided as-is. The developers are not responsible for any issues arising from its use. API keys and credentials are stored locally on your device and are never transmitted to third parties beyond the configured API providers (Google, Spotify, etc.).

**Security note**: This repository has been scrubbed of all personal information, API keys, and credentials. All sensitive values use placeholders. You must supply your own API keys via the companion app or `local.properties`.

---

## Developer Docs: OpenClaw CDP Browser Control

**Target Platform:** macOS (Apple Silicon)
**OpenClaw Version:** 2026.4.2
**Method:** Direct CDP Attachment via Port 9222

### 1. Overview

The CDP method bypasses visual screen-scraping (Peekaboo) and extension relays. It creates a binary WebSocket pipe between the OpenClaw Gateway and the Chrome V8 engine. This allows the agent to read the DOM tree directly, making it immune to UI shifts that break coordinates-based automation.

### 2. Installation & Environment Setup

**Step A: Clean the Configuration**

Ensure no "zombie" browser keys are present in your `openclaw.json`.

```bash
openclaw config set browser.profiles.user '{
  "driver": "cdp",
  "cdpUrl": "http://127.0.0.1:9222",
  "attachOnly": true,
  "color": "#4285F4"
}'
openclaw config set browser.defaultProfile "user"
```

**Step B: The "Launch Secret" (macOS)**

Chrome will only write the required `DevToolsActivePort` file if started with the explicit debugging flag from the CLI.

1. Fully Quit Chrome (Cmd+Q).
2. Launch via Terminal:

```bash
/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --remote-debugging-port=9222 --restore-last-session &
```

### 3. Running the Bridge

**Step 1: Health Check**

Verify Chrome is actually "listening" before starting the Gateway.

```bash
curl http://127.0.0.1:9222/json/version
```

If you see a JSON block with `webSocketDebuggerUrl`, you are ready.

**Step 2: Start the Gateway**

```bash
openclaw gateway restart
```

**Step 3: Manual Attachment**

Force the profile to "Handshake" with the open Chrome window:

```bash
openclaw browser start --profile user
```

### 4. Troubleshooting

**Issue: `Could not find DevToolsActivePort`**
- Cause: Chrome was opened via the Dock (GUI) instead of the Terminal (CLI).
- Fix: Kill all Chrome processes (`killall "Google Chrome"`) and restart using the command in Section 2B.

**Issue: `Error: Unrecognized key: "entries"`**
- Cause: Attempting to use the old 2025 "nested" config schema.
- Fix: Use the flat schema: `openclaw config set tools.canvas.enabled false`.

**Issue: Connection Refused (Port 9222)**
- Cause: macOS Firewall or a conflicting "Zombie" instance of Chrome.
- Fix:

```bash
lsof -i :9222  # Find what is using the port
kill -9 <PID>  # Kill the ghost process
```

### 5. Optimized Workflow for "Deep Research"

To prevent credit burn, use this specific prompt pattern now that CDP is active:

**Developer Command:**
"Gort, switch to the `user` browser profile. Locate the Gemini tab. Instead of clicking via coordinates, use a DOM selector to find the 'Deep Research' toggle and execute a `native.click()`. If the selector is missing, log the error and stop."

### 6. Analytical Summary for the "Oakland Build"

| Component | Status | Role |
|-----------|--------|------|
| Model `qwen3.5-omni-plus-realtime` | | The "Brain" for audio/logic |
| Transport `CDP / WebSocket` | | The "Eyes" (Reading the code) |
| Hardware `M1 Mac Mini (Unified Memory)` | | The "Muscles" (Whisper acceleration) |

---

## License

Distributed under the GNU General Public License v3.0 — see the [LICENSE](LICENSE) file for details.
