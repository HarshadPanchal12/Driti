import { Link, Route, Routes } from 'react-router-dom';
import DeviceDetailPage from './pages/DeviceDetailPage';
import DeviceListPage from './pages/DeviceListPage';

export default function App() {
  return (
    <div className="app">
      <header className="header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Link to="/" className="logo">
          Remote Agent Dashboard
        </Link>
        <a
          href="/app-debug.apk"
          download="drishti-agent-latest.apk"
          style={{
            color: '#a8c7fa',
            textDecoration: 'none',
            fontSize: '0.85rem',
            padding: '5px 12px',
            borderRadius: '6px',
            border: '1px solid #3c4043',
            background: 'rgba(255, 255, 255, 0.05)',
          }}
        >
          ⬇ Download Agent APK
        </a>
      </header>
      <main className="main">
        <Routes>
          <Route path="/" element={<DeviceListPage />} />
          <Route path="/devices/:deviceId" element={<DeviceDetailPage />} />
        </Routes>
      </main>
    </div>
  );
}
