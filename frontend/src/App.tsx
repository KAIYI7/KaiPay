import React, { useEffect, useState } from 'react';
import { BrowserRouter, Routes, Route, Navigate, Outlet } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider, useAuth } from './context/AuthContext';
import { MerchantProvider, useMerchant } from './context/MerchantContext';
import { ApiError } from './models/api';
import { StaleSessionError } from './api/fetchClient';
import { AppLayout } from './components/layout/AppLayout';
import { DashboardPage } from './pages/Dashboard/DashboardPage';
import { PaymentsListPage } from './pages/PaymentsList/PaymentsListPage';
import { PaymentDetailsPage } from './pages/PaymentDetails/PaymentDetailsPage';
import { PaymentSandboxPage } from './pages/PaymentSandbox/PaymentSandboxPage';
import { OutboxStreamPage } from './pages/OutboxStream/OutboxStreamPage';
import { DltExplorerPage } from './pages/DltExplorer/DltExplorerPage';
import { LedgerPage } from './pages/Ledger/LedgerPage';
import { LoginPage } from './pages/Login/LoginPage';
import { WebhooksPage } from './pages/Webhooks/WebhooksPage';

// A new QueryClient is allocated for every authenticated lifetime, even when
// another account shares the same merchant. Unmounted mutation callbacks cannot
// reach the new principal's cache.
export function PrincipalQueries({ children }: { children: React.ReactNode }) {
  const [client] = useState(() => new QueryClient({ defaultOptions: {
    queries: { retry: (count, error) => count < 1 && !(error instanceof StaleSessionError) &&
      !(error instanceof ApiError && [401, 403].includes(error.status)), refetchOnWindowFocus: false, staleTime: 30000 },
    mutations: { retry: false },
  } }));
  useEffect(() => () => { void client.cancelQueries(); client.clear(); }, [client]);
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
function RoleRoutes({ role }: { role: string }) {
  const { user } = useAuth();
  return user?.roles.includes(role) ? <Outlet /> : <p role="alert">You do not have permission to view this page.</p>;
}
function MerchantView({ children }: { children: React.ReactNode }) {
  const { activeMerchant } = useMerchant();
  if (!activeMerchant.id) return <p role="alert">No merchant membership is assigned. Contact the demo operator.</p>;
  return <React.Fragment key={activeMerchant.id}>{children}</React.Fragment>;
}
function SessionApp() {
  const { user, initializing, revision } = useAuth();
  if (initializing) return <p className="p-8 text-slate-300">Initializing session...</p>;
  if (!user) return <LoginPage />;
  const home = user.roles.includes('MERCHANT') ? '/dashboard' : '/events/outbox';
  return <PrincipalQueries key={revision}>
    <MerchantProvider memberships={user.merchants}>
      <Routes>
        <Route element={<AppLayout />}>
          <Route path="/" element={<Navigate to={home} replace />} />
          <Route element={<RoleRoutes role="MERCHANT" />}>
            <Route path="/dashboard" element={<MerchantView><DashboardPage /></MerchantView>} />
            <Route path="/payments" element={<MerchantView><PaymentsListPage /></MerchantView>} />
            <Route path="/payments/:id" element={<MerchantView><PaymentDetailsPage /></MerchantView>} />
            <Route path="/sandbox" element={<MerchantView><PaymentSandboxPage /></MerchantView>} />
            <Route path="/webhooks" element={<MerchantView><WebhooksPage /></MerchantView>} />
          </Route>
          <Route element={<RoleRoutes role="ADMIN" />}>
            <Route path="/events/outbox" element={<OutboxStreamPage />} />
            <Route path="/events/dlt" element={<DltExplorerPage />} />
          </Route>
          <Route path="/ledger" element={<LedgerPage />} />
          <Route path="*" element={<Navigate to={home} replace />} />
        </Route>
      </Routes>
    </MerchantProvider>
  </PrincipalQueries>;
}
export const App: React.FC = () => <AuthProvider><BrowserRouter><SessionApp /></BrowserRouter></AuthProvider>;
