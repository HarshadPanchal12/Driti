import { useState } from 'react';
import { enqueueCommand } from '../api/client';

interface DeviceControlsPanelProps {
  deviceId: string;
  onCommandSent: () => void;
}

export default function DeviceControlsPanel({
  deviceId,
  onCommandSent,
}: DeviceControlsPanelProps) {
  const [packageName, setPackageName] = useState('com.example.app');
  const [apkPath, setApkPath] = useState('/sdcard/Download/app.apk');
  const [apkUrl, setApkUrl] = useState(
    'https://24buy7-product.s3.ap-south-1.amazonaws.com/launcher/app-release-launcher.apk',
  );
  const [activity, setActivity] = useState('');
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function send(
    type:
      | 'INSTALL_APP'
      | 'INSTALL_FROM_URL'
      | 'UNINSTALL_APP'
      | 'OPEN_APP'
      | 'CLOSE_APP'
      | 'HIDE_NAV'
      | 'SHOW_NAV',
    payload?: Record<string, unknown>,
  ) {
    setLoading(true);
    setMessage(null);
    setError(null);
    try {
      const command = await enqueueCommand(deviceId, type, payload);
      setMessage(`Queued ${type} (${command.id})`);
      onCommandSent();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to enqueue command');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="card">
      <h2 className="card-title">App &amp; system controls</h2>

      <div className="form-section">
        <h3 className="form-section-title">Package name</h3>
        <p className="form-hint">
          Used for open, close, and uninstall. Example:{' '}
          <code className="mono">com.android.chrome</code>
        </p>
        <input
          className="text-input mono"
          value={packageName}
          onChange={(e) => setPackageName(e.target.value)}
          placeholder="com.example.app"
        />
        <div className="button-row" style={{ marginTop: 8 }}>
          <button
            className="primary"
            disabled={loading || !packageName.trim()}
            onClick={() =>
              send('OPEN_APP', {
                package: packageName.trim(),
                ...(activity.trim() ? { activity: activity.trim() } : {}),
              })
            }
          >
            Open app
          </button>
          <button
            disabled={loading || !packageName.trim()}
            onClick={() => send('CLOSE_APP', { package: packageName.trim() })}
          >
            Close app
          </button>
          <button
            className="danger"
            disabled={loading || !packageName.trim()}
            onClick={() => {
              if (
                window.confirm(`Uninstall ${packageName.trim()} from this device?`)
              ) {
                void send('UNINSTALL_APP', { package: packageName.trim() });
              }
            }}
          >
            Uninstall app
          </button>
        </div>
        <label className="form-label" htmlFor="activity-name">
          Activity (optional — leave blank to use launcher)
        </label>
        <input
          id="activity-name"
          className="text-input mono"
          value={activity}
          onChange={(e) => setActivity(e.target.value)}
          placeholder="com.example.app.MainActivity"
        />
      </div>

      <div className="form-section">
        <h3 className="form-section-title">Install from URL</h3>
        <p className="form-hint">
          Downloads the APK over HTTPS on the device, then installs with root.
        </p>
        <input
          className="text-input mono"
          value={apkUrl}
          onChange={(e) => setApkUrl(e.target.value)}
          placeholder="https://example.com/app.apk"
        />
        <div className="button-row" style={{ marginTop: 8 }}>
          <button
            className="primary"
            disabled={loading || !apkUrl.trim()}
            onClick={() => send('INSTALL_FROM_URL', { url: apkUrl.trim() })}
          >
            Install from URL
          </button>
        </div>
      </div>

      <div className="form-section">
        <h3 className="form-section-title">Install APK (local path)</h3>
        <p className="form-hint">
          APK must already exist on the tablet filesystem (root path).
        </p>
        <input
          className="text-input mono"
          value={apkPath}
          onChange={(e) => setApkPath(e.target.value)}
          placeholder="/sdcard/Download/app.apk"
        />
        <div className="button-row" style={{ marginTop: 8 }}>
          <button
            className="primary"
            disabled={loading || !apkPath.trim()}
            onClick={() => send('INSTALL_APP', { apkPath: apkPath.trim() })}
          >
            Install APK
          </button>
        </div>
      </div>

      <div className="form-section">
        <h3 className="form-section-title">Kiayo navigation bars</h3>
        <p className="form-hint">
          Hide/show system navigation on Kiayo K518 tablets (setprop + broadcasts).
        </p>
        <div className="button-row">
          <button disabled={loading} onClick={() => send('HIDE_NAV')}>
            Hide navigation
          </button>
          <button disabled={loading} onClick={() => send('SHOW_NAV')}>
            Show navigation
          </button>
        </div>
      </div>

      {message && <p className="success">{message}</p>}
      {error && <p className="error">{error}</p>}
    </div>
  );
}
