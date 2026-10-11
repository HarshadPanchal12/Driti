import { useCallback, useEffect, useRef, useState } from 'react';
import { vncViewerWsUrl } from '../api/client';

interface VncPanelProps {
  deviceId: string;
  vncTunnelConnected: boolean;
}

/**
 * Remote screen viewer using JPEG frame streaming over WebSocket.
 * No VNC server or RFB protocol required — the Android agent streams
 * JPEG frames directly from MediaProjection.
 *
 * Input events (tap, swipe, text) are sent back over a second control
 * WebSocket channel so they don't block incoming frames.
 */
export default function VncPanel({ deviceId, vncTunnelConnected }: VncPanelProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const frameWsRef = useRef<WebSocket | null>(null);
  const ctrlWsRef = useRef<WebSocket | null>(null);

  const [connected, setConnected] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fps, setFps] = useState(0);
  const frameCountRef = useRef(0);

  // FPS counter
  useEffect(() => {
    if (!connected) return;
    const id = setInterval(() => {
      setFps(frameCountRef.current);
      frameCountRef.current = 0;
    }, 1000);
    return () => clearInterval(id);
  }, [connected]);

  // ── Canvas rendering ─────────────────────────────────────────────────────

  const drawFrame = useCallback((blob: Blob) => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const url = URL.createObjectURL(blob);
    const img = new Image();
    img.onload = () => {
      // Auto-size canvas to first frame dimensions
      if (canvas.width !== img.naturalWidth) {
        canvas.width = img.naturalWidth;
        canvas.height = img.naturalHeight;
      }
      ctx.drawImage(img, 0, 0);
      URL.revokeObjectURL(url);
      frameCountRef.current++;
    };
    img.src = url;
  }, []);

  // ── Connection lifecycle ─────────────────────────────────────────────────

  const disconnect = useCallback(() => {
    frameWsRef.current?.close(1000, 'User disconnected');
    frameWsRef.current = null;
    ctrlWsRef.current?.close(1000, 'User disconnected');
    ctrlWsRef.current = null;
    setConnected(false);
    setConnecting(false);
    setFps(0);
  }, []);

  const connect = useCallback(() => {
    if (frameWsRef.current || connecting) return;
    setConnecting(true);
    setError(null);

    // Frame socket — receives binary JPEG frames from agent
    const frameWs = new WebSocket(vncViewerWsUrl(deviceId));
    frameWs.binaryType = 'blob';
    frameWsRef.current = frameWs;

    frameWs.onopen = () => {
      setConnected(true);
      setConnecting(false);
    };

    frameWs.onmessage = (e: MessageEvent) => {
      if (e.data instanceof Blob) {
        drawFrame(e.data);
      }
    };

    frameWs.onclose = (e) => {
      frameWsRef.current = null;
      ctrlWsRef.current?.close();
      ctrlWsRef.current = null;
      setConnected(false);
      setConnecting(false);
      if (e.code !== 1000 && e.code !== 1001) {
        setError(`Disconnected: ${e.reason || 'connection lost'} (code ${e.code})`);
      }
    };

    frameWs.onerror = () => {
      setError('WebSocket connection failed — is the backend reachable?');
    };
  }, [deviceId, connecting, drawFrame]);

  // Auto-connect when tunnel is ready
  useEffect(() => {
    if (vncTunnelConnected && !connected && !connecting) {
      connect();
    }
  }, [vncTunnelConnected, connected, connecting, connect]);

  // Auto-disconnect when tunnel drops
  useEffect(() => {
    if (!vncTunnelConnected) disconnect();
  }, [vncTunnelConnected, disconnect]);

  // Cleanup on unmount
  useEffect(() => () => disconnect(), [disconnect]);

  // ── Input event sending ─────────────────────────────────────────────────

  function sendControl(msg: object) {
    const ws = frameWsRef.current;
    if (ws?.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(msg));
    }
  }

  function handleCanvasClick(e: React.MouseEvent<HTMLCanvasElement>) {
    const canvas = canvasRef.current;
    if (!canvas || !connected) return;
    const rect = canvas.getBoundingClientRect();
    const nx = (e.clientX - rect.left) / rect.width;
    const ny = (e.clientY - rect.top) / rect.height;
    sendControl({ type: 'tap', nx, ny });
  }

  function handleCanvasTouchEnd(e: React.TouchEvent<HTMLCanvasElement>) {
    const canvas = canvasRef.current;
    if (!canvas || !connected || e.changedTouches.length === 0) return;
    const touch = e.changedTouches[0];
    const rect = canvas.getBoundingClientRect();
    const nx = (touch.clientX - rect.left) / rect.width;
    const ny = (touch.clientY - rect.top) / rect.height;
    sendControl({ type: 'tap', nx, ny });
    e.preventDefault();
  }

  // ── Render ───────────────────────────────────────────────────────────────

  return (
    <div className="card vnc-panel">
      <h2 className="card-title">Remote Screen &amp; Control</h2>
      <p className="form-hint" style={{ marginTop: 0 }}>
        Live JPEG frame stream via MediaProjection. Click/tap on the canvas to
        inject gestures. No VNC server or root required.
      </p>

      <div className="vnc-status-row">
        <span className={`vnc-status-pill ${vncTunnelConnected ? 'online' : 'offline'}`}>
          Tunnel: {vncTunnelConnected ? 'connected' : 'waiting for device'}
        </span>
        <span
          className={`vnc-status-pill ${
            connected ? 'online' : connecting ? 'pending' : 'offline'
          }`}
        >
          Stream: {connected ? `live · ${fps} fps` : connecting ? 'connecting…' : 'idle'}
        </span>
      </div>

      <div className="button-row" style={{ marginBottom: 12 }}>
        <button
          id="vnc-connect-btn"
          className="primary"
          disabled={!vncTunnelConnected || connected || connecting}
          onClick={connect}
        >
          {connecting ? 'Connecting…' : 'Connect'}
        </button>
        <button
          id="vnc-disconnect-btn"
          disabled={!connected && !connecting}
          onClick={disconnect}
        >
          Disconnect
        </button>
      </div>

      {/* Frame canvas — clicks/touches become device input events */}
      <canvas
        ref={canvasRef}
        id="vnc-canvas"
        className="vnc-viewport"
        style={{
          cursor: connected ? 'crosshair' : 'default',
          maxWidth: '100%',
          display: connected ? 'block' : 'none',
          border: '1px solid #3c4043',
          borderRadius: 4,
        }}
        onClick={handleCanvasClick}
        onTouchEnd={handleCanvasTouchEnd}
      />

      {!connected && !connecting && (
        <div className="screen-placeholder">
          {vncTunnelConnected
            ? 'Click Connect to start viewing the device screen.'
            : 'Waiting for the device agent to connect…'}
        </div>
      )}

      {error && <p className="error">{error}</p>}
    </div>
  );
}
