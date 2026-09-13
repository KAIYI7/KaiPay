import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { paymentsApi } from '../../api/paymentsApi';
import { useMerchant } from '../../context/MerchantContext';
import { PaymentStatus } from '../../models/payment';
import { Card } from '../../components/common/Card';
import { Button } from '../../components/common/Button';
import { Select } from '../../components/common/Select';
import { PaymentStatusBadge } from '../../components/payment/PaymentStatusBadge';
import { CopyButton } from '../../components/common/CopyButton';
import { Spinner } from '../../components/common/Spinner';
import { formatCentsToCurrency, formatDate, truncateId } from '../../utils/formatters';
import { ChevronLeft, ChevronRight, PlusCircle, RefreshCw } from 'lucide-react';

export const PaymentsListPage: React.FC = () => {
  const { activeMerchant } = useMerchant();
  const [statusFilter, setStatusFilter] = useState<PaymentStatus | ''>('');
  const [page, setPage] = useState<number>(0);
  const size = 10;

  const {
    data: paymentsPage,
    isLoading,
    isError,
    error,
    refetch,
    isFetching,
  } = useQuery({
    queryKey: ['payments', activeMerchant.id, page, size, statusFilter],
    queryFn: () =>
      paymentsApi.listPayments({
        page,
        size,
        status: statusFilter || undefined,
        sortBy: 'createdAt',
        direction: 'desc',
      }),
  });

  const payments = paymentsPage?.content || [];
  const totalPages = paymentsPage?.totalPages || 0;
  const totalElements = paymentsPage?.totalElements || 0;

  const statusOptions = [
    { value: '', label: 'All Statuses' },
    { value: 'CREATED', label: 'CREATED' },
    { value: 'PROCESSING', label: 'PROCESSING' },
    { value: 'AUTHORIZED', label: 'AUTHORIZED' },
    { value: 'CAPTURED', label: 'CAPTURED' },
    { value: 'FAILED', label: 'FAILED' },
    { value: 'DECLINED', label: 'DECLINED' },
    { value: 'PARTIALLY_REFUNDED', label: 'PARTIALLY_REFUNDED' },
    { value: 'REFUNDED', label: 'REFUNDED' },
  ];

  const handleStatusChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    setStatusFilter(e.target.value as PaymentStatus | '');
    setPage(0); // Reset to first page
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-100 tracking-tight">Payments Explorer</h1>
          <p className="text-sm text-slate-400 mt-1">
            Browse and filter paginated payments for{' '}
            <span className="text-emerald-400 font-semibold">{activeMerchant.name}</span>
          </p>
        </div>

        <div className="flex items-center gap-3">
          <Button
            variant="outline"
            size="sm"
            onClick={() => refetch()}
            disabled={isFetching}
            className="gap-1.5 text-xs"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${isFetching ? 'animate-spin' : ''}`} />
            Refresh
          </Button>
          <Link to="/sandbox">
            <Button variant="primary" size="sm" className="gap-1.5 text-xs">
              <PlusCircle className="w-4 h-4" />
              New Payment
            </Button>
          </Link>
        </div>
      </div>

      {/* Filter Bar */}
      <Card className="p-4">
        <div className="flex flex-col sm:flex-row items-center justify-between gap-4">
          <div className="w-full sm:w-64">
            <Select
              label="Filter by Status"
              options={statusOptions}
              value={statusFilter}
              onChange={handleStatusChange}
            />
          </div>

          <div className="text-xs text-slate-400 self-end sm:self-center">
            Found <span className="font-semibold text-slate-200">{totalElements}</span> payments
          </div>
        </div>
      </Card>

      {/* Main Payments Table */}
      <Card className="p-0 overflow-hidden">
        {isLoading && (
          <div className="py-20 flex justify-center">
            <Spinner />
          </div>
        )}

        {isError && (
          <div className="py-12 text-center text-xs text-rose-400">
            Failed to load payments: {(error as Error).message}
          </div>
        )}

        {!isLoading && !isError && payments.length === 0 && (
          <div className="py-16 text-center">
            <p className="text-sm font-medium text-slate-300">No matching payments found</p>
            <p className="text-xs text-slate-500 mt-1">
              {statusFilter ? `No transactions with status ${statusFilter}` : 'Create a transaction in the testing sandbox'}
            </p>
          </div>
        )}

        {!isLoading && !isError && payments.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead className="text-[11px] text-slate-400 uppercase bg-slate-900/80 border-b border-slate-700/60">
                <tr>
                  <th className="px-4 py-3.5 font-semibold">Payment ID</th>
                  <th className="px-4 py-3.5 font-semibold">Amount</th>
                  <th className="px-4 py-3.5 font-semibold">Status</th>
                  <th className="px-4 py-3.5 font-semibold">Customer ID</th>
                  <th className="px-4 py-3.5 font-semibold">Idempotency Key</th>
                  <th className="px-4 py-3.5 font-semibold">Version</th>
                  <th className="px-4 py-3.5 font-semibold">Date</th>
                  <th className="px-4 py-3.5 font-semibold text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-700/40 font-mono">
                {payments.map((payment) => (
                  <tr key={payment.id} className="hover:bg-slate-800/40 transition-colors">
                    <td className="px-4 py-3 text-slate-200">
                      <div className="flex items-center gap-1">
                        <Link
                          to={`/payments/${payment.id}`}
                          className="hover:text-emerald-400 transition-colors font-semibold"
                        >
                          {truncateId(payment.id, 8)}
                        </Link>
                        <CopyButton text={payment.id} />
                      </div>
                    </td>
                    <td className="px-4 py-3 text-slate-100 font-semibold font-sans">
                      {formatCentsToCurrency(payment.amountCents, payment.currency)}
                    </td>
                    <td className="px-4 py-3">
                      <PaymentStatusBadge status={payment.status} />
                    </td>
                    <td className="px-4 py-3 text-slate-400">
                      <div className="flex items-center gap-1">
                        <span>{truncateId(payment.customerId, 8)}</span>
                        <CopyButton text={payment.customerId} />
                      </div>
                    </td>
                    <td className="px-4 py-3 text-slate-400">
                      <div className="flex items-center gap-1">
                        <span>{truncateId(payment.idempotencyKey, 10)}</span>
                        <CopyButton text={payment.idempotencyKey} />
                      </div>
                    </td>
                    <td className="px-4 py-3 text-slate-500 font-sans">
                      v{payment.version}
                    </td>
                    <td className="px-4 py-3 text-slate-400 font-sans">
                      {formatDate(payment.createdAt)}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <Link to={`/payments/${payment.id}`}>
                        <Button variant="ghost" size="sm" className="text-xs h-7 px-2 font-sans">
                          Inspect
                        </Button>
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Pagination Footer */}
        {!isLoading && !isError && totalPages > 1 && (
          <div className="px-4 py-3 bg-slate-900/60 border-t border-slate-700/60 flex items-center justify-between text-xs text-slate-400">
            <div>
              Page <span className="font-semibold text-slate-200">{page + 1}</span> of{' '}
              <span className="font-semibold text-slate-200">{totalPages}</span>
            </div>

            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={page === 0}
                className="p-1.5 h-8 w-8"
              >
                <ChevronLeft className="w-4 h-4" />
              </Button>

              <Button
                variant="outline"
                size="sm"
                onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                disabled={page >= totalPages - 1}
                className="p-1.5 h-8 w-8"
              >
                <ChevronRight className="w-4 h-4" />
              </Button>
            </div>
          </div>
        )}
      </Card>
    </div>
  );
};
