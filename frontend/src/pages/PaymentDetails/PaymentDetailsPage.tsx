import React, { useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { paymentsApi } from '../../api/paymentsApi';
import { refundApi } from '../../api/refundApi';
import { dltApi } from '../../api/dltApi';
import { RefundStatus } from '../../models/refund';
import { Card } from '../../components/common/Card';
import { Button } from '../../components/common/Button';
import { Input } from '../../components/common/Input';
import { Badge } from '../../components/common/Badge';
import { PaymentStatusBadge } from '../../components/payment/PaymentStatusBadge';
import { StateMachineStepper } from '../../components/payment/StateMachineStepper';
import { CopyButton } from '../../components/common/CopyButton';
import { Spinner } from '../../components/common/Spinner';
import { formatCentsToCurrency, formatDate, generateIdempotencyKey, truncateId } from '../../utils/formatters';
import {
  ArrowLeft,
  CreditCard,
  Key,
  Shield,
  FileJson,
  Radio,
  AlertOctagon,
  RotateCw,
  ExternalLink,
  CheckCircle2,
  RotateCcw,
  DollarSign,
  X,
  Scale,
  AlertTriangle,
} from 'lucide-react';

export const PaymentDetailsPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const queryClient = useQueryClient();

  // Modals & Action States
  const [isCaptureModalOpen, setIsCaptureModalOpen] = useState(false);
  const [isRefundModalOpen, setIsRefundModalOpen] = useState(false);
  const [captureIdempotencyKey, setCaptureIdempotencyKey] = useState('');
  const [refundIdempotencyKey, setRefundIdempotencyKey] = useState('');
  const [refundAmountDollars, setRefundAmountDollars] = useState('');
  const [refundReason, setRefundReason] = useState('');
  const [actionSuccessMessage, setActionSuccessMessage] = useState<string | null>(null);
  const [actionErrorMessage, setActionErrorMessage] = useState<string | null>(null);

  // 1. Fetch Payment
  const {
    data: payment,
    isLoading: isPaymentLoading,
    isError: isPaymentError,
    error: paymentError,
  } = useQuery({
    queryKey: ['payment', id],
    queryFn: () => paymentsApi.getPayment(id!),
    enabled: !!id,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status === 'CREATED' || status === 'PROCESSING' ? 1000 : false;
    },
  });

  const isInFlight = payment?.status === 'CREATED' || payment?.status === 'PROCESSING';

  // 2. Fetch DLT Events (if any)
  const { data: dltEventsPage } = useQuery({
    queryKey: ['dlt-events-for-payment', id],
    queryFn: () => dltApi.listDeadLetterEvents({ paymentId: id }),
    enabled: !!id,
    refetchInterval: () => (isInFlight ? 1000 : false),
  });

  const dltEvent = dltEventsPage?.content?.[0];

  // 3. Fetch Refunds History
  const {
    data: refunds = [],
    isLoading: isRefundsLoading,
    refetch: refetchRefunds,
  } = useQuery({
    queryKey: ['payment-refunds', id],
    queryFn: () => refundApi.listPaymentRefunds(id!),
    enabled: !!id,
  });

  // Calculate refund limits
  const totalRefundedCents = refunds
    .filter((r) => r.status === 'COMPLETED')
    .reduce((sum, r) => sum + r.amountCents, 0);

  const remainingRefundableCents = payment
    ? Math.max(0, payment.amountCents - totalRefundedCents)
    : 0;

  // 4. Capture Mutation
  const captureMutation = useMutation({
    mutationFn: async (key: string) => {
      return paymentsApi.capturePayment(id!, key);
    },
    onSuccess: (updatedPayment) => {
      setIsCaptureModalOpen(false);
      setActionErrorMessage(null);
      setActionSuccessMessage(
        `Payment ${updatedPayment.id} successfully captured! Settled ${formatCentsToCurrency(
          updatedPayment.amountCents,
          updatedPayment.currency
        )} to double-entry ledger.`
      );
      queryClient.invalidateQueries({ queryKey: ['payment', id] });
      queryClient.invalidateQueries({ queryKey: ['payments'] });
      queryClient.invalidateQueries({ queryKey: ['ledger-balance'] });
      queryClient.invalidateQueries({ queryKey: ['ledger-journals'] });
    },
    onError: (err: Error) => {
      setActionErrorMessage(err.message || 'Failed to capture payment');
    },
  });

  // 5. Refund Mutation
  const refundMutation = useMutation({
    mutationFn: async ({
      amountCents,
      reason,
      key,
    }: {
      amountCents: number;
      reason?: string;
      key: string;
    }) => {
      return refundApi.createRefund(id!, { amountCents, reason }, key);
    },
    onSuccess: (refund) => {
      setIsRefundModalOpen(false);
      setActionErrorMessage(null);
      setActionSuccessMessage(
        `Refund ${refund.id} of ${formatCentsToCurrency(
          refund.amountCents,
          refund.currency
        )} successfully processed with status ${refund.status}!`
      );
      setRefundAmountDollars('');
      setRefundReason('');
      queryClient.invalidateQueries({ queryKey: ['payment', id] });
      queryClient.invalidateQueries({ queryKey: ['payment-refunds', id] });
      queryClient.invalidateQueries({ queryKey: ['payments'] });
      queryClient.invalidateQueries({ queryKey: ['ledger-balance'] });
      queryClient.invalidateQueries({ queryKey: ['ledger-journals'] });
    },
    onError: (err: Error) => {
      setActionErrorMessage(err.message || 'Failed to create refund');
    },
  });

  // Open Capture Modal Handler
  const handleOpenCaptureModal = () => {
    setCaptureIdempotencyKey(generateIdempotencyKey());
    setActionErrorMessage(null);
    setIsCaptureModalOpen(true);
  };

  // Open Refund Modal Handler
  const handleOpenRefundModal = () => {
    setRefundIdempotencyKey(generateIdempotencyKey());
    setRefundAmountDollars((remainingRefundableCents / 100).toFixed(2));
    setRefundReason('');
    setActionErrorMessage(null);
    setIsRefundModalOpen(true);
  };

  // Submit Capture
  const handleConfirmCapture = () => {
    captureMutation.mutate(captureIdempotencyKey);
  };

  // Submit Refund
  const handleConfirmRefund = (e: React.FormEvent) => {
    e.preventDefault();
    const amountDollars = parseFloat(refundAmountDollars);
    if (isNaN(amountDollars) || amountDollars <= 0) {
      setActionErrorMessage('Please enter a valid positive refund amount.');
      return;
    }
    const amountCents = Math.round(amountDollars * 100);
    if (amountCents > remainingRefundableCents) {
      setActionErrorMessage(
        `Refund amount exceeds remaining refundable amount (${formatCentsToCurrency(
          remainingRefundableCents,
          payment?.currency
        )}).`
      );
      return;
    }

    refundMutation.mutate({
      amountCents,
      reason: refundReason.trim() || undefined,
      key: refundIdempotencyKey,
    });
  };

  const getRefundStatusBadge = (status: RefundStatus) => {
    switch (status) {
      case 'COMPLETED':
        return (
          <Badge variant="success" className="font-mono text-[11px]">
            <CheckCircle2 className="w-3 h-3" />
            COMPLETED
          </Badge>
        );
      case 'PENDING':
        return (
          <Badge variant="warning" className="font-mono text-[11px]">
            <RotateCw className="w-3 h-3 animate-spin" />
            PENDING
          </Badge>
        );
      case 'FAILED':
        return (
          <Badge variant="danger" className="font-mono text-[11px]">
            <AlertOctagon className="w-3 h-3" />
            FAILED
          </Badge>
        );
      default:
        return <Badge className="font-mono text-[11px]">{status}</Badge>;
    }
  };

  if (isPaymentLoading) {
    return (
      <div className="py-24 flex justify-center">
        <Spinner size="lg" />
      </div>
    );
  }

  if (isPaymentError || !payment) {
    return (
      <div className="space-y-4">
        <Link to="/payments">
          <Button variant="outline" size="sm" className="gap-1.5 text-xs">
            <ArrowLeft className="w-4 h-4" />
            Back to Payments
          </Button>
        </Link>
        <Card className="p-8 text-center">
          <h3 className="text-base font-semibold text-rose-400">Payment Not Found</h3>
          <p className="text-xs text-slate-400 mt-1">
            {(paymentError as Error)?.message || 'The payment ID does not exist or belongs to another merchant.'}
          </p>
        </Card>
      </div>
    );
  }

  const canCapture = payment.status === 'AUTHORIZED';
  const canRefund =
    (payment.status === 'CAPTURED' || payment.status === 'PARTIALLY_REFUNDED') &&
    remainingRefundableCents > 0;

  return (
    <div className="space-y-6">
      {/* Navigation & Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div className="space-y-1">
          <Link to="/payments">
            <Button variant="ghost" size="sm" className="gap-1.5 text-xs -ml-2 mb-1 text-slate-400">
              <ArrowLeft className="w-3.5 h-3.5" />
              Payments
            </Button>
          </Link>
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold font-mono text-slate-100">{payment.id}</h1>
            <CopyButton text={payment.id} />
          </div>
          <p className="text-xs text-slate-400">
            Created on {formatDate(payment.createdAt)} • Version {payment.version}
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <PaymentStatusBadge status={payment.status} className="text-sm px-3 py-1" />

          {/* Action: Capture Funds */}
          {canCapture && (
            <Button
              variant="primary"
              size="sm"
              onClick={handleOpenCaptureModal}
              className="gap-2 text-xs font-semibold shadow-lg shadow-emerald-900/30"
            >
              <DollarSign className="w-4 h-4" />
              Capture Funds
            </Button>
          )}

          {/* Action: Issue Refund */}
          {canRefund && (
            <Button
              variant="outline"
              size="sm"
              onClick={handleOpenRefundModal}
              className="gap-2 text-xs font-semibold bg-purple-950/40 border-purple-500/50 hover:bg-purple-900/50 text-purple-200"
            >
              <RotateCcw className="w-4 h-4 text-purple-400" />
              Issue Refund
            </Button>
          )}

          {payment.status === 'PROCESSING' && (
            <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-semibold bg-amber-500/20 text-amber-300 border border-amber-500/40">
              <RotateCw className="w-3.5 h-3.5 animate-spin text-amber-400" />
              Attempting Acquirer Authorization (Non-Blocking Retries)
            </span>
          )}
        </div>
      </div>

      {/* Success Notification Alert */}
      {actionSuccessMessage && (
        <div className="p-4 rounded-xl bg-emerald-950/40 border border-emerald-500/50 flex items-start justify-between gap-3 text-xs text-emerald-300 shadow-lg animate-in fade-in duration-200">
          <div className="flex items-start gap-2.5">
            <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0 mt-0.5" />
            <div>
              <span className="font-bold">Action Completed: </span>
              {actionSuccessMessage}
            </div>
          </div>
          <button
            type="button"
            onClick={() => setActionSuccessMessage(null)}
            className="text-emerald-400 hover:text-emerald-200"
          >
            <X className="w-4 h-4" />
          </button>
        </div>
      )}

      {/* DLT Failure Warning Card for FAILED Status */}
      {payment.status === 'FAILED' && (
        <div className="bg-rose-950/30 border-2 border-rose-500/50 rounded-xl p-5 shadow-lg relative overflow-hidden">
          <div className="absolute top-0 right-0 w-80 h-80 bg-rose-500/10 rounded-full blur-3xl pointer-events-none -mr-16 -mt-16"></div>
          <div className="flex flex-col md:flex-row items-start md:items-center justify-between gap-4 relative z-10">
            <div className="flex items-start gap-3.5">
              <div className="p-3 rounded-xl bg-rose-500/20 border border-rose-500/40 text-rose-400 shrink-0">
                <AlertOctagon className="w-6 h-6" />
              </div>
              <div className="space-y-2">
                <div className="flex flex-wrap items-center gap-2">
                  <h3 className="text-base font-bold text-rose-300">
                    Non-Blocking Retries Exhausted • Dead Letter Topic (DLT) Routed
                  </h3>
                  <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-[11px] font-mono font-semibold bg-rose-900/60 text-rose-300 border border-rose-700/60">
                    Retries: {dltEvent ? dltEvent.retryCount : 'Exhausted (3)'}
                  </span>
                </div>

                <div className="space-y-1 text-xs">
                  <div>
                    <span className="text-slate-400 font-medium">Exception Class: </span>
                    <code className="font-mono text-rose-300 bg-rose-950/80 px-2 py-0.5 rounded border border-rose-800/60">
                      {dltEvent?.exceptionClass || payment.failureCode || 'AcquirerTransientFailureException'}
                    </code>
                  </div>

                  <div>
                    <span className="text-slate-400 font-medium">Failure Message: </span>
                    <span className="text-rose-200 font-sans">
                      {dltEvent?.failureMessage ||
                        payment.failureMessage ||
                        'Exhausted non-blocking retries after maximum attempts.'}
                    </span>
                  </div>

                  {dltEvent && (
                    <div className="text-[11px] text-slate-400 font-mono">
                      Topic: <span className="text-rose-300">{dltEvent.originalTopic}</span> (Partition{' '}
                      {dltEvent.originalPartition}, Offset {dltEvent.originalOffset}) • Intercepted at{' '}
                      {formatDate(dltEvent.createdAt)}
                    </div>
                  )}
                </div>
              </div>
            </div>

            <div className="shrink-0 self-end md:self-center">
              <Link to={`/events/dlt?paymentId=${payment.id}`}>
                <Button
                  variant="outline"
                  size="sm"
                  className="gap-2 text-xs font-semibold bg-rose-900/40 border-rose-500/50 hover:bg-rose-900/70 text-rose-200"
                >
                  <AlertOctagon className="w-4 h-4 text-rose-400" />
                  <span>Inspect in DLT Explorer</span>
                  <ExternalLink className="w-3.5 h-3.5 text-rose-400" />
                </Button>
              </Link>
            </div>
          </div>
        </div>
      )}

      {/* In-Flight Active Processing Banner */}
      {isInFlight && (
        <div className="bg-gradient-to-r from-amber-500/15 via-sky-500/10 to-emerald-500/15 border border-amber-500/30 rounded-xl p-4 shadow-sm">
          <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
            <div className="flex items-start md:items-center gap-3.5">
              <div className="relative flex items-center justify-center w-9 h-9 rounded-lg bg-amber-500/20 text-amber-400 shrink-0">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-lg bg-amber-400 opacity-30"></span>
                <Radio className="w-5 h-5 text-amber-400 relative z-10 animate-pulse" />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <h3 className="text-sm font-bold text-amber-300">Transaction In-Flight: Asynchronous Processing</h3>
                  <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-[10px] font-semibold bg-amber-400/20 text-amber-300 border border-amber-400/40">
                    <span className="w-1.5 h-1.5 rounded-full bg-amber-400 animate-ping"></span>
                    Live Polling Active (1s)
                  </span>
                </div>
                <p className="text-xs text-slate-300 mt-1">
                  The Kafka consumer worker and mock bank acquirer are asynchronously processing this transaction. UI
                  updates automatically.
                </p>
              </div>
            </div>

            <div className="flex items-center gap-2 text-xs font-mono bg-slate-900/90 px-3 py-1.5 rounded-lg border border-slate-700/70 shrink-0 self-start md:self-auto">
              <span className="text-slate-400 font-semibold">Event Pipeline:</span>
              <span className="text-emerald-400">PostgreSQL Outbox</span>
              <span className="text-slate-500">➔</span>
              <span className="text-sky-400">Kafka Topic</span>
              <span className="text-slate-500">➔</span>
              <span className="text-amber-400">Bank Acquirer</span>
            </div>
          </div>
        </div>
      )}

      {/* State Machine Stepper */}
      <StateMachineStepper
        currentStatus={payment.status}
        failureCode={payment.failureCode}
        failureMessage={payment.failureMessage}
      />

      {/* Financial & Technical Details Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Left Column: Financial & Customer Information */}
        <Card
          title="Financial & Customer Information"
          subtitle="Core transaction attributes stored in PostgreSQL"
        >
          <div className="space-y-4 text-xs font-sans divide-y divide-slate-700/50">
            <div className="flex justify-between py-2 items-center">
              <span className="text-slate-400 flex items-center gap-1.5">
                <CreditCard className="w-3.5 h-3.5 text-slate-500" />
                Original Authorized Amount
              </span>
              <span className="text-base font-bold text-slate-100">
                {formatCentsToCurrency(payment.amountCents, payment.currency)}
              </span>
            </div>

            <div className="flex justify-between py-2 items-center">
              <span className="text-slate-400">Currency</span>
              <span className="font-mono font-semibold text-slate-200">{payment.currency}</span>
            </div>

            <div className="flex justify-between py-2 items-center">
              <span className="text-slate-400">Customer ID</span>
              <div className="flex items-center gap-1 font-mono text-slate-300">
                <span>{payment.customerId}</span>
                <CopyButton text={payment.customerId} />
              </div>
            </div>

            {payment.paymentMethodId && (
              <div className="flex justify-between py-2 items-center">
                <span className="text-slate-400">Payment Method ID</span>
                <div className="flex items-center gap-1 font-mono text-slate-300">
                  <span>{payment.paymentMethodId}</span>
                  <CopyButton text={payment.paymentMethodId} />
                </div>
              </div>
            )}

            <div className="flex justify-between py-2 items-center">
              <span className="text-slate-400">Gateway Reference</span>
              <span className="font-mono text-slate-300">
                {payment.gatewayReference || '— (In-Flight / Simulated)'}
              </span>
            </div>

            {/* Refund Financial Summary (if captured/refunded) */}
            {(payment.status === 'CAPTURED' ||
              payment.status === 'PARTIALLY_REFUNDED' ||
              payment.status === 'REFUNDED') && (
              <div className="flex justify-between py-2 items-center bg-slate-900/60 px-3 rounded-lg border border-slate-700/60">
                <div>
                  <span className="text-slate-400 font-medium">Refunded Total:</span>
                  <span className="text-purple-300 font-mono font-bold ml-2">
                    {formatCentsToCurrency(totalRefundedCents, payment.currency)}
                  </span>
                </div>
                <div>
                  <span className="text-slate-400 font-medium">Remaining:</span>
                  <span className="text-emerald-400 font-mono font-bold ml-2">
                    {formatCentsToCurrency(remainingRefundableCents, payment.currency)}
                  </span>
                </div>
              </div>
            )}
          </div>
        </Card>

        {/* Right Column: Idempotency & Database Integrity */}
        <Card
          title="Idempotency & Concurrency Guard"
          subtitle="Multi-tenant consistency metadata"
        >
          <div className="space-y-4 text-xs font-sans divide-y divide-slate-700/50">
            <div className="flex justify-between py-2 items-center">
              <span className="text-slate-400 flex items-center gap-1.5">
                <Key className="w-3.5 h-3.5 text-slate-500" />
                Idempotency Key
              </span>
              <div className="flex items-center gap-1 font-mono text-slate-200">
                <span>{payment.idempotencyKey}</span>
                <CopyButton text={payment.idempotencyKey} />
              </div>
            </div>

            <div className="flex justify-between py-2 items-center">
              <span className="text-slate-400 flex items-center gap-1.5">
                <Shield className="w-3.5 h-3.5 text-slate-500" />
                Optimistic Lock Version
              </span>
              <span className="font-mono bg-slate-900 px-2 py-0.5 rounded border border-slate-700 text-emerald-400">
                v{payment.version}
              </span>
            </div>

            <div className="flex justify-between py-2 items-center">
              <span className="text-slate-400">Merchant Tenant ID</span>
              <div className="flex items-center gap-1 font-mono text-slate-300">
                <span>{payment.merchantId}</span>
                <CopyButton text={payment.merchantId} />
              </div>
            </div>

            <div className="flex justify-between py-2 items-center">
              <span className="text-slate-400">Last Updated</span>
              <span className="text-slate-300 font-mono">{formatDate(payment.updatedAt)}</span>
            </div>
          </div>
        </Card>
      </div>

      {/* Refunds History Section */}
      <Card
        title="Refunds History"
        subtitle="List of all partial and full refund transactions for this payment"
        action={
          <div className="flex items-center gap-2">
            {canRefund && (
              <Button
                variant="outline"
                size="sm"
                onClick={handleOpenRefundModal}
                className="gap-1.5 text-xs bg-purple-950/30 border-purple-500/40 text-purple-200 hover:bg-purple-900/40"
              >
                <RotateCcw className="w-3.5 h-3.5 text-purple-400" />
                Issue Refund
              </Button>
            )}
            <Button
              variant="ghost"
              size="sm"
              onClick={() => refetchRefunds()}
              className="text-xs h-7 px-2 text-slate-400"
            >
              <RotateCw className="w-3.5 h-3.5" />
            </Button>
          </div>
        }
        className="p-0 overflow-hidden"
      >
        {isRefundsLoading ? (
          <div className="py-12 flex justify-center">
            <Spinner />
          </div>
        ) : refunds.length === 0 ? (
          <div className="py-12 text-center">
            <RotateCcw className="w-8 h-8 text-slate-600 mx-auto mb-2 opacity-50" />
            <p className="text-sm font-medium text-slate-300">No refunds recorded for this payment</p>
            <p className="text-xs text-slate-500 mt-1">
              {payment.status === 'CAPTURED'
                ? 'You can issue full or partial refunds against this captured transaction.'
                : 'Refunds can only be issued once the payment is captured.'}
            </p>
          </div>
        ) : (
          <div>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead className="text-[11px] text-slate-400 uppercase bg-slate-900/80 border-b border-slate-700/60">
                  <tr>
                    <th className="px-4 py-3 font-semibold">Refund ID</th>
                    <th className="px-4 py-3 font-semibold">Amount</th>
                    <th className="px-4 py-3 font-semibold">Status</th>
                    <th className="px-4 py-3 font-semibold">Reason</th>
                    <th className="px-4 py-3 font-semibold">Idempotency Key</th>
                    <th className="px-4 py-3 font-semibold">Created At</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-700/40 font-mono">
                  {refunds.map((refund) => (
                    <tr key={refund.id} className="hover:bg-slate-800/40 transition-colors">
                      <td className="px-4 py-3 text-slate-200">
                        <div className="flex items-center gap-1">
                          <span>{truncateId(refund.id, 8)}</span>
                          <CopyButton text={refund.id} />
                        </div>
                      </td>
                      <td className="px-4 py-3 text-purple-300 font-semibold font-sans">
                        {formatCentsToCurrency(refund.amountCents, refund.currency)}
                      </td>
                      <td className="px-4 py-3 font-sans">
                        {getRefundStatusBadge(refund.status)}
                      </td>
                      <td className="px-4 py-3 text-slate-300 font-sans">
                        {refund.reason || <span className="text-slate-500 italic">No reason provided</span>}
                      </td>
                      <td className="px-4 py-3 text-slate-400">
                        <div className="flex items-center gap-1">
                          <span>{truncateId(refund.idempotencyKey, 10)}</span>
                          <CopyButton text={refund.idempotencyKey} />
                        </div>
                      </td>
                      <td className="px-4 py-3 text-slate-400 font-sans">
                        {formatDate(refund.createdAt)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Refunds Summary Banner */}
            <div className="px-4 py-3 bg-slate-900/60 border-t border-slate-700/60 flex flex-wrap items-center justify-between gap-4 text-xs font-sans">
              <div className="flex items-center gap-4">
                <span className="text-slate-400">
                  Total Refunds Issued: <strong className="text-slate-200">{refunds.length}</strong>
                </span>
                <span className="text-slate-400">
                  Total Settled Refund Amount:{' '}
                  <strong className="text-purple-300 font-mono">
                    {formatCentsToCurrency(totalRefundedCents, payment.currency)}
                  </strong>
                </span>
              </div>
              <div className="text-slate-400">
                Remaining Refundable:{' '}
                <strong className="text-emerald-400 font-mono">
                  {formatCentsToCurrency(remainingRefundableCents, payment.currency)}
                </strong>
              </div>
            </div>
          </div>
        )}
      </Card>

      {/* JSON Metadata Card */}
      <Card
        title="Custom Metadata (JSONB)"
        subtitle="Unstructured payload stored in PostgreSQL jsonb column"
        action={<FileJson className="w-4 h-4 text-slate-500" />}
      >
        <pre className="bg-slate-950 p-4 rounded-lg font-mono text-xs text-slate-300 overflow-x-auto border border-slate-800">
          {JSON.stringify(payment.metadata, null, 2)}
        </pre>
      </Card>

      {/* Capture Confirmation Modal */}
      {isCaptureModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-950/80 backdrop-blur-sm animate-in fade-in duration-150">
          <div className="bg-slate-900 border border-slate-700 rounded-xl shadow-2xl w-full max-w-lg overflow-hidden animate-in zoom-in-95 duration-150">
            <div className="flex items-center justify-between px-6 py-4 border-b border-slate-800 bg-slate-900/90">
              <div className="flex items-center gap-2.5">
                <div className="p-2 rounded-lg bg-emerald-500/15 text-emerald-400 border border-emerald-500/30">
                  <DollarSign className="w-5 h-5" />
                </div>
                <div>
                  <h3 className="text-base font-bold text-slate-100">Capture Payment Funds</h3>
                  <p className="text-xs text-slate-400">Move authorized hold to settled ledger state</p>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setIsCaptureModalOpen(false)}
                className="text-slate-400 hover:text-slate-200"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            <div className="p-6 space-y-4 text-xs text-slate-300">
              {actionErrorMessage && (
                <div className="p-3 rounded-lg bg-rose-950/50 border border-rose-500/40 text-rose-300 flex items-center gap-2">
                  <AlertTriangle className="w-4 h-4 shrink-0" />
                  <span>{actionErrorMessage}</span>
                </div>
              )}

              <div className="bg-slate-950 p-4 rounded-lg border border-slate-800 space-y-2 font-mono">
                <div className="flex justify-between">
                  <span className="text-slate-500">Payment ID:</span>
                  <span className="text-slate-200">{payment.id}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-slate-500">Capture Amount:</span>
                  <span className="text-emerald-400 font-bold text-sm">
                    {formatCentsToCurrency(payment.amountCents, payment.currency)}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-slate-500">Customer ID:</span>
                  <span className="text-slate-300">{payment.customerId}</span>
                </div>
              </div>

              <div className="space-y-2">
                <Input
                  label="Idempotency Key (Generated Header)"
                  value={captureIdempotencyKey}
                  onChange={(e) => setCaptureIdempotencyKey(e.target.value)}
                  className="font-mono text-xs"
                />
                <p className="text-[11px] text-slate-500">
                  Guarantees at-most-once capture execution across transient network retries.
                </p>
              </div>

              <div className="p-3 rounded-lg bg-slate-800/60 border border-slate-700/60 text-[11px] text-slate-400 flex items-start gap-2.5">
                <Scale className="w-4 h-4 text-emerald-400 shrink-0 mt-0.5" />
                <div>
                  <strong>Financial Mechanics:</strong> Capturing generates balancing double-entry ledger journals (Asset: Cash Clearing, Liability: Merchant Settlement, Revenue: Platform Fee) and updates real-time merchant balances.
                </div>
              </div>
            </div>

            <div className="px-6 py-4 bg-slate-900/90 border-t border-slate-800 flex items-center justify-end gap-3">
              <Button
                variant="secondary"
                size="sm"
                onClick={() => setIsCaptureModalOpen(false)}
                disabled={captureMutation.isPending}
              >
                Cancel
              </Button>
              <Button
                variant="primary"
                size="sm"
                onClick={handleConfirmCapture}
                isLoading={captureMutation.isPending}
                className="gap-1.5"
              >
                <CheckCircle2 className="w-4 h-4" />
                Confirm & Capture
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* Issue Refund Modal */}
      {isRefundModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-950/80 backdrop-blur-sm animate-in fade-in duration-150">
          <div className="bg-slate-900 border border-slate-700 rounded-xl shadow-2xl w-full max-w-lg overflow-hidden animate-in zoom-in-95 duration-150">
            <div className="flex items-center justify-between px-6 py-4 border-b border-slate-800 bg-slate-900/90">
              <div className="flex items-center gap-2.5">
                <div className="p-2 rounded-lg bg-purple-500/15 text-purple-400 border border-purple-500/30">
                  <RotateCcw className="w-5 h-5" />
                </div>
                <div>
                  <h3 className="text-base font-bold text-slate-100">Issue Payment Refund</h3>
                  <p className="text-xs text-slate-400">Reverse captured funds with double-entry journal reversal</p>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setIsRefundModalOpen(false)}
                className="text-slate-400 hover:text-slate-200"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            <form onSubmit={handleConfirmRefund}>
              <div className="p-6 space-y-4 text-xs text-slate-300">
                {actionErrorMessage && (
                  <div className="p-3 rounded-lg bg-rose-950/50 border border-rose-500/40 text-rose-300 flex items-center gap-2">
                    <AlertTriangle className="w-4 h-4 shrink-0" />
                    <span>{actionErrorMessage}</span>
                  </div>
                )}

                <div className="bg-slate-950 p-4 rounded-lg border border-slate-800 space-y-2 font-mono">
                  <div className="flex justify-between">
                    <span className="text-slate-500">Payment ID:</span>
                    <span className="text-slate-200">{payment.id}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-slate-500">Original Amount:</span>
                    <span className="text-slate-200">
                      {formatCentsToCurrency(payment.amountCents, payment.currency)}
                    </span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-slate-500">Already Refunded:</span>
                    <span className="text-purple-400">
                      {formatCentsToCurrency(totalRefundedCents, payment.currency)}
                    </span>
                  </div>
                  <div className="flex justify-between border-t border-slate-800 pt-1.5 font-bold">
                    <span className="text-slate-400">Max Refundable:</span>
                    <span className="text-emerald-400">
                      {formatCentsToCurrency(remainingRefundableCents, payment.currency)}
                    </span>
                  </div>
                </div>

                <div className="space-y-1.5">
                  <div className="flex items-center justify-between">
                    <label className="block text-xs font-medium text-slate-300">
                      Refund Amount ({payment.currency})
                    </label>
                    <button
                      type="button"
                      onClick={() => setRefundAmountDollars((remainingRefundableCents / 100).toFixed(2))}
                      className="text-[11px] text-emerald-400 hover:text-emerald-300 font-semibold"
                    >
                      Full Remaining ({formatCentsToCurrency(remainingRefundableCents, payment.currency)})
                    </button>
                  </div>
                  <Input
                    type="number"
                    step="0.01"
                    min="0.01"
                    max={(remainingRefundableCents / 100).toFixed(2)}
                    value={refundAmountDollars}
                    onChange={(e) => setRefundAmountDollars(e.target.value)}
                    placeholder="0.00"
                    required
                    className="font-mono text-sm"
                  />
                </div>

                <div className="space-y-1.5">
                  <Input
                    label="Refund Reason (Optional)"
                    placeholder="e.g., Customer requested return, Order cancelled"
                    value={refundReason}
                    onChange={(e) => setRefundReason(e.target.value)}
                  />
                </div>

                <div className="space-y-1.5">
                  <Input
                    label="Idempotency Key"
                    value={refundIdempotencyKey}
                    onChange={(e) => setRefundIdempotencyKey(e.target.value)}
                    className="font-mono text-xs"
                  />
                </div>
              </div>

              <div className="px-6 py-4 bg-slate-900/90 border-t border-slate-800 flex items-center justify-end gap-3">
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={() => setIsRefundModalOpen(false)}
                  disabled={refundMutation.isPending}
                >
                  Cancel
                </Button>
                <Button
                  type="submit"
                  variant="primary"
                  size="sm"
                  isLoading={refundMutation.isPending}
                  className="gap-1.5 bg-purple-600 hover:bg-purple-500 text-white"
                >
                  <RotateCcw className="w-4 h-4" />
                  Process Refund
                </Button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
