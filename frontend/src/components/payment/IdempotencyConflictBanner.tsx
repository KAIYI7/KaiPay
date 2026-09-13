import React from 'react';
import { AlertTriangle, Info } from 'lucide-react';

interface IdempotencyConflictBannerProps {
  type: 'replay' | 'conflict';
  message: string;
  idempotencyKey: string;
}

export const IdempotencyConflictBanner: React.FC<IdempotencyConflictBannerProps> = ({
  type,
  message,
  idempotencyKey,
}) => {
  if (type === 'replay') {
    return (
      <div className="rounded-xl border border-sky-500/30 bg-sky-950/20 p-4 text-sky-200">
        <div className="flex items-start gap-3">
          <Info className="w-5 h-5 text-sky-400 shrink-0 mt-0.5" />
          <div>
            <h4 className="text-sm font-semibold text-sky-300">
              Idempotent Replay (HTTP 201/200 Cached Result)
            </h4>
            <p className="text-xs text-sky-200/80 mt-1">{message}</p>
            <div className="mt-2 text-xs font-mono bg-sky-900/40 border border-sky-700/50 px-2 py-1 rounded inline-block text-sky-300">
              Key: {idempotencyKey}
            </div>
            <p className="text-[11px] text-sky-400/70 mt-1.5">
              The backend returned the existing payment from the database cache with 0 duplicate records created.
            </p>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="rounded-xl border border-amber-500/40 bg-amber-950/30 p-4 text-amber-200">
      <div className="flex items-start gap-3">
        <AlertTriangle className="w-5 h-5 text-amber-400 shrink-0 mt-0.5" />
        <div>
          <h4 className="text-sm font-semibold text-amber-300">
            Idempotency Conflict (HTTP 409 Conflict)
          </h4>
          <p className="text-xs text-amber-200/90 mt-1">{message}</p>
          <div className="mt-2 text-xs font-mono bg-amber-900/40 border border-amber-700/50 px-2 py-1 rounded inline-block text-amber-300">
            Key: {idempotencyKey}
          </div>
          <p className="text-[11px] text-amber-400/80 mt-1.5">
            The SHA-256 payload hash did not match the original transaction or a concurrent collision occurred on unique constraint <code className="font-mono">uk_merchant_idempotency</code>.
          </p>
        </div>
      </div>
    </div>
  );
};
