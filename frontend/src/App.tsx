import { Link, Route, Routes } from 'react-router-dom';
import DeviceDetailPage from './pages/DeviceDetailPage';
import DeviceListPage from './pages/DeviceListPage';

export default function App() {
  return (
    <div className="app">
      <header className="header">
        <Link to="/" className="logo">
          Remote Agent Dashboard
        </Link>
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
