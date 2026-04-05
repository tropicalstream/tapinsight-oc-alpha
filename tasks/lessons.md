# Lessons

- When a Live WebSocket fails with a ping/pong timeout, inspect the transport assumptions before adding app-level heartbeats. For Gemini Live, prefer no client-initiated OkHttp ping interval unless the protocol explicitly requires it.
- For nearby-place answers, raw Places API order is not enough. Re-rank around user intent (especially "open now") and always give the chat card a browser-navigation path, even if the model text did not include a URL.
- For place lookup UX, "nearest" is not enough. If the nearest place is closed, widen the search for the nearest open alternative and surface that explicitly with ETA/context instead of making the user infer it.
- For cross-activity media HUDs, never infer playback state from selection alone. Persist the actual playing state from the source player and render from that single source of truth.
- If a voice query has a deterministic local tool path and Gemini keeps distorting the answer, stop letting Gemini paraphrase it. Route locally and surface the exact tool result.
- For multi-activity browser/media flows, preserving state usually means reusing the existing activity instance, not reconstructing UI state in a fresh one.
- Never treat Gemini Live input transcription chunks as final utterances. Debounce them until speech settles; otherwise local intent routing and tool assists will fire mid-sentence and make the assistant look frozen.
- If a returning WebView only has mirrored playback state, do not let it overwrite the authoritative playing flag with its own paused local audio state.
- For a deterministic multi-source briefing, do not rely on prompt-only planning inside Gemini. Build a local orchestration tool that composes calendar, location, routing, weather, AQI, and research data directly.
- If a feature is meant to trigger only on an explicit phrase, enforce that at the final tool-dispatch boundary as well as in prompts and local parsers. Do not trust the model to honor a narrow trigger contract on its own.
- In a multi-WebView browser, clearing only the active page is not enough for media handoff bugs. Before a new autoplay request, stop matching media across every WebView that can still hold a live playback pipeline.
- If an explicit media launch must replace current playback, do not reuse the existing WebView instance. Reset to a pristine WebView so old page JS and media session state cannot race the new request.
- For release-grade network behavior, do not rely on process-wide `bindProcessToNetwork(...)` plus `HttpURLConnection` across hotspot or NAT64 transitions. Use a single OkHttp transport and clear any stale process binding on startup/resume.
- When a user corrects the target bug, stop and re-plan immediately. Do not keep iterating on the previous issue just because it already has momentum.
- For Gemini Live, do not treat `setup` send-success as session readiness. Only transition to ready after a server acknowledgement such as `setupComplete` or real server content.
- For place-list chat cards, never route to direct driving directions by taking the first extracted address. One clear address should open directions; multiple addresses should open a Maps list/search view instead.
