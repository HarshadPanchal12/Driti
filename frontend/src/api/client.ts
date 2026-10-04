export type CommandType =
  | 'ENABLE_ADB'
  | 'DISABLE_ADB'
  | 'REBOOT'
  | 'SHELL'
  | 'SCREENSHOT'
  | 'INSTALL_APP'
  | 'INSTALL_FROM_URL'
  | 'UNINSTALL_APP'
  | 'OPEN_APP'
  | 'CLOSE_APP'
  | 'HIDE_NAV'
  | 'SHOW_NAV';

export type CommandStatus = 'PENDING' | 'SENT' | 'DONE' | 'FAILED';

export interface Command {
  id: string;
  type: CommandType;
  payload?: Record<string, unknown> & { result?: unknown };
  status: CommandStatus;
  createdAt: string;
}

export interface DeviceSummary {
  deviceId: string;
  lastHeartbeatAt?: string;
  captureScreen: boolean;
  lastScreenshotAt?: string;
  online: boolean;
  vncTunnelConnected: boolean;
  vncViewerConnected: boolean;
}

export interface DeviceDetail extends DeviceSummary {
  commands: Command[];
}

const API_KEY = import.meta.env.VITE_API_KEY ?? 'dev-agent-key';

async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      'x-agent-key': API_KEY,
      ...(init?.headers ?? {}),
    },
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Request failed: ${response.status}`);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const contentType = response.headers.get('content-type') ?? '';
  if (contentType.includes('application/json')) {
    return response.json() as Promise<T>;
  }

  return undefined as T;
}

export function listDevices(): Promise<DeviceSummary[]> {
  return apiFetch('/devices');
}

export function getDevice(deviceId: string): Promise<DeviceDetail> {
  return apiFetch(`/devices/${encodeURIComponent(deviceId)}`);
}

export function enqueueCommand(
  deviceId: string,
  type: CommandType,
  payload?: Record<string, unknown>,
): Promise<Command> {
  return apiFetch(`/devices/${encodeURIComponent(deviceId)}/enqueue-command`, {
    method: 'POST',
    body: JSON.stringify({ type, payload }),
  });
}

export function setScreenCapture(
  deviceId: string,
  enabled: boolean,
): Promise<{ deviceId: string; captureScreen: boolean }> {
  return apiFetch(`/devices/${encodeURIComponent(deviceId)}/screen-capture`, {
    method: 'POST',
    body: JSON.stringify({ enabled }),
  });
}

export function screenUrl(deviceId: string): string {
  return `/devices/${encodeURIComponent(deviceId)}/screen?t=${Date.now()}`;
}

export function vncViewerWsUrl(deviceId: string): string {
  const apiKey = import.meta.env.VITE_API_KEY ?? 'dev-agent-key';
  const wsBase = import.meta.env.VITE_WS_URL;
  if (wsBase) {
    return `${wsBase.replace(/\/$/, '')}/vnc/viewer/${encodeURIComponent(deviceId)}?key=${encodeURIComponent(apiKey)}`;
  }

  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const backendHost = import.meta.env.VITE_BACKEND_HOST ?? window.location.hostname;
  const backendPort = import.meta.env.VITE_BACKEND_PORT ?? '3008';
  return `${protocol}//${backendHost}:${backendPort}/vnc/viewer/${encodeURIComponent(deviceId)}?key=${encodeURIComponent(apiKey)}`;
}

export async function fetchScreenBlob(deviceId: string): Promise<Blob | null> {
  const response = await fetch(screenUrl(deviceId), {
    headers: {
      'x-agent-key': API_KEY,
    },
  });

  if (!response.ok) {
    return null;
  }

  return response.blob();
}

export function formatTimestamp(value?: string): string {
  if (!value) {
    return '—';
  }
  return new Date(value).toLocaleString();
}

export function formatAge(value?: string): string {
  if (!value) {
    return 'never';
  }
  const seconds = Math.floor((Date.now() - new Date(value).getTime()) / 1000);
  if (seconds < 60) {
    return `${seconds}s ago`;
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes}m ago`;
  }
  const hours = Math.floor(minutes / 60);
  return `${hours}h ago`;
}
