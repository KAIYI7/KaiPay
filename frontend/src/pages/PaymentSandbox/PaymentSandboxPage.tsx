import React, { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { paymentsApi } from '../../api/paymentsApi';
import { useMerchant } from '../../context/MerchantContext';
import { Payment } from '../../models/payment';
import { ApiError } from '../../models/api';
import { Card } from '../../components/common/Card';
import { Button } from '../../components/common/Button';
import { Input } from '../../components/common/Input';
import { Select } from '../../components/common/Select';
import { IdempotencyConflictBanner } from '../../components/payment/IdempotencyConflictBanner';
import { Spinner } from '../../components/common/Spinner';
import { generateIdempotencyKey } from '../../utils/formatters';
import { FlaskConical, Send, RefreshCw, AlertTriangle, CheckCircle2, ArrowRight } from 'lucide-react';
import { Link } from 'react-router-dom';

export const PaymentSandboxPage: React.FC = () => {
  const { activeMerchant } = useMerchant();
  const queryClient = useQueryClient();

  // Form State
  const [dollars, setDollars] = useState<string>('50.00');
  const [currency, setCurrency] = useState<string>('USD');
  const [customerId, setCustomerId] = useState<string>(
    activeMerchant.defaultCustomers[0]?.id || '22222222-2222-2222-2222-222222222221'
  );
  const [orderId, setOrderId] = useState<string>('ORD-' + Math.floor(10000 + Math.random() * 90000));
  const [idempotencyKey, setIdempotencyKey] = useState<string>(() => generateIdempotencyKey());

  // Result state
  const [lastPayment, setLastPayment] = useState<Payment | null>(null);
  const [idempotencyMode, setIdempotencyMode] = useState<'initial' | 'replay' | 'conflict' | null>(null);
  const [lastError, setLastError] = useState<ApiError | null>(null);

  // Update customer dropdown if merchant changes
  React.useEffect(() => {
    if (activeMerchant.defaultCustomers.length > 0) {
      setCustomerId(activeMerchant.defaultCustomers[0].id);
    }
  }, [activeMerchant]);

  const createPaymentMutation = useMutation({
    mutationFn: async (params: { key: string; amountCents: number; custId: string; curr: string; order: string }) => {
      return paymentsApi.createPayment(params.key, {
        amountCents: params.amountCents,
        currency: params.curr,
        customerId: params.custId,
        metadata: { orderId: params.order },
      });
    },
    onSuccess: (payment, variables) => {
      setLastError(null);
      // Check if it's a replay of existing payment
      if (lastPayment && lastPayment.idempotencyKey === variables.key && lastPayment.id === payment.id) {
        setIdempotencyMode('replay');
      } else {
        setIdempotencyMode('initial');
      }
      setLastPayment(payment);
      queryClient.invalidateQueries({ queryKey: ['payments', activeMerchant.id] });
    },
    onError: (err: ApiError) => {
      if (err.status === 409) {
        setIdempotencyMode('conflict');
      } else {
        setIdempotencyMode(null);
      }
      setLastError(err);
    },
  });

  const getAmountCents = () => {
    const num = parseFloat(dollars);
    if (isNaN(num) || num <= 0) return 1000;
    return Math.round(num * 100);
  };

  const handleStandardSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    createPaymentMutation.mutate({
      key: idempotencyKey,
      amountCents: getAmountCents(),
      custId: customerId,
      curr: currency,
      order: orderId,
    });
  };

  // Scenario 1: Test Idempotent Replay (Exact same key & exact same payload)
  const handleTestIdempotentReplay = () => {
    createPaymentMutation.mutate({
      key: idempotencyKey,
      amountCents: getAmountCents(),
      custId: customerId,
      curr: currency,
      order: orderId,
    });
  };

  // Scenario 2: Test Idempotency Conflict (Same key, but mutate amount to trigger 409 Conflict)
  const handleTestConflictMismatch = () => {
    const mutatedAmountCents = getAmountCents() + 2500; // Alter amount by $25.00
    createPaymentMutation.mutate({
      key: idempotencyKey,
      amountCents: mutatedAmountCents,
      custId: customerId,
      curr: currency,
      order: orderId,
    });
  };

  const handleGenerateNewKey = () => {
    setIdempotencyKey(generateIdempotencyKey());
    setOrderId('ORD-' + Math.floor(10000 + Math.random() * 90000));
    setIdempotencyMode(null);
    setLastError(null);
  };

  const customerOptions = activeMerchant.defaultCustomers.map((c) => ({
    value: c.id,
    label: `${c.name} (${c.email})`,
  }));

  const currencyOptions = [
    { value: 'USD', label: 'USD - United States Dollar' },
    { value: 'EUR', label: 'EUR - Euro' },
    { value: 'GBP', label: 'GBP - British Pound' },
    { value: 'TWD', label: 'TWD - New Taiwan Dollar' },
  ];

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <div className="flex items-center gap-2 text-xs font-semibold text-emerald-400 uppercase tracking-wider">
          <FlaskConical className="w-4 h-4" />
          Interactive Testing Studio
        </div>
        <h1 className="text-2xl font-bold text-slate-100 tracking-tight mt-1">
          Payment Creation & Idempotency Sandbox
        </h1>
        <p className="text-sm text-slate-400 mt-1">
          Test real-time payment ingestion, deterministic state assignment, and duplicate request conflict scenarios
          against the Spring Boot backend.
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* Left Column: Interactive Form & 1-Click Test Actions */}
        <div className="lg:col-span-7 space-y-6">
          <Card title="Payment Intent Parameters" subtitle="Configure request payload and client idempotency key">
            <form onSubmit={handleStandardSubmit} className="space-y-4">
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <Input
                  label="Amount (Dollars)"
                  type="number"
                  step="0.01"
                  min="0.01"
                  value={dollars}
                  onChange={(e) => setDollars(e.target.value)}
                  helperText={`Backend will store as ${getAmountCents()} cents (BIGINT)`}
                  required
                />

                <Select
                  label="Currency"
                  options={currencyOptions}
                  value={currency}
                  onChange={(e) => setCurrency(e.target.value)}
                />
              </div>

              <Select
                label="Customer (Tenant Scoped)"
                options={customerOptions}
                value={customerId}
                onChange={(e) => setCustomerId(e.target.value)}
                helperText="Pre-seeded test customer belonging to active merchant"
              />

              <Input
                label="Order Reference (Metadata)"
                value={orderId}
                onChange={(e) => setOrderId(e.target.value)}
                helperText="Stored in PostgreSQL JSONB metadata column"
              />

              <div>
                <div className="flex items-center justify-between mb-1.5">
                  <label className="block text-xs font-medium text-slate-300">
                    Idempotency-Key Header
                  </label>
                  <button
                    type="button"
                    onClick={handleGenerateNewKey}
                    className="text-[11px] text-emerald-400 hover:text-emerald-300 flex items-center gap-1"
                  >
                    <RefreshCw className="w-3 h-3" />
                    Generate New Key
                  </button>
                </div>
                <input
                  type="text"
                  value={idempotencyKey}
                  onChange={(e) => setIdempotencyKey(e.target.value)}
                  className="w-full bg-slate-900 border border-slate-700 rounded-lg px-3.5 py-2 text-xs font-mono text-slate-200 focus:outline-none focus:ring-2 focus:ring-emerald-500"
                  required
                />
              </div>

              <div className="pt-2 flex flex-col sm:flex-row gap-3">
                <Button
                  type="submit"
                  variant="primary"
                  isLoading={createPaymentMutation.isPending}
                  className="gap-2 flex-1"
                >
                  <Send className="w-4 h-4" />
                  Submit Payment (HTTP POST)
                </Button>

                <Button
                  type="button"
                  variant="outline"
                  onClick={handleGenerateNewKey}
                  className="text-xs"
                >
                  Reset Form
                </Button>
              </div>
            </form>
          </Card>

          {/* Quick Idempotency Test Harness Buttons */}
          <Card
            title="1-Click Idempotency Scenarios"
            subtitle="Demonstrate distributed consistency and conflict detection to recruiters"
          >
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={handleTestIdempotentReplay}
                disabled={createPaymentMutation.isPending}
                className="gap-2 justify-start text-left h-auto py-2.5"
              >
                <RefreshCw className="w-4 h-4 text-sky-400 shrink-0" />
                <div>
                  <div className="font-semibold text-xs text-sky-300">1. Test Idempotent Replay</div>
                  <div className="text-[10px] text-slate-400 font-normal">
                    Re-send exact same key & payload ➔ Expect 201 Cached Replay
                  </div>
                </div>
              </Button>

              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={handleTestConflictMismatch}
                disabled={createPaymentMutation.isPending}
                className="gap-2 justify-start text-left h-auto py-2.5 border border-amber-500/30"
              >
                <AlertTriangle className="w-4 h-4 text-amber-400 shrink-0" />
                <div>
                  <div className="font-semibold text-xs text-amber-300">2. Test Payload Conflict</div>
                  <div className="text-[10px] text-slate-400 font-normal">
                    Re-send key with mutated amount ➔ Expect 409 Conflict
                  </div>
                </div>
              </Button>
            </div>
          </Card>
        </div>

        {/* Right Column: Live Backend Response Visualizer */}
        <div className="lg:col-span-5 space-y-6">
          {/* Status Alert Banners */}
          {idempotencyMode === 'replay' && lastPayment && (
            <IdempotencyConflictBanner
              type="replay"
              message={`Subsequent request with key "${idempotencyKey}" safely replayed the original payment.`}
              idempotencyKey={idempotencyKey}
            />
          )}

          {idempotencyMode === 'conflict' && lastError && (
            <IdempotencyConflictBanner
              type="conflict"
              message={lastError.message}
              idempotencyKey={idempotencyKey}
            />
          )}

          {idempotencyMode === 'initial' && lastPayment && (
            <div className="rounded-xl border border-emerald-500/40 bg-emerald-950/20 p-4 text-emerald-200 flex items-start gap-3">
              <CheckCircle2 className="w-5 h-5 text-emerald-400 shrink-0 mt-0.5" />
              <div>
                <h4 className="text-sm font-semibold text-emerald-300">
                  Payment Created Successfully (HTTP 201)
                </h4>
                <p className="text-xs text-emerald-200/80 mt-1">
                  New payment aggregate inserted with status <code className="font-mono font-bold">CREATED</code> and
                  idempotency snapshot saved.
                </p>
              </div>
            </div>
          )}

          {lastError && lastError.status !== 409 && (
            <div className="rounded-xl border border-rose-500/40 bg-rose-950/30 p-4 text-rose-200 flex items-start gap-3">
              <AlertTriangle className="w-5 h-5 text-rose-400 shrink-0 mt-0.5" />
              <div>
                <h4 className="text-sm font-semibold text-rose-300">
                  HTTP {lastError.status} {lastError.errorName}
                </h4>
                <p className="text-xs text-rose-200/90 mt-1">{lastError.message}</p>
                {lastError.validationErrors && (
                  <ul className="text-xs mt-2 list-disc list-inside space-y-0.5 text-rose-300">
                    {lastError.validationErrors.map((v, i) => (
                      <li key={i}>
                        <span className="font-semibold font-mono">{v.field}:</span> {v.message}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
          )}

          {/* Response Payload Inspector */}
          <Card
            title="Live API Response Inspector"
            subtitle="Raw payload received from Spring Boot"
            action={
              lastPayment && (
                <Link to={`/payments/${lastPayment.id}`}>
                  <Button variant="ghost" size="sm" className="text-xs gap-1 text-emerald-400">
                    <span>Inspect Payment</span>
                    <ArrowRight className="w-3.5 h-3.5" />
                  </Button>
                </Link>
              )
            }
          >
            {createPaymentMutation.isPending && (
              <div className="py-16 flex flex-col items-center justify-center gap-2">
                <Spinner />
                <span className="text-xs text-slate-400">Executing transaction in PostgreSQL...</span>
              </div>
            )}

            {!createPaymentMutation.isPending && lastPayment && (
              <pre className="bg-slate-950 p-4 rounded-lg font-mono text-xs text-emerald-300 overflow-x-auto border border-slate-800">
                {JSON.stringify(lastPayment, null, 2)}
              </pre>
            )}

            {!createPaymentMutation.isPending && !lastPayment && !lastError && (
              <div className="py-16 text-center text-xs text-slate-500">
                Submit a payment request above to inspect the real-time response payload and state machine behavior.
              </div>
            )}

            {!createPaymentMutation.isPending && lastError && (
              <pre className="bg-slate-950 p-4 rounded-lg font-mono text-xs text-rose-300 overflow-x-auto border border-slate-800">
                {JSON.stringify(
                  {
                    status: lastError.status,
                    error: lastError.errorName,
                    message: lastError.message,
                    path: lastError.path,
                    validationErrors: lastError.validationErrors,
                  },
                  null,
                  2
                )}
              </pre>
            )}
          </Card>
        </div>
      </div>
    </div>
  );
};
