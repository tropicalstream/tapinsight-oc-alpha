# TapInsight 1.1.2

## Highlights

- Companion app now shows the current connection, detected glasses LAN IP, and the recommended phone GPS bridge URL directly in the UI.
- Background audio remains reliable when the projector is off / low-power display mode is active.
  - You can keep listening while only using the waveguides when needed, which helps save battery.
- Optional **Phone GPS Bridge** remains available for higher-accuracy phone-assisted location.
- Visualizer/theme refresh remains included, including the orchestra replacement and current themed visualizer set.

## Setup Changes

- The published source now leaves the default TapClaw gateway URL blank. Configure it manually or use QR pairing in the companion app.

## Phone GPS Bridge Usage

The phone GPS bridge works only when the phone browser can reach the glasses companion server over the same network.

Recommended flow:

1. Put the phone and glasses on the same network.
2. Find the glasses IP in the companion app `Connection Info` block or in the glasses Wi-Fi settings.
3. Open `https://<glasses-ip>:19110` on the phone.
4. Accept the local certificate warning once if needed.
5. Turn **Phone GPS Bridge** on in the companion app.
6. Tap **Use This Phone's GPS**.
7. Allow browser location permission.

Supported real-world setups:

- **Home / office Wi-Fi**
  - Both devices connected to the same Wi-Fi network.
- **iPhone Personal Hotspot**
  - Connect the glasses to the iPhone hotspot, then open the companion page on that iPhone.
- **Android hotspot**
  - Connect the glasses to the Android hotspot, then open the companion page on that Android phone.
- **Other same-network arrangements**
  - Travel router, mobile hotspot puck, or any shared LAN where both devices can reach each other.

Notes:

- If the phone and glasses are not on the same network, the bridge will not work.
- If the network isolates clients from each other, the bridge will not work until that is disabled or you switch networks.
- Prefer `https://<glasses-ip>:19110` for phone GPS bridge use, especially on iPhone.

## Safety / Notes

- Maps and location services remain experimental.
- Follow local laws while using AR navigation features.
- Do not rely on location services for emergencies.
- If you lose the glasses, you lose locally stored data unless you back it up.
- Logged-in website sessions are not part of the published source or APK.
