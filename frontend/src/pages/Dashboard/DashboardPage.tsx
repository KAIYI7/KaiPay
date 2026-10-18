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
  Building,
  ShieldCheck,
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
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-2xl font-bold text-slate-100 tracking-tight">Merchant Financial Dashboard</h1>
            <span className="text-[10px] font-mono uppercase bg-emerald-950/80 text-emerald-400 border border-emerald-700/60 px-2 py-0.5 rounded-full font-semibold">
              Live Hub
            </span>
          </div>
          <p className="text-sm text-slate-400 mt-1">
            Real-time double-entry ledger projections, transaction stream, and event-driven architecture telemetry for{' '}
            <span className="text-emerald-400 font-semibold">{activeMerchant.name}</span>
          </p>
        </div>

        <div className="flex items-center gap-3">
          <Button
            variant="outline"
            size="sm"
            onClick={handleRefreshAll}
            disabled={isRefreshing}
            className="gap-1.5 text-xs"
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

      {/* Active Merchant Context & Simulation Mode Banner */}
      <div className="bg-gradient-to-r from-slate-900 via-slate-800 to-slate-900 border border-slate-700/80 rounded-xl p-5 shadow-sm">
        <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4">
          <div className="flex items-start gap-3.5">
            <div className="p-2.5 rounded-lg bg-emerald-500/10 border border-emerald-500/20 text-emerald-400 mt-0.5 shrink-0">
              <Building className="w-6 h-6" />
            </div>
            <div>
              <div className="flex items-center gap-2 flex-wrap">
                <h3 className="text-base font-semibold text-slate-100">{activeMerchant.name}</h3>
                <span className="text-xs px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-400 border border-emerald-500/30 font-medium">
                  {'Assigned'}
                </span>
                <span className="text-[11px] px-2.5 py-0.5 rounded-full bg-amber-500/15 text-amber-300 border border-amber-500/30 font-medium flex items-center gap-1.5">
                  <ShieldCheck className="w-3.5 h-3.5 text-amber-400" />
                  Simulation Mode: Multi-Tenant Test Harness
                </span>
              </div>
              <p className="text-xs text-slate-400 mt-1">{'Authenticated merchant membership'}</p>
              <div className="text-xs font-mono text-slate-400 mt-1.5 flex items-center gap-2">
                <span>Tenant UUID:</span>
                <span className="bg-slate-950 px-2 py-0.5 rounded border border-slate-800 text-slate-300 select-all">
                  {activeMerchant.id}
                </span>
              </div>
            </div>
          </div>

          <div className="flex items-center gap-2 text-xs text-slate-400 bg-slate-950/80 border border-slate-800 p-3 rounded-lg self-start lg:self-auto">
            <ShieldCheck className="w-4 h-4 text-emerald-400 shrink-0" />
            <span>
              All transactions & balances strictly isolated via <code className="text-emerald-300 font-mono">X-Merchant-Id</code>.
            </span>
          </div>
        </div>
      </div>

      {/* Top Financial Overview KPI Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {/* 1. Available Payout Balance */}
        <Card className="relative overflow-hidden border-emerald-500/30 bg-gradient-to-b from-emerald-950/25 via-slate-800/90 to-slate-800/80">
          <div className="flex items-center justify-between text-slate-400 text-xs font-semibold uppercase tracking-wider">
            <span>Available Payout Balance (MYR only)</span>
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
              <div className="text-2xl font-bold font-mono text-emerald-400 tracking-tight">
                {formatCentsToCurrency(balance?.availableBalanceCents ?? 0, balance?.currency || 'MYR')}
              </div>
              <p className="text-[11px] text-slate-400 mt-1">
                Net merchant settlement funds available for payout
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
              <div className="text-2xl font-bold font-mono text-slate-100 tracking-tight">
                {formatCentsToCurrency(balance?.totalVolumeCents ?? 0, balance?.currency || 'MYR')}
              </div>
              <p className="text-[11px] text-slate-400 mt-1">
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
              <div className="text-2xl font-bold font-mono text-slate-200 tracking-tight">
                {formatCentsToCurrency(balance?.totalFeesCents ?? 0, balance?.currency || 'MYR')}
              </div>
              <p className="text-[11px] text-slate-400 mt-1">
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
              <div className="text-2xl font-bold font-mono text-purple-300 tracking-tight">
                {formatCentsToCurrency(balance?.totalRefundsCents ?? 0, balance?.currency || 'MYR')}
              </div>
              <p className="text-[11px] text-slate-400 mt-1">
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
          <div className="text-xs text-slate-500 mt-1">
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
            {recentPayments.length === 0 && <span className="text-xs text-slate-500">No recent records</span>}
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
            <p className="text-xs text-slate-500 mt-1">Use the Testing Sandbox to generate test transactions</p>
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
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead className="text-[11px] text-slate-400 uppercase bg-slate-900/60 border-b border-slate-700/60">
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
                    <td className="px-4 py-3 text-slate-100 font-semibold font-sans">
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
                        <Button variant="ghost" size="sm" className="text-xs h-7 px-2 font-sans">
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
