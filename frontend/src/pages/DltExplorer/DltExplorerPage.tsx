import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { dltApi } from '../../api/dltApi';
import { DeadLetterEvent } from '../../models/dlt';
import { Card } from '../../components/common/Card';
import { Button } from '../../components/common/Button';
import { Input } from '../../components/common/Input';
import { CopyButton } from '../../components/common/CopyButton';
import { Spinner } from '../../components/common/Spinner';
import { formatDate, truncateId } from '../../utils/formatters';
import {
  AlertOctagon,
  RefreshCw,
  ChevronLeft,
  ChevronRight,
  ArrowRight,
  Radio,
  AlertTriangle,
  Code2,
  X,
  ExternalLink,
  Info,
  Search,
  CheckCircle2,
  Flame,
} from 'lucide-react';

export const DltExplorerPage: React.FC = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  const initialPaymentId = searchParams.get('paymentId') || '';

  const [paymentIdInput, setPaymentIdInput] = useState<string>(initialPaymentId);
  const [paymentIdFilter, setPaymentIdFilter] = useState<string>(initialPaymentId);
  const [page, setPage] = useState<number>(0);
  const size = 10;
  const [selectedEvent, setSelectedEvent] = useState<DeadLetterEvent | null>(null);

  const {
    data: dltPage,
    isLoading,
    isError,
    error,
    refetch,
    isFetching,
  } = useQuery({
    queryKey: ['dlt-events', page, size, paymentIdFilter],
    queryFn: () =>
      dltApi.listDeadLetterEvents({
        page,
        size,
        paymentId: paymentIdFilter.trim() || undefined,
      }),
    refetchInterval: 3000, // 3-second live polling
  });

  const events = dltPage?.content || [];
  const totalPages = dltPage?.totalPages || 0;
  const totalElements = dltPage?.totalElements || 0;

  const handleSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setPaymentIdFilter(paymentIdInput.trim());
    setPage(0);
    if (paymentIdInput.trim()) {
      setSearchParams({ paymentId: paymentIdInput.trim() });
    } else {
      setSearchParams({});
    }
  };

  const handleClearFilter = () => {
    setPaymentIdInput('');
    setPaymentIdFilter('');
    setPage(0);
    setSearchParams({});
  };

  const parseJson = (str: string) => {
    try {
      return JSON.stringify(JSON.parse(str), null, 2);
    } catch {
      return str;
    }
  };

  const getExceptionBadgeClass = (exceptionClass: string) => {
    if (exceptionClass.includes('PoisonPill') || exceptionClass.includes('Deserialization')) {
      return 'bg-rose-500/20 text-rose-300 border-rose-500/40';
    }
    if (exceptionClass.includes('Transient') || exceptionClass.includes('Timeout')) {
      return 'bg-amber-500/20 text-amber-300 border-amber-500/40';
    }
    return 'bg-red-500/20 text-red-300 border-red-500/40';
  };

  return (
    <div className="space-y-6">
      {/* Top Banner: Internal Platform Operational Tool */}
      <div className="bg-slate-900/90 border border-rose-500/30 rounded-xl p-5 shadow-lg relative overflow-hidden">
        <div className="absolute top-0 right-0 w-96 h-96 bg-rose-500/5 rounded-full blur-3xl pointer-events-none -mr-20 -mt-20"></div>

        <div className="flex flex-col lg:flex-row items-start lg:items-center justify-between gap-4 relative z-10">
          <div className="space-y-1.5">
            <div className="flex flex-wrap items-center gap-2">
              <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-md text-[11px] font-bold font-mono uppercase bg-rose-500/20 text-rose-400 border border-rose-500/40">
                <AlertOctagon className="w-3 h-3" />
                Dead Letter Topic (DLT)
              </span>
              <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-semibold bg-rose-950/80 text-rose-300 border border-rose-700/50">
                <span className="w-1.5 h-1.5 rounded-full bg-rose-400 animate-ping"></span>
                Live Stream (3s)
              </span>
            </div>

            <h1 className="text-xl font-bold text-slate-100 tracking-tight">
              Internal Platform Operational Tool: Dead Letter Topic (DLT) Explorer &amp; Poison Pill Graveyard
            </h1>

            <p className="text-xs text-slate-400 max-w-4xl leading-relaxed">
              Non-blocking retry exhaustion buffer and poison pill isolation monitoring. Messages that exceed maximum retry attempts
              on <code className="text-amber-400 font-mono">kaipay.payment.processing-retry</code> or trigger deserialization failures are routed to
              the <code className="text-rose-400 font-mono">kaipay.payment.processing.DLT</code> topic and persisted in the Dead Letter Graveyard.
            </p>
          </div>

          <div className="flex items-center gap-3 shrink-0 self-end lg:self-center">
            <Button
              variant="outline"
              size="sm"
              onClick={() => refetch()}
              disabled={isFetching}
              className="gap-1.5 text-xs font-mono border-slate-700"
            >
              <RefreshCw className={`w-3.5 h-3.5 ${isFetching ? 'animate-spin text-rose-400' : ''}`} />
              <span>Poll Now</span>
            </Button>
          </div>
        </div>
      </div>

      {/* Filter and Control Bar */}
      <Card className="p-4">
        <div className="flex flex-col sm:flex-row items-center justify-between gap-4">
          <form onSubmit={handleSearchSubmit} className="w-full sm:w-96 flex items-center gap-2">
            <div className="relative flex-1">
              <Input
                placeholder="Filter by Payment ID (UUID)..."
                value={paymentIdInput}
                onChange={(e) => setPaymentIdInput(e.target.value)}
                className="pr-8 text-xs font-mono"
              />
              {paymentIdInput && (
                <button
                  type="button"
                  onClick={handleClearFilter}
                  className="absolute right-2.5 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-200"
                >
                  <X className="w-3.5 h-3.5" />
                </button>
              )}
            </div>
            <Button type="submit" size="sm" variant="secondary" className="text-xs gap-1.5 shrink-0">
              <Search className="w-3.5 h-3.5" />
              <span>Search</span>
            </Button>
          </form>

          <div className="flex items-center gap-4 text-xs text-slate-400 self-end sm:self-center">
            <div className="flex items-center gap-2 font-mono">
              <span className="text-slate-500">Dead Letters:</span>
              <span className="font-bold text-rose-400">{totalElements}</span>
              <span>events</span>
            </div>
            <div className="flex items-center gap-1.5 text-[11px] text-slate-400 bg-slate-900/60 px-2.5 py-1 rounded-md border border-slate-700/50">
              <Radio className="w-3 h-3 text-rose-400 animate-pulse" />
              <span>Auto-refreshing (3s)</span>
            </div>
          </div>
        </div>
      </Card>

      {/* Dead Letter Events Table */}
      <Card className="p-0 overflow-hidden">
        {isLoading && (
          <div className="py-24 flex flex-col items-center justify-center gap-3">
            <Spinner size="lg" />
            <p className="text-xs text-slate-400 font-mono">Reading Dead Letter Topic graveyard...</p>
          </div>
        )}

        {isError && (
          <div className="py-16 text-center space-y-3 px-4">
            <div className="w-10 h-10 rounded-full bg-rose-500/15 border border-rose-500/30 flex items-center justify-center mx-auto text-rose-400">
              <AlertTriangle className="w-5 h-5" />
            </div>
            <h3 className="text-sm font-semibold text-rose-400">Failed to fetch DLT events</h3>
            <p className="text-xs text-slate-400 max-w-md mx-auto">
              {(error as Error)?.message || 'Ensure backend service is running on port 28080.'}
            </p>
            <Button variant="outline" size="sm" onClick={() => refetch()} className="text-xs">
              Retry Connection
            </Button>
          </div>
        )}

        {!isLoading && !isError && events.length === 0 && (
          <div className="py-20 text-center space-y-3">
            <div className="w-12 h-12 rounded-xl bg-slate-800/80 border border-slate-700/60 flex items-center justify-center mx-auto text-emerald-400">
              <CheckCircle2 className="w-6 h-6" />
            </div>
            <p className="text-sm font-semibold text-slate-200">No Dead Letter Events Found</p>
            <p className="text-xs text-slate-500 max-w-md mx-auto">
              {paymentIdFilter
                ? `No dead letter events recorded for payment ID "${paymentIdFilter}".`
                : 'The DLT graveyard is clean. No poison pills or exhausted retry messages have been intercepted.'}
            </p>
            {paymentIdFilter && (
              <Button variant="outline" size="sm" onClick={handleClearFilter} className="text-xs">
                Clear Payment Filter
              </Button>
            )}
          </div>
        )}

        {!isLoading && !isError && events.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead className="text-[11px] text-slate-400 uppercase bg-slate-900/80 border-b border-slate-700/60">
                <tr>
                  <th className="px-4 py-3.5 font-semibold">DLT Record ID</th>
                  <th className="px-4 py-3.5 font-semibold">Payment ID</th>
                  <th className="px-4 py-3.5 font-semibold">Original Topic &amp; Partition</th>
                  <th className="px-4 py-3.5 font-semibold">Exception Class</th>
                  <th className="px-4 py-3.5 font-semibold">Retries</th>
                  <th className="px-4 py-3.5 font-semibold">Failure Message</th>
                  <th className="px-4 py-3.5 font-semibold">Timestamp</th>
                  <th className="px-4 py-3.5 font-semibold text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-700/40 font-mono">
                {events.map((event) => (
                  <tr key={event.id} className="hover:bg-slate-800/40 transition-colors">
                    {/* DLT Record ID */}
                    <td className="px-4 py-3 text-slate-200">
                      <div className="flex items-center gap-1.5">
                        <span className="font-semibold text-slate-100">{truncateId(event.id, 8)}</span>
                        <CopyButton text={event.id} />
                      </div>
                    </td>

                    {/* Payment ID */}
                    <td className="px-4 py-3 text-slate-300 font-sans">
                      {event.paymentId ? (
                        <div className="flex items-center gap-1 font-mono">
                          <Link
                            to={`/payments/${event.paymentId}`}
                            className="text-rose-400 hover:underline flex items-center gap-1"
                            title="Inspect Payment Details"
                          >
                            <span>{truncateId(event.paymentId, 8)}</span>
                            <ExternalLink className="w-3 h-3 opacity-60" />
                          </Link>
                          <CopyButton text={event.paymentId} />
                        </div>
                      ) : (
                        <span className="text-slate-500 italic text-[11px]">None (Poison Pill)</span>
                      )}
                    </td>

                    {/* Original Topic & Partition */}
                    <td className="px-4 py-3 text-slate-300">
                      <div className="flex flex-col">
                        <span className="text-slate-200 text-[11px] font-mono">{event.originalTopic}</span>
                        <span className="text-[10px] text-slate-500 font-mono">
                          P{event.originalPartition} @ offset {event.originalOffset}
                        </span>
                      </div>
                    </td>

                    {/* Exception Class */}
                    <td className="px-4 py-3 font-sans">
                      <span
                        className={`inline-flex items-center px-2 py-0.5 rounded text-[11px] font-mono font-medium border ${getExceptionBadgeClass(
                          event.exceptionClass
                        )}`}
                        title={event.exceptionClass}
                      >
                        {event.exceptionClass.split('.').pop() || event.exceptionClass}
                      </span>
                    </td>

                    {/* Retries */}
                    <td className="px-4 py-3 font-sans">
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px] font-mono font-semibold bg-rose-500/20 text-rose-300 border border-rose-500/40">
                        <Flame className="w-3 h-3 text-rose-400" />
                        {event.retryCount}
                      </span>
                    </td>

                    {/* Failure Message */}
                    <td className="px-4 py-3 font-sans max-w-xs truncate text-slate-400" title={event.failureMessage || ''}>
                      {event.failureMessage || '—'}
                    </td>

                    {/* Timestamp */}
                    <td className="px-4 py-3 text-slate-400 font-sans">{formatDate(event.createdAt)}</td>

                    {/* Actions */}
                    <td className="px-4 py-3 text-right">
                      <Button
                        variant="secondary"
                        size="sm"
                        onClick={() => setSelectedEvent(event)}
                        className="text-xs h-7 px-2.5 font-sans gap-1 bg-slate-800 hover:bg-slate-700 text-slate-200 border border-slate-700"
                      >
                        <Code2 className="w-3.5 h-3.5 text-rose-400" />
                        <span>Inspect</span>
                      </Button>
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
              <span className="font-semibold text-slate-200">{totalPages}</span> (
              <span className="font-semibold text-slate-200">{totalElements}</span> total events)
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

      {/* JSON Inspection Modal */}
      {selectedEvent && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-950/80 backdrop-blur-sm animate-in fade-in duration-150">
          <div className="bg-slate-900 border border-slate-700 rounded-2xl w-full max-w-3xl max-h-[90vh] flex flex-col shadow-2xl overflow-hidden">
            {/* Modal Header */}
            <div className="px-6 py-4 border-b border-slate-800 flex items-center justify-between bg-slate-900/90">
              <div className="flex items-center gap-3">
                <div className="p-2 rounded-lg bg-rose-500/15 border border-rose-500/30 text-rose-400">
                  <AlertOctagon className="w-5 h-5" />
                </div>
                <div>
                  <h3 className="text-base font-bold text-slate-100 flex items-center gap-2">
                    <span>Dead Letter Event Record</span>
                    <span
                      className={`text-xs px-2 py-0.5 rounded font-mono border ${getExceptionBadgeClass(
                        selectedEvent.exceptionClass
                      )}`}
                    >
                      {selectedEvent.exceptionClass.split('.').pop()}
                    </span>
                  </h3>
                  <p className="text-xs text-slate-400 font-mono mt-0.5">DLT ID: {selectedEvent.id}</p>
                </div>
              </div>

              <button
                type="button"
                onClick={() => setSelectedEvent(null)}
                className="p-1.5 rounded-lg text-slate-400 hover:text-slate-200 hover:bg-slate-800 transition-colors"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            {/* Modal Body */}
            <div className="p-6 space-y-6 overflow-y-auto">
              {/* Event Metadata Grid */}
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 bg-slate-950/60 p-4 rounded-xl border border-slate-800 text-xs">
                <div>
                  <span className="text-slate-500 block text-[11px] uppercase tracking-wider font-semibold">
                    Payment ID
                  </span>
                  <span className="text-slate-200 font-mono mt-1 inline-block truncate max-w-full">
                    {selectedEvent.paymentId ? truncateId(selectedEvent.paymentId, 8) : 'None (Poison Pill)'}
                  </span>
                </div>

                <div>
                  <span className="text-slate-500 block text-[11px] uppercase tracking-wider font-semibold">
                    Topic / Partition
                  </span>
                  <span className="text-slate-200 font-mono mt-1 inline-block truncate max-w-full">
                    {selectedEvent.originalTopic} (P{selectedEvent.originalPartition} @ {selectedEvent.originalOffset})
                  </span>
                </div>

                <div>
                  <span className="text-slate-500 block text-[11px] uppercase tracking-wider font-semibold">
                    Retry Count
                  </span>
                  <span className="text-rose-400 font-mono mt-1 inline-block font-bold">
                    {selectedEvent.retryCount} retries
                  </span>
                </div>

                <div>
                  <span className="text-slate-500 block text-[11px] uppercase tracking-wider font-semibold">
                    Intercepted At
                  </span>
                  <span className="text-slate-300 font-sans mt-1 inline-block">
                    {formatDate(selectedEvent.createdAt)}
                  </span>
                </div>
              </div>

              {/* Exception & Failure Callout */}
              <div className="p-4 rounded-xl bg-rose-950/30 border border-rose-500/30 text-rose-200 text-xs space-y-2">
                <div className="flex items-center gap-2 font-semibold text-rose-400">
                  <AlertTriangle className="w-4 h-4" />
                  <span>Exception Details &amp; Root Cause</span>
                </div>
                <div className="font-mono text-[11px] space-y-1">
                  <div>
                    <span className="text-slate-400">Class: </span>
                    <span className="text-rose-300 font-semibold">{selectedEvent.exceptionClass}</span>
                  </div>
                  {selectedEvent.failureMessage && (
                    <div>
                      <span className="text-slate-400">Message: </span>
                      <span className="text-rose-200">{selectedEvent.failureMessage}</span>
                    </div>
                  )}
                </div>
              </div>

              {/* Payload Section */}
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <Code2 className="w-4 h-4 text-rose-400" />
                    <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-300">
                      Serialized Payload (Poison Pill / Exhausted Event JSON)
                    </h4>
                  </div>
                  <CopyButton text={selectedEvent.payload} />
                </div>
                <pre className="bg-slate-950 p-4 rounded-xl font-mono text-xs text-rose-300 overflow-x-auto border border-slate-800 leading-relaxed max-h-60">
                  {parseJson(selectedEvent.payload)}
                </pre>
              </div>

              {/* Transport Headers Section */}
              {selectedEvent.headers && Object.keys(selectedEvent.headers).length > 0 && (
                <div className="space-y-2">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <Info className="w-4 h-4 text-sky-400" />
                      <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-300">
                        Kafka Headers &amp; Retry Metadata
                      </h4>
                    </div>
                    <CopyButton text={JSON.stringify(selectedEvent.headers, null, 2)} />
                  </div>
                  <pre className="bg-slate-950 p-4 rounded-xl font-mono text-xs text-sky-300 overflow-x-auto border border-slate-800 max-h-48">
                    {JSON.stringify(selectedEvent.headers, null, 2)}
                  </pre>
                </div>
              )}
            </div>

            {/* Modal Footer */}
            <div className="px-6 py-3.5 bg-slate-900/90 border-t border-slate-800 flex justify-between items-center">
              {selectedEvent.paymentId ? (
                <Link to={`/payments/${selectedEvent.paymentId}`}>
                  <Button variant="outline" size="sm" className="text-xs gap-1.5">
                    <span>Inspect Payment Entity</span>
                    <ArrowRight className="w-3.5 h-3.5 text-slate-400" />
                  </Button>
                </Link>
              ) : (
                <span className="text-[11px] text-slate-500 font-mono italic">
                  Non-deserializable poison pill (no payment entity)
                </span>
              )}
              <div className="ml-auto">
                <Button variant="secondary" size="sm" onClick={() => setSelectedEvent(null)} className="text-xs">
                  Close
                </Button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
