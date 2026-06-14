import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  formatAge,
  formatTimestamp,
  listDevices,
  type DeviceSummary,
} from '../api/client';
import DeviceStatusBadge from '../components/DeviceStatusBadge';

export default function DeviceListPage() {
  const [devices, setDevices] = useState<DeviceSummary[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const data = await listDevices();
        if (!cancelled) {
          setDevices(data);
          setError(null);
        }
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : 'Failed to load devices');
        }
      }
    }

    load();
    const interval = setInterval(load, 5000);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, []);

  return (
    <div>
      <h1 className="page-title">Devices</h1>
      <p className="page-subtitle">
        Tablets that have checked in via heartbeat appear here.
      </p>

      {error && <p className="error">{error}</p>}

      <div className="card">
        {devices.length === 0 ? (
          <p style={{ color: '#9aa0a6', margin: 0 }}>
            No devices yet. Open the Remote Agent app on a tablet to register.
          </p>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Device ID</th>
                <th>Status</th>
                <th>Last heartbeat</th>
                <th>Last screenshot</th>
                <th>Live view</th>
              </tr>
            </thead>
            <tbody>
              {devices.map((device) => (
                <tr key={device.deviceId}>
                  <td>
                    <Link to={`/devices/${device.deviceId}`} className="mono">
                      {device.deviceId}
                    </Link>
                  </td>
                  <td>
                    <DeviceStatusBadge online={device.online} />
                  </td>
                  <td>{formatTimestamp(device.lastHeartbeatAt)}</td>
                  <td>{formatAge(device.lastScreenshotAt)}</td>
                  <td>{device.captureScreen ? 'On' : 'Off'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
