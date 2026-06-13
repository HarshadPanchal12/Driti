import { Injectable, NotFoundException } from '@nestjs/common';
import { randomUUID } from 'crypto';
import { Command, CommandType, DeviceState } from './command.types';

@Injectable()
export class DevicesService {
  private readonly devices = new Map<string, DeviceState>();

  heartbeat(deviceId: string): { deviceId: string; lastHeartbeatAt: string } {
    const device = this.getOrCreateDevice(deviceId);
    const now = new Date().toISOString();
    device.lastHeartbeatAt = now;
    return { deviceId, lastHeartbeatAt: now };
  }

  enqueueCommand(
    deviceId: string,
    type: CommandType,
    payload?: unknown,
  ): Command {
    const device = this.getOrCreateDevice(deviceId);
    const command: Command = {
      id: randomUUID(),
      type,
      payload,
      status: 'PENDING',
      createdAt: new Date().toISOString(),
    };
    device.commands.push(command);
    return command;
  }

  pollCommand(deviceId: string): Command | { type: 'NONE' } {
    const device = this.getOrCreateDevice(deviceId);
    const pending = device.commands
      .filter((c) => c.status === 'PENDING')
      .sort(
        (a, b) =>
          new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime(),
      );

    if (pending.length === 0) {
      return { type: 'NONE' };
    }

    const command = pending[0];
    command.status = 'SENT';
    return command;
  }

  submitResult(
    deviceId: string,
    commandId: string,
    success: boolean,
    output?: unknown,
  ): Command {
    const device = this.getOrCreateDevice(deviceId);
    const command = device.commands.find((c) => c.id === commandId);

    if (!command) {
      throw new NotFoundException(`Command ${commandId} not found`);
    }

    command.status = success ? 'DONE' : 'FAILED';
    if (output !== undefined) {
      command.payload = { ...(command.payload as object), result: output };
    }

    return command;
  }

  private getOrCreateDevice(deviceId: string): DeviceState {
    let device = this.devices.get(deviceId);
    if (!device) {
      device = { commands: [] };
      this.devices.set(deviceId, device);
    }
    return device;
  }
}
