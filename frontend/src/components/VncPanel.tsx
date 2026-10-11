import React, { useCallback, useEffect, useRef, useState } from 'react';
import { vncViewerWsUrl } from '../api/client';

interface VncPanelProps {
  deviceId: string;
  vncTunnelConnected: boolean;
}

/**
  * Remote screen viewer using native MediaProjection JPEG stream.
  * Supports click-to-tap, drag-to-swipe/scroll, text typing,
  * and global navigation (Back, Home, Recents).
  */
export default function VncPanel({ deviceId, vncTunnelConnected }: VncPanelProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const frameWsRef = useRef<WebSocket | null>(null);

  const [connected, setConnected] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const [fps, setFps] = useState(0);
  const [resolution, setResolution] = useState<{ width: number; height: number } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [touchIndicator, setTouchIndicator] = useState<{ x: number; y: number } | null>(null);

  const frameCountRef = useRef(0);
  const isDraggingRef = useRef(false);
  const dragStartRef = useRef<{ x: number; y: number; time: number } | null>(null);

  // ── FPS Counter ──────────────────────────────────────────────────────────
  useEffect(() => {
    const timer = setInterval(() => {
      setFps(frameCountRef.current);
      frameCountRef.current = 0;
    }, 1000);
    return () => clearInterval(timer);
  }, []);

  // ── Frame drawing ────────────────────────────────────────────────────────
  const drawFrame = useCallback((blob: Blob) => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const url = URL.createObjectURL(blob);
    const img = new Image();
    img.onload = () => {
      if (canvas.width !== img.naturalWidth || canvas.height !== img.naturalHeight) {
        canvas.width = img.naturalWidth;
        canvas.height = img.naturalHeight;
        setResolution({ width: img.naturalWidth, height: img.naturalHeight });
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
    setConnected(false);
    setConnecting(false);
    setFps(0);
  }, []);

  const connect = useCallback(() => {
    if (frameWsRef.current || connecting) return;
    setConnecting(true);
    setError(null);

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

  useEffect(() => {
    if (vncTunnelConnected && !connected && !connecting) {
      connect();
    }
  }, [vncTunnelConnected, connected, connecting, connect]);

  useEffect(() => {
    if (!vncTunnelConnected) disconnect();
  }, [vncTunnelConnected, disconnect]);

  useEffect(() => () => disconnect(), [disconnect]);

  // ── Input event sending ──────────────────────────────────────────────────
  function sendControl(msg: object) {
    const ws = frameWsRef.current;
    if (ws?.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(msg));
    }
  }

  // ── Mouse & Touch Gesture Handlers ───────────────────────────────────────
  function handlePointerDown(clientX: number, clientY: number) {
    const canvas = canvasRef.current;
    if (!canvas || !connected) return;
    const rect = canvas.getBoundingClientRect();
    const x = clientX - rect.left;
    const y = clientY - rect.top;

    isDraggingRef.current = true;
    dragStartRef.current = { x, y, time: Date.now() };
    setTouchIndicator({ x, y });
  }

  function handlePointerMove(clientX: number, clientY: number) {
    if (!isDraggingRef.current || !canvasRef.current) return;
    const rect = canvasRef.current.getBoundingClientRect();
    const x = clientX - rect.left;
    const y = clientY - rect.top;
    setTouchIndicator({ x, y });
  }

  function handlePointerUp(clientX: number, clientY: number) {
    const canvas = canvasRef.current;
    if (!canvas || !connected || !dragStartRef.current) {
      isDraggingRef.current = false;
      dragStartRef.current = null;
      setTouchIndicator(null);
      return;
    }

    const rect = canvas.getBoundingClientRect();
    const endX = clientX - rect.left;
    const endY = clientY - rect.top;
    const start = dragStartRef.current;

    const dx = endX - start.x;
    const dy = endY - start.y;
    const distance = Math.hypot(dx, dy);
    const duration = Math.max(50, Math.min(1000, Date.now() - start.time));

    if (distance < 10) {
      // Single tap
      const nx = start.x / rect.width;
      const ny = start.y / rect.height;
      sendControl({ type: 'tap', nx, ny });
    } else {
      // Drag / Swipe gesture
      const nx1 = start.x / rect.width;
      const ny1 = start.y / rect.height;
      const nx2 = endX / rect.width;
      const ny2 = endY / rect.height;
      sendControl({
        type: 'swipe',
        nx1,
        ny1,
        nx2,
        ny2,
        duration,
      });
    }

    isDraggingRef.current = false;
    dragStartRef.current = null;
    setTimeout(() => setTouchIndicator(null), 150);
  }

  return (
    <div className="card vnc-panel">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8 }}>
        <div>
          <h2 className="card-title" style={{ margin: 0 }}>Remote Screen &amp; Control</h2>
          <p className="form-hint" style={{ margin: '4px 0 0' }}>
            Live frame stream. Click to tap, click &amp; drag to swipe/scroll.
          </p>
        </div>

        <div className="vnc-status-row" style={{ margin: 0 }}>
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
          {resolution && (
            <span className="vnc-status-pill online mono">
              {resolution.width} × {resolution.height}
            </span>
          )}
        </div>
      </div>

      <div className="button-row" style={{ margin: '12px 0' }}>
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

      {connected && (
        <div className="vnc-screen-wrapper">
          <div className="vnc-device-frame" style={{ position: 'relative' }}>
            <canvas
              ref={canvasRef}
              id="vnc-canvas"
              className="vnc-canvas"
              onMouseDown={(e) => handlePointerDown(e.clientX, e.clientY)}
              onMouseMove={(e) => handlePointerMove(e.clientX, e.clientY)}
              onMouseUp={(e) => handlePointerUp(e.clientX, e.clientY)}
              onMouseLeave={() => {
                if (isDraggingRef.current) {
                  isDraggingRef.current = false;
                  dragStartRef.current = null;
                  setTouchIndicator(null);
                }
              }}
              onTouchStart={(e) => {
                if (e.touches.length > 0) {
                  handlePointerDown(e.touches[0].clientX, e.touches[0].clientY);
                }
              }}
              onTouchMove={(e) => {
                if (e.touches.length > 0) {
                  handlePointerMove(e.touches[0].clientX, e.touches[0].clientY);
                }
              }}
              onTouchEnd={(e) => {
                if (e.changedTouches.length > 0) {
                  handlePointerUp(e.changedTouches[0].clientX, e.changedTouches[0].clientY);
                }
              }}
            />

            {/* Touch ripple indicator */}
            {touchIndicator && (
              <div
                style={{
                  position: 'absolute',
                  left: touchIndicator.x,
                  top: touchIndicator.y,
                  width: 28,
                  height: 28,
                  marginLeft: -14,
                  marginTop: -14,
                  borderRadius: '50%',
                  backgroundColor: 'rgba(66, 133, 244, 0.45)',
                  border: '2px solid rgba(255, 255, 255, 0.9)',
                  pointerEvents: 'none',
                  transition: 'transform 0.05s ease-out',
                }}
              />
            )}

            {/* Quick Android navigation buttons */}
            <div className="vnc-nav-bar">
              <button
                type="button"
                className="vnc-nav-btn"
                title="Back button"
                onClick={() => sendControl({ type: 'back' })}
              >
                ◀ Back
              </button>
              <button
                type="button"
                className="vnc-nav-btn"
                title="Home button"
                onClick={() => sendControl({ type: 'home' })}
              >
                ⌂ Home
              </button>
              <button
                type="button"
                className="vnc-nav-btn"
                title="Recent apps"
                onClick={() => sendControl({ type: 'recents' })}
              >
                ▢ Recents
              </button>
            </div>
          </div>
        </div>
      )}

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
