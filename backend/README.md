# Tablet Remote Backend (PoC)

Minimal NestJS API for tablet remote management. All state is in memory.

## Run locally

```bash
cd backend
npm install
npm run start:dev
```

Server starts on `http://localhost:3000` (override with `PORT`).

## Auth

Send header `x-agent-key` on every request. Default key: `dev-agent-key` (set `AGENT_API_KEY` to override).

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/devices/:deviceId/heartbeat` | Device check-in |
| GET | `/devices/:deviceId/command` | Poll oldest PENDING command (marks SENT) |
| POST | `/devices/:deviceId/result` | Report command outcome |
| POST | `/devices/:deviceId/enqueue-command` | Queue a new command |

### Examples

```bash
# Heartbeat
curl -X POST http://localhost:3000/devices/tablet-1/heartbeat \
  -H "x-agent-key: dev-agent-key"

# Enqueue command
curl -X POST http://localhost:3000/devices/tablet-1/enqueue-command \
  -H "x-agent-key: dev-agent-key" \
  -H "Content-Type: application/json" \
  -d '{"type":"SHELL","payload":{"cmd":"getprop ro.build.version.release"}}'

# Poll command (device)
curl http://localhost:3000/devices/tablet-1/command \
  -H "x-agent-key: dev-agent-key"

# Submit result (device)
curl -X POST http://localhost:3000/devices/tablet-1/result \
  -H "x-agent-key: dev-agent-key" \
  -H "Content-Type: application/json" \
  -d '{"commandId":"<id>","success":true,"output":"14"}'
```
