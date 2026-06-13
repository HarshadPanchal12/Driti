export type CommandType = 'ENABLE_ADB' | 'DISABLE_ADB' | 'REBOOT' | 'SHELL';

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
}
