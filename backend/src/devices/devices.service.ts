import { Injectable, NotFoundException } from '@nestjs/common';
import { randomUUID } from 'crypto';
import {
  Command,
  CommandType,
  DeviceDetail,
  DeviceState,
  DeviceSummary,
} from './command.types';

const ONLINE_THRESHOLD_MS = 30_000;

@Injectable()
export class DevicesService {
  private readonly devices = new Map<string, DeviceState>();

  heartbeat(deviceId: string): {
    deviceId: string;
    lastHeartbeatAt: string;
    captureScreen: boolean;
  } {
    const device = this.getOrCreateDevice(deviceId);
    const now = new Date().toISOString();
    device.lastHeartbeatAt = now;
    return {
      deviceId,
      lastHeartbeatAt: now,
      captureScreen: device.captureScreen ?? false,
    };
  }

  listDevices(): DeviceSummary[] {
    return Array.from(this.devices.entries()).map(([deviceId, device]) =>
      this.toSummary(deviceId, device),
    );
  }

  getDevice(deviceId: string): DeviceDetail {
    const device = this.devices.get(deviceId);
    if (!device) {
      throw new NotFoundException(`Device ${deviceId} not found`);
    }

    const summary = this.toSummary(deviceId, device);
    return {
      ...summary,
      commands: [...device.commands].sort(
        (a, b) =>
          new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
      ),
    };
  }

  setCaptureScreen(
    deviceId: string,
    enabled: boolean,
  ): { deviceId: string; captureScreen: boolean } {
    const device = this.getOrCreateDevice(deviceId);
    device.captureScreen = enabled;
    return { deviceId, captureScreen: enabled };
  }

  saveScreenshot(
    deviceId: string,
    imageBase64: string,
  ): { deviceId: string; lastScreenshotAt: string } {
    const device = this.getOrCreateDevice(deviceId);
    const now = new Date().toISOString();
    device.lastScreenshotBase64 = imageBase64;
    device.lastScreenshotAt = now;
    return { deviceId, lastScreenshotAt: now };
  }

  getScreenshot(deviceId: string): { imageBase64: string; lastScreenshotAt: string } {
    const device = this.devices.get(deviceId);
    if (!device?.lastScreenshotBase64 || !device.lastScreenshotAt) {
      throw new NotFoundException(`No screenshot for device ${deviceId}`);
    }

    return {
      imageBase64: device.lastScreenshotBase64,
      lastScreenshotAt: device.lastScreenshotAt,
    };
  }

  getScreenshotBuffer(deviceId: string): Buffer {
    const { imageBase64 } = this.getScreenshot(deviceId);
    return Buffer.from(imageBase64, 'base64');
  }

  setVncTunnelConnected(
    deviceId: string,
    connected: boolean,
  ): { deviceId: string; vncTunnelConnected: boolean } {
    const device = this.getOrCreateDevice(deviceId);
    device.vncTunnelConnected = connected;
    if (!connected) {
      device.vncViewerConnected = false;
    }
    return { deviceId, vncTunnelConnected: connected };
  }

  setVncViewerConnected(
    deviceId: string,
    connected: boolean,
  ): { deviceId: string; vncViewerConnected: boolean } {
    const device = this.getOrCreateDevice(deviceId);
    device.vncViewerConnected = connected;
    return { deviceId, vncViewerConnected: connected };
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

  private toSummary(deviceId: string, device: DeviceState): DeviceSummary {
    const lastHeartbeatMs = device.lastHeartbeatAt
      ? new Date(device.lastHeartbeatAt).getTime()
      : 0;
    const online =
      lastHeartbeatMs > 0 &&
      Date.now() - lastHeartbeatMs <= ONLINE_THRESHOLD_MS;

    return {
      deviceId,
      lastHeartbeatAt: device.lastHeartbeatAt,
      captureScreen: device.captureScreen ?? false,
      lastScreenshotAt: device.lastScreenshotAt,
      online,
      vncTunnelConnected: device.vncTunnelConnected ?? false,
      vncViewerConnected: device.vncViewerConnected ?? false,
    };
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
