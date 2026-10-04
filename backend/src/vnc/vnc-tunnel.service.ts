import { Injectable, Logger } from '@nestjs/common';
import { IncomingMessage } from 'http';
import { Server as HttpServer } from 'http';
import { WebSocket, WebSocketServer, type RawData } from 'ws';
import { DevicesService } from '../devices/devices.service';

type TunnelRole = 'agent' | 'viewer';

interface ParsedVncPath {
  role: TunnelRole;
  deviceId: string;
}

@Injectable()
export class VncTunnelService {
  private readonly logger = new Logger(VncTunnelService.name);
  private readonly expectedKey = process.env.AGENT_API_KEY ?? 'dev-agent-key';
  private wss: WebSocketServer | null = null;

  private readonly agentSockets = new Map<string, WebSocket>();
  private readonly viewerSockets = new Map<string, WebSocket>();

  constructor(private readonly devicesService: DevicesService) {}

  attach(httpServer: HttpServer): void {
    if (this.wss) {
      return;
    }

    this.wss = new WebSocketServer({ noServer: true });

    httpServer.on('upgrade', (request, socket, head) => {
      const parsed = this.parsePath(request.url);
      if (!parsed) {
        return;
      }

      if (!this.isAuthorized(request)) {
        socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
        socket.destroy();
        return;
      }

      this.wss?.handleUpgrade(request, socket, head, (ws) => {
        this.wss?.emit('connection', ws, request, parsed);
      });
    });

    this.wss.on(
      'connection',
      (ws: WebSocket, request: IncomingMessage, parsed: ParsedVncPath) => {
        if (parsed.role === 'agent') {
          this.handleAgentConnection(parsed.deviceId, ws);
          return;
        }

        this.handleViewerConnection(parsed.deviceId, ws);
      },
    );

    this.logger.log('VNC tunnel WebSocket relay ready at /vnc/{agent|viewer}/:deviceId');
  }

  private parsePath(url?: string): ParsedVncPath | null {
    if (!url) {
      return null;
    }

    const pathname = url.split('?')[0];
    const match = pathname.match(/^\/vnc\/(agent|viewer)\/([^/]+)$/);
    if (!match) {
      return null;
    }

    return {
      role: match[1] as TunnelRole,
      deviceId: decodeURIComponent(match[2]),
    };
  }

  private isAuthorized(request: IncomingMessage): boolean {
    const headerKey = request.headers['x-agent-key'];
    if (typeof headerKey === 'string' && headerKey === this.expectedKey) {
      return true;
    }

    const url = new URL(request.url ?? '/', 'http://localhost');
    const queryKey = url.searchParams.get('key');
    return queryKey === this.expectedKey;
  }

  private sendAgentControl(deviceId: string, type: string): void {
    const agent = this.agentSockets.get(deviceId);
    if (agent?.readyState === WebSocket.OPEN) {
      agent.send(JSON.stringify({ type }));
    }
  }

  private relayToViewer(deviceId: string, data: RawData): void {
    const viewer = this.viewerSockets.get(deviceId);
    if (viewer?.readyState === WebSocket.OPEN) {
      viewer.send(data, { binary: true });
    }
  }

  private handleAgentConnection(deviceId: string, ws: WebSocket): void {
    const existing = this.agentSockets.get(deviceId);
    if (existing) {
      existing.close(4000, 'Replaced by new agent tunnel');
    }

    this.agentSockets.set(deviceId, ws);
    this.devicesService.setVncTunnelConnected(deviceId, true);
    this.logger.log(`VNC agent tunnel connected for ${deviceId}`);

    ws.on('message', (data) => {
      this.relayToViewer(deviceId, data);
    });

    const cleanup = () => {
      if (this.agentSockets.get(deviceId) === ws) {
        this.agentSockets.delete(deviceId);
        this.devicesService.setVncTunnelConnected(deviceId, false);
        this.logger.log(`VNC agent tunnel disconnected for ${deviceId}`);
      }

      const viewer = this.viewerSockets.get(deviceId);
      if (viewer?.readyState === WebSocket.OPEN) {
        viewer.close(4001, 'Device VNC tunnel closed');
      }
    };

    ws.on('close', cleanup);
    ws.on('error', (error) => {
      this.logger.warn(`VNC agent tunnel error for ${deviceId}: ${error.message}`);
      cleanup();
    });
  }

  private handleViewerConnection(deviceId: string, ws: WebSocket): void {
    const agent = this.agentSockets.get(deviceId);
    if (!agent || agent.readyState !== WebSocket.OPEN) {
      ws.close(4404, 'Device VNC tunnel not available');
      return;
    }

    const existingViewer = this.viewerSockets.get(deviceId);
    if (existingViewer && existingViewer.readyState === WebSocket.OPEN) {
      existingViewer.close(4000, 'Replaced by new viewer');
      this.sendAgentControl(deviceId, 'viewer_disconnected');
    }

    this.viewerSockets.set(deviceId, ws);
    this.devicesService.setVncViewerConnected(deviceId, true);
    this.logger.log(`VNC viewer connected for ${deviceId}`);

    // Open a fresh local VNC TCP session so the RFB handshake starts cleanly.
    this.sendAgentControl(deviceId, 'viewer_connected');

    ws.on('message', (data) => {
      if (agent.readyState === WebSocket.OPEN) {
        agent.send(data, { binary: true });
      }
    });

    const cleanup = () => {
      if (this.viewerSockets.get(deviceId) === ws) {
        this.viewerSockets.delete(deviceId);
        this.devicesService.setVncViewerConnected(deviceId, false);
        this.sendAgentControl(deviceId, 'viewer_disconnected');
        this.logger.log(`VNC viewer disconnected for ${deviceId}`);
      }
    };

    ws.on('close', cleanup);
    ws.on('error', (error) => {
      this.logger.warn(`VNC viewer error for ${deviceId}: ${error.message}`);
      cleanup();
    });
  }
}
