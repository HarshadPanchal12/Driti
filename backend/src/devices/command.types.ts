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
  payload?: unknown;
  status: CommandStatus;
  createdAt: string;
}

export interface DeviceState {
  lastHeartbeatAt?: string;
  commands: Command[];
  captureScreen?: boolean;
  lastScreenshotAt?: string;
  lastScreenshotBase64?: string;
  vncTunnelConnected?: boolean;
  vncViewerConnected?: boolean;
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
