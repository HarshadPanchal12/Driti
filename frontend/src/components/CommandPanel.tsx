import { useState } from 'react';
import {
  enqueueCommand,
  type CommandType,
} from '../api/client';

interface CommandPanelProps {
  deviceId: string;
  onCommandSent: () => void;
}

export default function CommandPanel({
  deviceId,
  onCommandSent,
}: CommandPanelProps) {
  const [shellCommand, setShellCommand] = useState('echo hello');
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function send(type: CommandType, payload?: Record<string, unknown>) {
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
      <h2 className="card-title">Commands</h2>
      <div className="button-row">
        <button disabled={loading} onClick={() => send('ENABLE_ADB')}>
          Enable ADB
        </button>
        <button disabled={loading} onClick={() => send('DISABLE_ADB')}>
          Disable ADB
        </button>
        <button disabled={loading} onClick={() => send('SCREENSHOT')}>
          Screenshot
        </button>
        <button
          className="danger"
          disabled={loading}
          onClick={() => {
            if (window.confirm('Reboot this device?')) {
              void send('REBOOT');
            }
          }}
        >
          Reboot
        </button>
      </div>

      <div style={{ marginTop: 16 }}>
        <label htmlFor="shell-command" className="card-title">
          Shell command
        </label>
        <textarea
          id="shell-command"
          value={shellCommand}
          onChange={(e) => setShellCommand(e.target.value)}
          placeholder="pm list packages -3"
        />
        <div className="button-row" style={{ marginTop: 8 }}>
          <button
            className="primary"
            disabled={loading || !shellCommand.trim()}
            onClick={() => send('SHELL', { command: shellCommand.trim() })}
          >
            Run shell
          </button>
        </div>
      </div>

      {message && <p className="success">{message}</p>}
      {error && <p className="error">{error}</p>}
    </div>
  );
}
