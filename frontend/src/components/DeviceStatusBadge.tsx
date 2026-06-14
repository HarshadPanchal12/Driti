import type { DeviceSummary } from '../api/client';

interface DeviceStatusBadgeProps {
  online: boolean;
}

export default function DeviceStatusBadge({ online }: DeviceStatusBadgeProps) {
  return (
    <span className={`badge ${online ? 'badge-online' : 'badge-offline'}`}>
      {online ? 'Online' : 'Offline'}
    </span>
  );
}

export function DeviceIdCell({ device }: { device: DeviceSummary }) {
  return <span className="mono">{device.deviceId}</span>;
}
