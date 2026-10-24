import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { paymentsApi } from '../../api/paymentsApi';
import { ledgerApi } from '../../api/ledgerApi';
import { useMerchant } from '../../context/MerchantContext';
import { Card } from '../../components/common/Card';
import { Button } from '../../components/common/Button';
import { PaymentStatusBadge } from '../../components/payment/PaymentStatusBadge';
import { Spinner } from '../../components/common/Spinner';
import { formatCentsToCurrency, formatDate, truncateId } from '../../utils/formatters';
import {
  Wallet,
  TrendingUp,
  Receipt,
  RotateCcw,
  PlusCircle,
  ArrowRight,
  ArrowUpRight,
  Activity,
  RefreshCw,
} from 'lucide-react';

export const DashboardPage: React.FC = () => {
  const { activeMerchant } = useMerchant();

  // 1. Fetch real-time merchant balance projection from Double-Entry Ledger Core
  const {
    data: balance,
    isLoading: isBalanceLoading,
    isError: isBalanceError,
    error: balanceError,
    refetch: refetchBalance,
    isFetching: isBalanceFetching,
  } = useQuery({
    queryKey: ['ledger-balance', activeMerchant.id],
    queryFn: () => ledgerApi.getMerchantBalance(activeMerchant.id),
  });

  // 2. Fetch recent payment transactions
  const {
    data: paymentsPage,
    isLoading: isPaymentsLoading,
    isError: isPaymentsError,
    error: paymentsError,
    refetch: refetchPayments,
    isFetching: isPaymentsFetching,
  } = useQuery({
    queryKey: ['payments', activeMerchant.id, 'recent'],
    queryFn: () => paymentsApi.listPayments({ page: 0, size: 5, sortBy: 'createdAt', direction: 'desc' }, activeMerchant.id),
  });

  const isRefreshing = isBalanceFetching || isPaymentsFetching;

  const handleRefreshAll = () => {
    refetchBalance();
    refetchPayments();
  };

  const recentPayments = paymentsPage?.content || [];

  // Compute status counts from recent transactions
  const statusCounts = recentPayments.reduce((acc, p) => {
    acc[p.status] = (acc[p.status] || 0) + 1;
    return acc;
  }, {} as Record<string, number>);

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div className="flex flex-col xl:flex-row xl:items-center justify-between gap-4">
        <div>
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-2xl font-bold text-slate-100 tracking-tight">Merchant Financial Dashboard</h1>
            <span className="text-xs font-mono uppercase bg-emerald-950/80 text-emerald-400 border border-emerald-700/60 px-2 py-0.5 rounded-full font-semibold">
              Merchant view
            </span>
          </div>
          <p className="text-sm text-slate-400 mt-1">
            Simulated MYR ledger projections and recent payment records for{' '}
            <span className="text-emerald-400 font-semibold">{activeMerchant.name}</span>
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-3 shrink-0">
          <Button
            variant="outline"
            size="sm"
            onClick={handleRefreshAll}
            disabled={isRefreshing}
            className="gap-1.5 whitespace-nowrap"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${isRefreshing ? 'animate-spin' : ''}`} />
            Refresh
          </Button>

          <Link to="/sandbox">
            <Button variant="primary" size="sm" className="gap-2">
              <PlusCircle className="w-4 h-4" />
              Testing Sandbox
            </Button>
          </Link>
        </div>
      </div>

      <details className="text-sm text-slate-400 border-b border-slate-800 pb-4">
        <summary className="text-slate-300">Simulation &amp; merchant context</summary>
        <div className="mt-3 space-y-2 max-w-prose">
          <p>Simulated payments only; no real money movement or payout facility.</p>
          <p>Authenticated merchant membership: <span className="text-slate-200">{activeMerchant.name}</span>.</p>
          <p>Tenant UUID: <code className="text-slate-300 select-all">{activeMerchant.id}</code></p>
          <p>Server-checked membership scopes <code className="text-emerald-300">X-Merchant-Id</code>.</p>
        </div>
      </details>

      {/* Top Financial Overview KPI Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-4">
        {/* 1. Available Payout Balance */}
        <Card className="relative overflow-hidden border-emerald-500/30 bg-gradient-to-b from-emerald-950/25 via-slate-800/90 to-slate-800/80">
          <div className="flex items-center justify-between text-slate-400 text-xs font-semibold uppercase tracking-wider">
            <span>Merchant Balance (MYR only)</span>
            <div className="p-2 rounded-lg bg-emerald-500/20 text-emerald-400 border border-emerald-500/30">
              <Wallet className="w-4 h-4" />
            </div>
          </div>
          {isBalanceLoading ? (
            <div className="py-4">
              <Spinner size="sm" />
            </div>
          ) : isBalanceError ? (
            <div className="text-xs text-rose-400 mt-2">
              Error: {(balanceError as Error)?.message || 'Failed to load'}
            </div>
          ) : (
            <div className="mt-2">
              <div className="text-3xl font-bold tabular-nums text-emerald-400 tracking-tight">
                {formatCentsToCurrency(balance?.availableBalanceCents ?? 0, balance?.currency || 'MYR')}
              </div>
              <p className="text-xs text-slate-400 mt-1">
                Simulated ledger balance; no payout facility
              </p>
            </div>
          )}
        </Card>

        {/* 2. Gross Volume */}
        <Card className="relative overflow-hidden border-slate-700/80 bg-gradient-to-b from-sky-950/20 via-slate-800/90 to-slate-800/80">
          <div className="flex items-center justify-between text-slate-400 text-xs font-semibold uppercase tracking-wider">
            <span>Gross Volume (MYR only)</span>
            <div className="p-2 rounded-lg bg-sky-500/20 text-sky-400 border border-sky-500/30">
              <TrendingUp className="w-4 h-4" />
            </div>
          </div>
          {isBalanceLoading ? (
            <div className="py-4">
              <Spinner size="sm" />
            </div>
          ) : isBalanceError ? (
            <div className="text-xs text-rose-400 mt-2">
              Error: {(balanceError as Error)?.message || 'Failed to load'}
            </div>
          ) : (
            <div className="mt-2">
              <div className="text-3xl font-bold tabular-nums text-slate-100 tracking-tight">
                {formatCentsToCurrency(balance?.totalVolumeCents ?? 0, balance?.currency || 'MYR')}
              </div>
              <p className="text-xs text-slate-400 mt-1">
                Total gross payment volume successfully captured
              </p>
            </div>
          )}
        </Card>

        {/* 3. Platform Fees */}
        <Card className="relative overflow-hidden border-slate-700/80 bg-gradient-to-b from-indigo-950/20 via-slate-800/90 to-slate-800/80">
          <div className="flex items-center justify-between text-slate-400 text-xs font-semibold uppercase tracking-wider">
            <span>Platform Fees (MYR only)</span>
            <div className="p-2 rounded-lg bg-indigo-500/20 text-indigo-400 border border-indigo-500/30">
              <Receipt className="w-4 h-4" />
            </div>
          </div>
          {isBalanceLoading ? (
            <div className="py-4">
              <Spinner size="sm" />
            </div>
          ) : isBalanceError ? (
            <div className="text-xs text-rose-400 mt-2">
              Error: {(balanceError as Error)?.message || 'Failed to load'}
            </div>
          ) : (
            <div className="mt-2">
              <div className="text-3xl font-bold tabular-nums text-slate-200 tracking-tight">
                {formatCentsToCurrency(balance?.totalFeesCents ?? 0, balance?.currency || 'MYR')}
              </div>
              <p className="text-xs text-slate-400 mt-1">
                Automated platform revenue cut (Account 4000)
              </p>
            </div>
          )}
        </Card>

        {/* 4. Total Refunds */}
        <Card className="relative overflow-hidden border-slate-700/80 bg-gradient-to-b from-purple-950/20 via-slate-800/90 to-slate-800/80">
          <div className="flex items-center justify-between text-slate-400 text-xs font-semibold uppercase tracking-wider">
            <span>Total Refunds (MYR only)</span>
            <div className="p-2 rounded-lg bg-purple-500/20 text-purple-400 border border-purple-500/30">
              <RotateCcw className="w-4 h-4" />
            </div>
          </div>
          {isBalanceLoading ? (
            <div className="py-4">
              <Spinner size="sm" />
            </div>
          ) : isBalanceError ? (
            <div className="text-xs text-rose-400 mt-2">
              Error: {(balanceError as Error)?.message || 'Failed to load'}
            </div>
          ) : (
            <div className="mt-2">
              <div className="text-3xl font-bold tabular-nums text-purple-300 tracking-tight">
                {formatCentsToCurrency(balance?.totalRefundsCents ?? 0, balance?.currency || 'MYR')}
              </div>
              <p className="text-xs text-slate-400 mt-1">
                Reversed customer volume debited from payable
              </p>
            </div>
          )}
        </Card>
      </div>

      {/* Activity Breakdown & Quick Links Bar */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <Card>
          <div className="text-xs font-semibold text-slate-400 uppercase tracking-wider">
            Recent Payments Recorded
          </div>
          <div className="text-2xl font-bold text-slate-100 mt-2 font-mono">{recentPayments.length}</div>
          <div className="text-xs text-slate-400 mt-1">
            Total in database: <span className="text-slate-300 font-semibold">{paymentsPage?.totalElements || 0}</span>
          </div>
        </Card>

        <Card>
          <div className="text-xs font-semibold text-slate-400 uppercase tracking-wider">
            Status Breakdown (Page 1)
          </div>
          <div className="flex items-center gap-2 mt-3 flex-wrap">
            {Object.entries(statusCounts).map(([status, count]) => (
              <div
                key={status}
                className="text-xs flex items-center gap-1.5 bg-slate-900/80 px-2 py-1 rounded border border-slate-700/60"
              >
                <span className="font-semibold text-slate-300">{status}:</span>
                <span className="text-emerald-400 font-bold font-mono">{count}</span>
              </div>
            ))}
            {recentPayments.length === 0 && <span className="text-xs text-slate-400">No recent records</span>}
          </div>
        </Card>

        <Card>
          <div className="text-xs font-semibold text-slate-400 uppercase tracking-wider">
            Quick Sandbox Scenarios
          </div>
          <div className="mt-2 space-y-1.5">
            <Link
              to="/sandbox"
              className="text-xs text-emerald-400 hover:text-emerald-300 flex items-center justify-between group"
            >
              <span>• Test Idempotency Key Replay</span>
              <ArrowUpRight className="w-3.5 h-3.5 group-hover:translate-x-0.5 transition-transform" />
            </Link>
            <Link
              to="/sandbox"
              className="text-xs text-amber-400 hover:text-amber-300 flex items-center justify-between group"
            >
              <span>• Test 409 Conflict Simulation</span>
              <ArrowUpRight className="w-3.5 h-3.5 group-hover:translate-x-0.5 transition-transform" />
            </Link>
          </div>
        </Card>
      </div>

      {/* Recent Payments Table */}
      <Card
        title="Recent Transactions Stream"
        subtitle="Live payment records fetched directly from PostgreSQL via Spring Boot REST API"
        action={
          <Link to="/payments">
            <Button variant="outline" size="sm" className="gap-1 text-xs">
              <span>View All</span>
              <ArrowRight className="w-3.5 h-3.5" />
            </Button>
          </Link>
        }
      >
        {isPaymentsLoading && (
          <div className="py-12 flex justify-center">
            <Spinner />
          </div>
        )}

        {isPaymentsError && (
          <div className="py-8 text-center text-xs text-rose-400">
            Failed to load payments: {(paymentsError as Error)?.message || 'Network error'}
          </div>
        )}

        {!isPaymentsLoading && !isPaymentsError && recentPayments.length === 0 && (
          <div className="py-12 text-center">
            <Activity className="w-8 h-8 text-slate-600 mx-auto mb-2" />
            <p className="text-sm text-slate-400 font-medium">No payments created yet for this merchant</p>
            <p className="text-xs text-slate-400 mt-1">Use the Testing Sandbox to generate test transactions</p>
            <div className="mt-4">
              <Link to="/sandbox">
                <Button size="sm" variant="primary">
                  Go to Sandbox
                </Button>
              </Link>
            </div>
          </div>
        )}

        {!isPaymentsLoading && !isPaymentsError && recentPayments.length > 0 && (
          <div className="data-scroll" tabIndex={0} role="region" aria-label="Recent payments table">
            <table className="w-full text-left text-sm">
              <thead className="text-xs text-slate-400 uppercase bg-slate-900/60 border-b border-slate-700/60">
                <tr>
                  <th className="px-4 py-3 font-semibold">Payment ID</th>
                  <th className="px-4 py-3 font-semibold">Amount</th>
                  <th className="px-4 py-3 font-semibold">Status</th>
                  <th className="px-4 py-3 font-semibold">Idempotency Key</th>
                  <th className="px-4 py-3 font-semibold">Created At</th>
                  <th className="px-4 py-3 font-semibold text-right">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-700/40 font-mono">
                {recentPayments.map((payment) => (
                  <tr key={payment.id} className="hover:bg-slate-800/40 transition-colors">
                    <td className="px-4 py-3 text-slate-200">
                      <Link
                        to={`/payments/${payment.id}`}
                        className="hover:text-emerald-400 transition-colors font-semibold"
                      >
                        {truncateId(payment.id, 8)}
                      </Link>
                    </td>
                    <td className="px-4 py-3 text-slate-100 font-semibold font-sans whitespace-nowrap">
                      {formatCentsToCurrency(payment.amountCents, payment.currency)}
                    </td>
                    <td className="px-4 py-3">
                      <PaymentStatusBadge status={payment.status} />
                    </td>
                    <td className="px-4 py-3 text-slate-400">
                      {truncateId(payment.idempotencyKey, 14)}
                    </td>
                    <td className="px-4 py-3 text-slate-400 font-sans">
                      {formatDate(payment.createdAt)}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <Link to={`/payments/${payment.id}`}>
                        <Button variant="ghost" size="sm" className="text-xs h-7 px-2 font-sans whitespace-nowrap">
                          Details
                        </Button>
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
};
