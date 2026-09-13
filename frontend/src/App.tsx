import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MerchantProvider } from './context/MerchantContext';
import { AppLayout } from './components/layout/AppLayout';
import { DashboardPage } from './pages/Dashboard/DashboardPage';
import { PaymentsListPage } from './pages/PaymentsList/PaymentsListPage';
import { PaymentDetailsPage } from './pages/PaymentDetails/PaymentDetailsPage';
import { PaymentSandboxPage } from './pages/PaymentSandbox/PaymentSandboxPage';
import { OutboxStreamPage } from './pages/OutboxStream/OutboxStreamPage';
import { DltExplorerPage } from './pages/DltExplorer/DltExplorerPage';
import { LedgerPage } from './pages/Ledger/LedgerPage';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
      staleTime: 30000, // 30 seconds
    },
  },
});

export const App: React.FC = () => {
  return (
    <QueryClientProvider client={queryClient}>
      <MerchantProvider>
        <BrowserRouter>
          <Routes>
            <Route element={<AppLayout />}>
              <Route path="/" element={<Navigate to="/dashboard" replace />} />
              <Route path="/dashboard" element={<DashboardPage />} />
              <Route path="/payments" element={<PaymentsListPage />} />
              <Route path="/payments/:id" element={<PaymentDetailsPage />} />
              <Route path="/sandbox" element={<PaymentSandboxPage />} />
              <Route path="/events/outbox" element={<OutboxStreamPage />} />
              <Route path="/events/dlt" element={<DltExplorerPage />} />
              <Route path="/ledger" element={<LedgerPage />} />
              <Route path="*" element={<Navigate to="/dashboard" replace />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </MerchantProvider>
    </QueryClientProvider>
  );
};
