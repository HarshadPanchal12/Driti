# Remote Device Access Platform — Architecture Plan
*(v2 — updated for vending-machine tablet fleet, internal device manager app, not Play Store)*

## 1. Goal

An internal "device manager" APK, sideloaded onto vending-machine tablets (not
distributed via Play Store), that makes an **outbound-only** connection to a backend
and exposes two capabilities to an internal web dashboard so non-technical staff never
have to touch a tab in person again:

1. **ADB shell access** — click a device, hit Connect, get a terminal.
2. **Live interactive screen share ("VNC-like")** — a panel showing the real device
   screen, with click/tap/swipe/keyboard forwarded back to the device.

Fleet is a mix of **rooted** and **non-rooted** tabs. Key advantage we should exploit:
**we have physical/USB access to every tab exactly once — at install time** (whoever
mounts the tab in the vending machine plugs it in to install the APK). That one-time
touch is enough to permanently unlock full remote ADB even on non-rooted units. See §2.

Three components, one relay backend in the middle.

```
┌────────────────┐        outbound         ┌────────────────┐        WebSocket        ┌──────────────────┐
│  Android Agent  │ ───────────────────────▶│  Backend Relay  │◀───────────────────────│  Web Frontend     │
│  (per device)   │   wss:// (persistent)    │  (Node/Go)      │   wss:// (dashboard)    │  React dashboard  │
│                  │                          │                 │                         │                    │
│ - ADB client lib │                          │ - Device registry│                        │ - Device list      │
│ - MediaProjection│                          │ - Session router │                        │ - Shell terminal   │
│ - AccessibilitySvc│                         │ - Auth/ACL       │                        │ - Screen canvas     │
│ - Config file     │                         │ - Msg broker     │                        │                    │
└────────────────┘                          └────────────────┘                         └──────────────────┘
```

Nobody opens an inbound port on the device or the backend needs to reach into the
device's network — the agent always dials out, so this works behind NAT/CGNAT/mobile
data with zero port forwarding.

---

## 2. The ADB question — rooted vs non-rooted (read this first, it drives the design)

ADB is a client/server protocol: `adbd` (the daemon) runs on the device and listens on
a socket; a client (normally the `adb` binary on a PC) talks to it over USB or TCP. An
app on the device can't *be* adbd, but it can act as an ADB **client** talking to the
device's own `adbd`, using a pure Kotlin/Java ADB protocol implementation (e.g.
`adb-android`/AdbLib — re-implements the wire protocol, no shell-out needed). That's the
"app as medium" idea and it's correct — the catch is just that `adbd` has to already be
enabled and reachable, and a plain app has no permission to flip that on by itself.

**Because this is a sideloaded fleet app with guaranteed one-time physical/USB access
at install, we don't have to live with that limitation on non-rooted units:**

| Device state | Setup action (once, at install/mounting time) | Result |
|---|---|---|
| **Rooted** | None needed beyond installing the APK | Agent uses `su` to flip `adb_enabled`, start/keep adbd alive, bind a port. Fully automatic from then on, survives reboot. |
| **Non-rooted** | Installer runs **one adb command over USB** during the mounting step: `adb shell dpm set-device-owner com.yourco.devicemanager/.AdminReceiver` (device must have no accounts added — do this on first boot before Wi-Fi/Google sign-in, or on a factory-reset unit) | App becomes **Android Device Owner**. From then on the app can call `DevicePolicyManager` to toggle ADB / Wireless Debugging programmatically, no root, and it survives reboots. This is the standard mechanism MDM/kiosk tools use — no Play Store requirement, works on a sideloaded APK. |
| **Non-rooted, missed the Device Owner step** (e.g. already provisioned with a Google account) | Fallback: installer manually enables Developer Options → Wireless Debugging once, physically, during mounting | Works, but more fragile — some OEMs reset this toggle on reboot/OTA, so it's the option to avoid if you can help it. Flag these devices in the dashboard as "manual re-pair may be needed." |

**Practical recommendation:** make Device Owner provisioning via USB the *standard*
step in your tab-mounting checklist, before the tab is ever put into a machine and
before any account is signed in. It costs the installer 10 extra seconds with a USB
cable and a laptop, and it's the difference between "works forever, remotely" and
"someone has to physically revisit the machine." Root vs non-rooted then only changes
*how* the agent flips ADB on (`su` vs `DevicePolicyManager`) — the rest of the pipeline
(agent as local ADB client, streaming shell I/O over the outbound WebSocket) is
identical either way.

## 2a. VNC / screen share for non-rooted — same logic

You don't need an actual VNC server binary (which typically wants root to bind input
injection system-wide). Use the same non-root APIs regardless of rooted/non-rooted:
**MediaProjection** for capture, **AccessibilityService** (`dispatchGesture()`) for tap/
swipe/type injection. Since Device Owner apps can also **silently grant themselves the
Accessibility permission and pre-approve the MediaProjection consent** via
`DevicePolicyManager` policies (no user prompt needed once you're Device Owner), the
one-time USB provisioning step in §2 kills the "consent dialog every session" annoyance
too. This is functionally your VNC server — capture + inject, streamed as frames rather
than a raw VNC protocol, which is actually simpler to pipe through your existing
WebSocket relay than standing up real RFB/VNC.

---

## 3. Screen share + interactive control (this one *doesn't* need root)

This part is fully doable on stock, non-rooted devices, using two public Android APIs:

- **Capture:** `MediaProjection` API — the standard screen-recording/mirroring API.
  User approves a one-time system consent dialog per session (or once, if you request
  a persistent projection via a foreground service). The agent captures frames, encodes
  them (H.264 via `MediaCodec`, or downsampled JPEG frames if you want something simpler
  to start with), and streams over the same outbound WebSocket.
- **Control (tap/swipe/type injection):** `AccessibilityService` with
  `dispatchGesture()` — lets an app inject synthetic touch gestures and text input
  system-wide, no root required (this is how TeamViewer QuickSupport / AnyDesk / Android's
  own remote-support tooling do it). User grants the Accessibility permission once during
  agent setup.

So: MediaProjection (video out) + AccessibilityService (input in) = a full "emulator"
view in the browser, no root, no Device Owner needed. This part works on every tier.

---

## 4. Component breakdown

### 4.1 Mobile Agent App (Android)
- Runs as a foreground service (needed to keep MediaProjection/socket alive).
- On boot: reads **config file**, opens persistent outbound WebSocket to backend, authenticates.
- Modules:
  - `ConfigManager` — loads/validates the customizable config (see §6).
  - `AdbBridge` — pure-protocol ADB client talking to local/paired adbd; exposes shell stdin/stdout as a stream multiplexed onto the WebSocket.
  - `ScreenCaptureService` — MediaProjection capture → encode → frame stream.
  - `InputInjector` — AccessibilityService, receives tap/swipe/text events from backend, calls `dispatchGesture()`.
  - `Heartbeat` — periodic keepalive + device status (battery, network, ADB availability tier).

### 4.2 Backend Relay
- Maintains the device registry (which agents are connected, their capability tier: rooted / device-owner / plain).
- Terminates two kinds of WebSocket connections: agent-side and dashboard-side, and relays messages between them by device ID + session ID.
- Does **not** need to understand ADB or video codecs — it's a dumb pipe/multiplexer with auth and routing. Keeps it simple and horizontally scalable.
- Auth: device tokens (issued at enrollment) for agents; user/session auth for the dashboard.
- Optional: record session logs, TURN/relay fallback if you later want peer-to-peer for video instead of relaying through the backend (reduces backend bandwidth for screen share at scale).

### 4.3 Web Frontend
- **Device list view** — pulls from backend registry: online/offline, capability tier, last seen.
- Click device → device detail pane with two actions:
  - **Connect (Shell)** → opens a terminal component (e.g. xterm.js) wired to a WebSocket subchannel scoped to `deviceId:shell`.
  - **Screen (Emulator view)** → canvas/video element rendering incoming frames from `deviceId:screen`, with mouse/touch events on the canvas translated to tap/swipe coordinates and sent back down `deviceId:input`.
- Both can be open at once per device, on separate logical channels over the same WebSocket (or separate sockets — see §7).

---

## 5. Connection flow (sequence)

1. Agent boots → reads config → dials `wss://backend/agent` with device token.
2. Backend authenticates, registers device in registry, marks it **online**, detects capability tier (checks if adbd reachable, if Device Owner, if rooted).
3. Dashboard user loads device list → backend pushes live registry state.
4. User clicks a device → **Connect**:
   - Backend opens a `shell` session, tells agent "start shell session `sid-123`".
   - Agent's `AdbBridge` opens local ADB shell, streams output back tagged `sid-123`.
   - Frontend terminal renders it; keystrokes go back down the same tagged channel.
5. User opens the **screen panel**:
   - Backend tells agent "start screen session `sid-456`".
   - Agent starts (or reuses) MediaProjection capture, streams encoded frames tagged `sid-456`.
   - Frontend decodes and paints to canvas/video element.
   - User clicks on canvas → frontend computes device-relative coordinates → sends `input` event tagged `sid-456` → backend → agent's `InputInjector` → `dispatchGesture()`.
6. Either side can close a session independently (shell and screen are decoupled) without dropping the underlying agent connection.

---

## 6. Customizable agent config file

Ship as a JSON (or YAML) file the agent reads at startup, and re-reads on a signal/restart so it's field-editable without rebuilding the APK:

```json
{
  "backend_url": "wss://relay.example.com/agent",
  "device_token": "***",
  "reconnect": {
    "initial_delay_ms": 1000,
    "max_delay_ms": 30000,
    "jitter": true
  },
  "heartbeat_interval_ms": 15000,
  "features": {
    "shell_enabled": true,
    "screen_share_enabled": true,
    "input_injection_enabled": true
  },
  "screen": {
    "max_fps": 15,
    "max_resolution": "720p",
    "codec": "h264"
  },
  "adb": {
    "prefer_local_port": 5555,
    "allow_root_escalation": true
  },
  "logging": {
    "level": "info",
    "remote_log_upload": false
  }
}
```

Points worth deciding early:
- Where does this file live — bundled default + remote override pushed from backend at
  connect time (lets you push config changes to fleets without redeploying the APK), or
  purely local (simpler, but harder to manage at scale)? A hybrid (local defaults,
  backend can push a delta on connect) is usually the sweet spot.
- `device_token` should be provisioned per-device, not baked into a shared APK.

---

## 7. WebSocket protocol shape

Two reasonable options:

- **A. Single multiplexed socket per agent**, all sessions (shell, screen, input,
  heartbeat) tagged with `{type, sessionId, deviceId, payload}` — fewer sockets to
  manage, but you own the multiplexing/backpressure logic.
- **B. Separate sockets per concern** (control channel + a dedicated binary socket for
  screen frames) — simpler backpressure handling for the video path (frames are large,
  bursty, and loss-tolerant, unlike shell/control traffic which must be reliable and
  ordered), at the cost of more connection management.

Given screen frames are high-bandwidth and shell/input are latency-sensitive but
low-bandwidth, **B is the better real-world choice**: keep a small JSON control channel
for shell + input + registry events, and a separate binary channel per active screen
session for video frames, so a bad frame burst never head-of-line-blocks a keystroke.

---

## 8. Open questions / risks to resolve before building

1. **Bake the Device Owner step into the mounting SOP.** Write it as a literal checklist
   item ("plug in via USB, run provisioning script, *then* mount tab in machine") — if
   installers skip it, that device falls back to the fragile manual-toggle tier.
2. **Existing already-deployed tabs** that already have a Google account signed in can't
   retroactively become Device Owner without a factory reset. Decide whether it's worth a
   reset-and-reprovision pass on the current fleet, or whether those stay on manual
   Wireless Debugging pairing permanently.
3. **Bandwidth/cost at scale** — see the companion pricing doc; relaying video for many
   concurrent screen-share sessions is the dominant variable cost.
4. **Security** — shell access + input injection is full remote control of a fleet of
   payment-adjacent devices; you want per-session audit logs, short-lived session
   tokens, and server-side authorization checks (never trust the frontend to gate who
   can connect to what).
5. **Root detection / capability reporting** — have the agent self-report its tier
   (rooted / device-owner / manual-fallback) on connect so the dashboard can show which
   devices have full automatic ADB vs which might need a manual re-pair after an OTA.
