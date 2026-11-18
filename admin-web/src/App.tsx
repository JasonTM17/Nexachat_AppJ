import { Navigate, Route, Routes } from 'react-router-dom';
import { useSession } from './auth';
import Layout from './components/Layout';
import LoginPage from './pages/LoginPage';
import OverviewPage from './pages/OverviewPage';
import UsersPage from './pages/UsersPage';
import AlertsPage from './pages/AlertsPage';
import FeedPage from './pages/FeedPage';
import RulesPage from './pages/RulesPage';
import AuditPage from './pages/AuditPage';
import SystemPage from './pages/SystemPage';
import { useI18n } from './i18n';

export default function App() {
  const { me, loading } = useSession();
  const { t } = useI18n();

  if (loading) {
    return <div className="loading" role="status">{t('common.loading')}</div>;
  }
  if (!me) {
    return (
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>
    );
  }
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Navigate to="/overview" replace />} />
        <Route path="/overview" element={<OverviewPage />} />
        <Route path="/users" element={<UsersPage />} />
        <Route path="/alerts" element={<AlertsPage />} />
        <Route path="/feed" element={<FeedPage />} />
        <Route path="/rules" element={<RulesPage />} />
        <Route path="/audit" element={<AuditPage />} />
        <Route path="/system" element={<SystemPage />} />
        <Route path="*" element={<Navigate to="/overview" replace />} />
      </Route>
    </Routes>
  );
}
