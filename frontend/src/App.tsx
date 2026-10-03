import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth';
import { Layout } from './components/Layout';
import { AuditPage } from './pages/AuditPage';
import { AuthPage } from './pages/AuthPage';
import { FlagPage } from './pages/FlagPage';
import { FlagsPage } from './pages/FlagsPage';
import { KeysPage } from './pages/KeysPage';
import { MembersPage } from './pages/MembersPage';

export function App() {
  const { session, loading } = useAuth();

  if (loading) return <p className="page-status">Loading…</p>;

  if (!session) {
    return (
      <Routes>
        <Route path="/signup" element={<AuthPage mode="signup" />} />
        <Route path="*" element={<AuthPage mode="login" />} />
      </Routes>
    );
  }

  return (
    <Layout>
      <Routes>
        <Route path="/flags" element={<FlagsPage />} />
        <Route path="/flags/:key" element={<FlagPage />} />
        <Route path="/keys" element={<KeysPage />} />
        <Route path="/audit" element={<AuditPage />} />
        <Route path="/members" element={<MembersPage />} />
        <Route path="*" element={<Navigate to="/flags" replace />} />
      </Routes>
    </Layout>
  );
}
