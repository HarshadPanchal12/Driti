import { useCallback, useEffect, useState } from 'react';
import {
  fetchScreenBlob,
  setScreenCapture,
} from '../api/client';

interface ScreenPanelProps {
  deviceId: string;
  captureScreen: boolean;
  lastScreenshotAt?: string;
  onCaptureChange: (enabled: boolean) => void;
}

export default function ScreenPanel({
  deviceId,
  captureScreen,
  lastScreenshotAt,
  onCaptureChange,
}: ScreenPanelProps) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [imageUrl, setImageUrl] = useState<string | null>(null);
  const [waiting, setWaiting] = useState(false);

  const refreshImage = useCallback(async () => {
    const blob = await fetchScreenBlob(deviceId);
    if (!blob) {
      setWaiting(true);
      return;
    }

    setWaiting(false);
    const url = URL.createObjectURL(blob);
    setImageUrl((previous) => {
      if (previous) {
        URL.revokeObjectURL(previous);
      }
      return url;
    });
  }, [deviceId]);

  useEffect(() => {
    if (!captureScreen && !lastScreenshotAt) {
      return;
    }

    void refreshImage();

    if (!captureScreen) {
      return;
    }

    const interval = setInterval(() => {
      void refreshImage();
    }, 2000);

    return () => clearInterval(interval);
  }, [captureScreen, lastScreenshotAt, refreshImage]);

  useEffect(() => {
    return () => {
      if (imageUrl) {
        URL.revokeObjectURL(imageUrl);
      }
    };
  }, [imageUrl]);

  async function toggleCapture(enabled: boolean) {
    setLoading(true);
    setError(null);
    try {
      await setScreenCapture(deviceId, enabled);
      onCaptureChange(enabled);
      if (enabled) {
        await refreshImage();
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to toggle live view');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="card screen-panel">
      <h2 className="card-title">Screen</h2>
      <div className="button-row" style={{ marginBottom: 12 }}>
        <button
          className="primary"
          disabled={loading || captureScreen}
          onClick={() => toggleCapture(true)}
        >
          Start live view
        </button>
        <button
          disabled={loading || !captureScreen}
          onClick={() => toggleCapture(false)}
        >
          Stop live view
        </button>
      </div>

      {imageUrl ? (
        <img src={imageUrl} alt="Device screen" />
      ) : (
        <div className="screen-placeholder">
          No screenshot yet. Start live view or send a SCREENSHOT command.
        </div>
      )}

      {waiting && captureScreen && (
        <p className="error">Waiting for screenshot from device…</p>
      )}

      {error && <p className="error">{error}</p>}
    </div>
  );
}
