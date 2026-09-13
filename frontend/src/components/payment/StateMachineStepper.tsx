import React from 'react';
import { PaymentStatus } from '../../models/payment';
import { Check, X, ArrowRight } from 'lucide-react';

interface StateMachineStepperProps {
  currentStatus: PaymentStatus;
  failureCode?: string | null;
  failureMessage?: string | null;
}

export const StateMachineStepper: React.FC<StateMachineStepperProps> = ({
  currentStatus,
  failureCode,
  failureMessage,
}) => {
  const isFailed = currentStatus === 'FAILED' || currentStatus === 'DECLINED';
  const isRefunded =
    currentStatus === 'REFUNDED' ||
    currentStatus === 'PARTIALLY_REFUNDED' ||
    currentStatus === 'REFUND_PENDING';

  const steps: { key: PaymentStatus; label: string; description: string }[] = [
    { key: 'CREATED', label: '1. Created', description: 'Idempotency verified & stored' },
    { key: 'PROCESSING', label: '2. Processing', description: 'Dispatched to Kafka worker' },
    { key: 'AUTHORIZED', label: '3. Authorized', description: 'Acquirer hold granted' },
    { key: 'CAPTURED', label: '4. Captured', description: 'Settled to ledger' },
  ];

  const getStepState = (_stepKey: PaymentStatus, index: number) => {
    const statusOrder: PaymentStatus[] = ['CREATED', 'PROCESSING', 'AUTHORIZED', 'CAPTURED'];
    const currentIndex = statusOrder.indexOf(currentStatus);

    if (isFailed) {
      if (index === 0) return 'completed';
      return 'failed';
    }

    if (isRefunded) {
      return 'completed';
    }

    if (currentIndex >= index) {
      return 'completed';
    }
    if (currentIndex === index - 1) {
      return 'active';
    }
    return 'upcoming';
  };

  return (
    <div className="w-full bg-slate-900/60 border border-slate-700/60 rounded-xl p-5">
      <div className="flex items-center justify-between mb-4">
        <div>
          <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-400">
            Payment State Machine Trajectory
          </h4>
          <p className="text-xs text-slate-500 mt-0.5">
            Deterministic domain state machine enforced by Spring Data JPA entity
          </p>
        </div>
        <span className="text-xs font-mono bg-slate-800 text-emerald-400 px-2.5 py-1 rounded-md border border-slate-700">
          Status: {currentStatus}
        </span>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-3 relative">
        {steps.map((step, idx) => {
          const state = getStepState(step.key, idx);

          return (
            <div
              key={step.key}
              className={`relative rounded-lg p-3.5 border transition-all ${
                state === 'completed'
                  ? 'bg-emerald-950/30 border-emerald-500/40 text-emerald-300'
                  : state === 'active'
                  ? 'bg-amber-950/30 border-amber-500/50 text-amber-300 ring-1 ring-amber-500/30'
                  : state === 'failed'
                  ? 'bg-rose-950/20 border-rose-500/30 text-rose-400 opacity-60'
                  : 'bg-slate-800/40 border-slate-700/40 text-slate-400'
              }`}
            >
              <div className="flex items-center justify-between mb-1.5">
                <span className="text-xs font-semibold">{step.label}</span>
                {state === 'completed' && <Check className="w-4 h-4 text-emerald-400" />}
                {state === 'failed' && <X className="w-4 h-4 text-rose-400" />}
              </div>
              <p className="text-[11px] text-slate-400 leading-tight">{step.description}</p>
            </div>
          );
        })}
      </div>

      {isFailed && (
        <div className="mt-4 p-3 rounded-lg bg-rose-500/10 border border-rose-500/30 flex items-start gap-2.5 text-xs text-rose-300">
          <X className="w-4 h-4 text-rose-400 shrink-0 mt-0.5" />
          <div>
            <span className="font-semibold">Terminal State Failure: </span>
            {failureCode && <code className="font-mono text-rose-200 mr-1">[{failureCode}]</code>}
            {failureMessage || 'Transaction declined or halted.'}
          </div>
        </div>
      )}

      {isRefunded && (
        <div className="mt-4 p-3 rounded-lg bg-purple-500/10 border border-purple-500/30 flex items-start gap-2.5 text-xs text-purple-300">
          <ArrowRight className="w-4 h-4 text-purple-400 shrink-0 mt-0.5" />
          <div>
            <span className="font-semibold">Refund Transition: </span>
            This payment was captured and subsequently moved to {currentStatus}.
          </div>
        </div>
      )}
    </div>
  );
};
