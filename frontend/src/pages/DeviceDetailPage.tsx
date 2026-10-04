import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  formatTimestamp,
  getDevice,
  type DeviceDetail,
} from '../api/client';
import CommandPanel from '../components/CommandPanel';
import DeviceControlsPanel from '../components/DeviceControlsPanel';
import DeviceStatusBadge from '../components/DeviceStatusBadge';
import ScreenPanel from '../components/ScreenPanel';
import VncPanel from '../components/VncPanel';

function statusClass(status: string): string {
  switch (status) {
    case 'PENDING':
      return 'status-pending';
    case 'SENT':
      return 'status-sent';
    case 'DONE':
      return 'status-done';
    case 'FAILED':
      return 'status-failed';
    default:
      return '';
  }
}

export default function DeviceDetailPage() {
  const { deviceId = '' } = useParams();
  const [device, setDevice] = useState<DeviceDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  const loadDevice = useCallback(async () => {
    if (!deviceId) {
      return;
    }
    try {
      const data = await getDevice(deviceId);
      setDevice(data);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load device');
    }
  }, [deviceId]);

  useEffect(() => {
    loadDevice();
    const interval = setInterval(loadDevice, 5000);
    return () => clearInterval(interval);
  }, [loadDevice]);

  if (!deviceId) {
    return <p className="error">Missing device ID</p>;
  }

  return (
    <div>
      <Link to="/" className="back-link">
        ← Back to devices
      </Link>

      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 8 }}>
        <h1 className="page-title" style={{ margin: 0 }}>
          Device
        </h1>
        {device && <DeviceStatusBadge online={device.online} />}
      </div>

      <p className="page-subtitle mono">{deviceId}</p>

      {error && <p className="error">{error}</p>}

      {device && (
        <>
          <div className="card" style={{ marginBottom: 16 }}>
            <p style={{ margin: '4px 0' }}>
              <strong>Last heartbeat:</strong>{' '}
              {formatTimestamp(device.lastHeartbeatAt)}
            </p>
            <p style={{ margin: '4px 0' }}>
              <strong>Last screenshot:</strong>{' '}
              {formatTimestamp(device.lastScreenshotAt)}
            </p>
            <p style={{ margin: '4px 0' }}>
              <strong>VNC tunnel:</strong>{' '}
              {device.vncTunnelConnected ? 'Connected' : 'Not connected'}
            </p>
          </div>

          <div className="detail-grid">
            <VncPanel
              deviceId={deviceId}
              vncTunnelConnected={device.vncTunnelConnected}
            />
            <ScreenPanel
              deviceId={deviceId}
              captureScreen={device.captureScreen}
              lastScreenshotAt={device.lastScreenshotAt}
              onCaptureChange={(enabled) =>
                setDevice((current) =>
                  current ? { ...current, captureScreen: enabled } : current,
                )
              }
            />
            <CommandPanel deviceId={deviceId} onCommandSent={loadDevice} />
          </div>

          <DeviceControlsPanel deviceId={deviceId} onCommandSent={loadDevice} />

          <div className="card" style={{ marginTop: 16 }}>
            <h2 className="card-title">Command history</h2>
            <div className="command-history">
              {device.commands.length === 0 ? (
                <p style={{ color: '#9aa0a6', margin: 0 }}>No commands yet.</p>
              ) : (
                device.commands.map((command) => (
                  <div key={command.id} className="command-item">
                    <div className="command-meta">
                      <strong>{command.type}</strong>
                      <span className={`mono ${statusClass(command.status)}`}>
                        {command.status}
                      </span>
                      <span className="mono" style={{ color: '#9aa0a6' }}>
                        {formatTimestamp(command.createdAt)}
                      </span>
                    </div>
                    <div className="mono" style={{ color: '#9aa0a6' }}>
                      {command.id}
                    </div>
                    {command.payload?.result !== undefined && (
                      <pre
                        className="mono"
                        style={{
                          margin: '8px 0 0',
                          whiteSpace: 'pre-wrap',
                          color: '#c4c7c5',
                        }}
                      >
                        {String(command.payload.result)}
                      </pre>
                    )}
                  </div>
                ))
              )}
            </div>
          </div>
        </>
      )}
    </div>
  );
}
