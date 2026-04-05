# OpenClaw Security Risk Warning

TapClaw / OpenClaw can grant a remote agent access to powerful actions on your Mac, browser sessions, accounts, and data. Treat it as a high-trust admin surface.

## Main Risks

- **Remote control risk**: OpenClaw may be able to control apps, browser tabs, automations, and other connected tools on your machine.
- **Token exposure risk**: Anyone who gets your OpenClaw gateway token or related credentials may be able to connect to your agent.
- **Internet exposure risk**: If you expose OpenClaw through Cloudflare Tunnel or another public endpoint, mistakes in origin settings or token handling can open access beyond your local network.
- **Sensitive data risk**: Browser cookies, OAuth tokens, emails, calendar data, and captured camera frames may become accessible to the agent or to anyone who compromises the host.
- **Image relay risk**: Camera frames sent from the glasses are saved to disk on the OpenClaw host. Those images can contain private surroundings, screens, documents, or bystanders.

## Safer Defaults

- Keep OpenClaw on a trusted local network unless you truly need remote access.
- Prefer QR/device pairing over manually sharing long-lived tokens.
- Never post gateway tokens, setup URLs, or config files in chat, screenshots, or public repos.
- If you enable a public tunnel, restrict `allowedOrigins` carefully and verify the exact hostname.
- Rotate tokens immediately if you think they were copied, logged, or exposed.
- Review what tools and automations OpenClaw can access before using it with important accounts.
- Treat the Mac running OpenClaw as a sensitive machine and keep it updated.

## Before Sharing This Alpha

- Only invite collaborators you trust to the private repo.
- Share the APK through the private release only.
- Tell testers not to expose OpenClaw to the public internet unless they understand the risks.
- Tell testers not to reuse sensitive browser profiles or personal admin sessions without understanding the consequences.

## Short Version

If you would not hand someone broad access to your Mac, browser sessions, and saved camera frames, do not expose OpenClaw casually or share its credentials.
