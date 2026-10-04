import { useCallback, useEffect, useRef, useState } from 'react';
import RFB from '@novnc/novnc';
import { vncViewerWsUrl } from '../api/client';

interface VncPanelProps {
  deviceId: string;
  vncTunnelConnected: boolean;
}

function refreshViewportScale(rfb: RFB): void {
  requestAnimationFrame(() => {
    rfb.scaleViewport = false;
    rfb.scaleViewport = true;
  });
}

export default function VncPanel({
  deviceId,
  vncTunnelConnected,
}: VncPanelProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const rfbRef = useRef<RFB | null>(null);
  const connectingRef = useRef(false);
  const [connected, setConnected] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [desktopInfo, setDesktopInfo] = useState<string | null>(null);

  const disconnect = useCallback(() => {
    connectingRef.current = false;
    setConnecting(false);
    if (rfbRef.current) {
      rfbRef.current.disconnect();
      rfbRef.current = null;
    }
    if (containerRef.current) {
      containerRef.current.innerHTML = '';
    }
    setConnected(false);
    setDesktopInfo(null);
  }, []);

  const connect = useCallback(() => {
    if (!containerRef.current || connectingRef.current || rfbRef.current) {
      return;
    }

    connectingRef.current = true;
    setConnecting(true);
    setError(null);
    setDesktopInfo(null);
    containerRef.current.innerHTML = '';

    try {
      const rfb = new RFB(containerRef.current, vncViewerWsUrl(deviceId), {
        credentials: { password: '' },
      });

      rfb.scaleViewport = true;
      rfb.resizeSession = false;
      rfb.showDotCursor = true;
      rfb.background = '#0f1117';
      rfb.clipViewport = false;

      rfb.addEventListener('connect', () => {
        connectingRef.current = false;
        setConnecting(false);
        setConnected(true);
        setError(null);
        refreshViewportScale(rfb);
      });

      rfb.addEventListener('disconnect', (event) => {
        connectingRef.current = false;
        setConnecting(false);
        setConnected(false);
        rfbRef.current = null;

        const detail = (event as CustomEvent<{ clean: boolean }>).detail;
        if (!detail.clean) {
          setError('VNC session disconnected. Check that a VNC server is running on port 5900.');
        }
      });

      rfb.addEventListener('desktopname', (event) => {
        const detail = (event as CustomEvent<{ name: string }>).detail;
        setDesktopInfo(detail.name || null);
        refreshViewportScale(rfb);
      });

      rfb.addEventListener('credentialsrequired', () => {
        const password = window.prompt('VNC password (leave blank if none):') ?? '';
        rfb.sendCredentials({ password });
      });

      rfb.addEventListener('securityfailure', (event) => {
        const detail = (event as CustomEvent<{ status: number; reason: string }>).detail;
        setError(detail.reason || 'VNC authentication failed');
      });

      rfbRef.current = rfb;
    } catch (e) {
      connectingRef.current = false;
      setConnecting(false);
      setError(e instanceof Error ? e.message : 'Failed to start VNC viewer');
    }
  }, [deviceId]);

  useEffect(() => {
    if (vncTunnelConnected && !connected && !connectingRef.current) {
      connect();
    }
  }, [vncTunnelConnected, connected, connect]);

  useEffect(() => {
    if (!vncTunnelConnected) {
      disconnect();
    }
  }, [vncTunnelConnected, disconnect]);

  useEffect(() => {
    return () => {
      disconnect();
    };
  }, [disconnect]);

  return (
    <div className="card vnc-panel">
      <h2 className="card-title">Remote control (VNC)</h2>
      <p className="form-hint" style={{ marginTop: 0 }}>
        Interactive screen control via reverse tunnel. Requires a VNC server on the
        device (port 5900) and an active agent tunnel.
      </p>

      <div className="vnc-status-row">
        <span
          className={`vnc-status-pill ${vncTunnelConnected ? 'online' : 'offline'}`}
        >
          Tunnel: {vncTunnelConnected ? 'connected' : 'waiting for device'}
        </span>
        <span
          className={`vnc-status-pill ${connected ? 'online' : connecting ? 'pending' : 'offline'}`}
        >
          Viewer:{' '}
          {connected ? 'live' : connecting ? 'connecting…' : 'disconnected'}
        </span>
        {desktopInfo && (
          <span className="vnc-status-pill online">{desktopInfo}</span>
        )}
      </div>

      <div className="button-row" style={{ marginBottom: 12 }}>
        <button
          className="primary"
          disabled={!vncTunnelConnected || connected || connecting}
          onClick={connect}
        >
          {connecting ? 'Connecting…' : 'Connect VNC'}
        </button>
        <button disabled={!connected && !connecting} onClick={disconnect}>
          Disconnect
        </button>
      </div>

      <div ref={containerRef} className="vnc-viewport" />

      {error && <p className="error">{error}</p>}
    </div>
  );
}
