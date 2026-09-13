import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { outboxApi } from '../../api/outboxApi';
import { OutboxEvent, OutboxEventStatus } from '../../models/outbox';
import { Card } from '../../components/common/Card';
import { Button } from '../../components/common/Button';
import { Select } from '../../components/common/Select';
import { CopyButton } from '../../components/common/CopyButton';
import { Spinner } from '../../components/common/Spinner';
import { formatDate, truncateId } from '../../utils/formatters';
import {
  Layers,
  RefreshCw,
  ChevronLeft,
  ChevronRight,
  Database,
  ArrowRight,
  Radio,
  CheckCircle2,
  Clock,
  AlertTriangle,
  Code2,
  X,
  ExternalLink,
  Info,
} from 'lucide-react';

export const OutboxStreamPage: React.FC = () => {
  const [statusFilter, setStatusFilter] = useState<OutboxEventStatus | ''>('');
  const [page, setPage] = useState<number>(0);
  const size = 10;
  const [selectedEvent, setSelectedEvent] = useState<OutboxEvent | null>(null);

  const {
    data: eventsPage,
    isLoading,
    isError,
    error,
    refetch,
    isFetching,
  } = useQuery({
    queryKey: ['outbox-events', page, size, statusFilter],
    queryFn: () =>
      outboxApi.listOutboxEvents({
        page,
        size,
        status: statusFilter || undefined,
      }),
    refetchInterval: 2000, // 2-second live polling stream
  });

  const events = eventsPage?.content || [];
  const totalPages = eventsPage?.totalPages || 0;
  const totalElements = eventsPage?.totalElements || 0;

  const statusOptions = [
    { value: '', label: 'All Statuses' },
    { value: 'PENDING', label: 'PENDING' },
    { value: 'PUBLISHED', label: 'PUBLISHED' },
  ];

  const handleStatusChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    setStatusFilter(e.target.value as OutboxEventStatus | '');
    setPage(0);
  };

  const parseJson = (str: string) => {
    try {
      return JSON.stringify(JSON.parse(str), null, 2);
    } catch {
      return str;
    }
  };

  const getEventTypeBadgeClass = (eventType: string) => {
    if (eventType.includes('Created')) return 'bg-sky-500/15 text-sky-400 border-sky-500/30';
    if (eventType.includes('Authorized')) return 'bg-indigo-500/15 text-indigo-400 border-indigo-500/30';
    if (eventType.includes('Captured')) return 'bg-emerald-500/15 text-emerald-400 border-emerald-500/30';
    if (eventType.includes('Failed') || eventType.includes('Declined'))
      return 'bg-rose-500/15 text-rose-400 border-rose-500/30';
    return 'bg-purple-500/15 text-purple-400 border-purple-500/30';
  };

  return (
    <div className="space-y-6">
      {/* Top Banner: Internal Platform Operational Tool */}
      <div className="bg-slate-900/90 border border-emerald-500/30 rounded-xl p-5 shadow-lg relative overflow-hidden">
        <div className="absolute top-0 right-0 w-96 h-96 bg-emerald-500/5 rounded-full blur-3xl pointer-events-none -mr-20 -mt-20"></div>

        <div className="flex flex-col lg:flex-row items-start lg:items-center justify-between gap-4 relative z-10">
          <div className="space-y-1.5">
            <div className="flex flex-wrap items-center gap-2">
              <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-md text-[11px] font-bold font-mono uppercase bg-emerald-500/20 text-emerald-400 border border-emerald-500/40">
                <Database className="w-3 h-3" />
                PostgreSQL ➔ Apache Kafka
              </span>
              <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-semibold bg-emerald-950/80 text-emerald-300 border border-emerald-700/50">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-ping"></span>
                Live Stream (2s)
              </span>
            </div>

            <h1 className="text-xl font-bold text-slate-100 tracking-tight">
              Internal Platform Operational Tool: Transactional Outbox Buffer Monitor (PostgreSQL ➔ Apache Kafka)
            </h1>

            <p className="text-xs text-slate-400 max-w-4xl leading-relaxed">
              Guaranteed at-least-once event delivery preventing Dual-Write inconsistencies. Payment state transitions
              and outbox records are committed atomically within PostgreSQL ACID transactions before being relayed to
              Kafka by the asynchronous <code className="text-emerald-400 font-mono">OutboxEventPublisher</code> scheduler.
            </p>
          </div>

          <div className="flex items-center gap-3 shrink-0 self-end lg:self-center">
            <Button
              variant="outline"
              size="sm"
              onClick={() => refetch()}
              disabled={isFetching}
              className="gap-1.5 text-xs font-mono"
            >
              <RefreshCw className={`w-3.5 h-3.5 ${isFetching ? 'animate-spin text-emerald-400' : ''}`} />
              <span>Poll Now</span>
            </Button>
          </div>
        </div>
      </div>

      {/* Filter and Control Bar */}
      <Card className="p-4">
        <div className="flex flex-col sm:flex-row items-center justify-between gap-4">
          <div className="w-full sm:w-64">
            <Select
              label="Filter by Outbox Status"
              options={statusOptions}
              value={statusFilter}
              onChange={handleStatusChange}
            />
          </div>

          <div className="flex items-center gap-4 text-xs text-slate-400 self-end sm:self-center">
            <div className="flex items-center gap-2 font-mono">
              <span className="text-slate-500">Buffer Size:</span>
              <span className="font-bold text-slate-200">{totalElements}</span>
              <span>events</span>
            </div>
            <div className="flex items-center gap-1.5 text-[11px] text-slate-400 bg-slate-900/60 px-2.5 py-1 rounded-md border border-slate-700/50">
              <Radio className="w-3 h-3 text-emerald-400 animate-pulse" />
              <span>Auto-refreshing</span>
            </div>
          </div>
        </div>
      </Card>

      {/* Outbox Events Stream Table */}
      <Card className="p-0 overflow-hidden">
        {isLoading && (
          <div className="py-24 flex flex-col items-center justify-center gap-3">
            <Spinner size="lg" />
            <p className="text-xs text-slate-400 font-mono">Reading PostgreSQL outbox buffer...</p>
          </div>
        )}

        {isError && (
          <div className="py-16 text-center space-y-3 px-4">
            <div className="w-10 h-10 rounded-full bg-rose-500/15 border border-rose-500/30 flex items-center justify-center mx-auto text-rose-400">
              <AlertTriangle className="w-5 h-5" />
            </div>
            <h3 className="text-sm font-semibold text-rose-400">Failed to fetch outbox buffer stream</h3>
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
            <div className="w-12 h-12 rounded-xl bg-slate-800/80 border border-slate-700/60 flex items-center justify-center mx-auto text-slate-400">
              <Layers className="w-6 h-6" />
            </div>
            <p className="text-sm font-semibold text-slate-200">No Outbox Events Found</p>
            <p className="text-xs text-slate-500 max-w-md mx-auto">
              {statusFilter
                ? `No outbox events currently in ${statusFilter} state.`
                : 'Create a transaction in the Testing Sandbox to generate transactional outbox events.'}
            </p>
          </div>
        )}

        {!isLoading && !isError && events.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead className="text-[11px] text-slate-400 uppercase bg-slate-900/80 border-b border-slate-700/60">
                <tr>
                  <th className="px-4 py-3.5 font-semibold">Event ID</th>
                  <th className="px-4 py-3.5 font-semibold">Event Type</th>
                  <th className="px-4 py-3.5 font-semibold">Aggregate</th>
                  <th className="px-4 py-3.5 font-semibold">Status</th>
                  <th className="px-4 py-3.5 font-semibold">Retries</th>
                  <th className="px-4 py-3.5 font-semibold">Created At</th>
                  <th className="px-4 py-3.5 font-semibold">Published At</th>
                  <th className="px-4 py-3.5 font-semibold text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-700/40 font-mono">
                {events.map((event) => {
                  const isPending = event.status === 'PENDING';
                  return (
                    <tr key={event.id} className="hover:bg-slate-800/40 transition-colors">
                      {/* Event ID */}
                      <td className="px-4 py-3 text-slate-200">
                        <div className="flex items-center gap-1.5">
                          <span className="font-semibold text-slate-100">{truncateId(event.id, 8)}</span>
                          <CopyButton text={event.id} />
                        </div>
                      </td>

                      {/* Event Type */}
                      <td className="px-4 py-3 font-sans">
                        <span
                          className={`inline-flex items-center px-2 py-0.5 rounded text-[11px] font-mono font-medium border ${getEventTypeBadgeClass(
                            event.eventType
                          )}`}
                        >
                          {event.eventType}
                        </span>
                      </td>

                      {/* Aggregate */}
                      <td className="px-4 py-3 text-slate-300">
                        <div className="flex items-center gap-1">
                          <span className="text-slate-500 font-sans text-[11px]">{event.aggregateType}:</span>
                          {event.aggregateType.toLowerCase() === 'payment' ? (
                            <Link
                              to={`/payments/${event.aggregateId}`}
                              className="text-emerald-400 hover:underline flex items-center gap-1"
                              title="Inspect Payment Details"
                            >
                              <span>{truncateId(event.aggregateId, 8)}</span>
                              <ExternalLink className="w-3 h-3 opacity-60" />
                            </Link>
                          ) : (
                            <span>{truncateId(event.aggregateId, 8)}</span>
                          )}
                          <CopyButton text={event.aggregateId} />
                        </div>
                      </td>

                      {/* Status */}
                      <td className="px-4 py-3 font-sans">
                        {isPending ? (
                          <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-[11px] font-semibold bg-amber-500/15 text-amber-300 border border-amber-500/30">
                            <span className="w-1.5 h-1.5 rounded-full bg-amber-400 animate-ping"></span>
                            PENDING
                          </span>
                        ) : (
                          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-[11px] font-semibold bg-emerald-500/15 text-emerald-400 border border-emerald-500/30">
                            <CheckCircle2 className="w-3.5 h-3.5" />
                            PUBLISHED
                          </span>
                        )}
                      </td>

                      {/* Retries */}
                      <td className="px-4 py-3 font-sans">
                        {event.retryCount > 0 ? (
                          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px] font-mono font-semibold bg-rose-500/20 text-rose-300 border border-rose-500/40">
                            <AlertTriangle className="w-3 h-3" />
                            {event.retryCount}
                          </span>
                        ) : (
                          <span className="text-slate-500 font-mono">0</span>
                        )}
                      </td>

                      {/* Created At */}
                      <td className="px-4 py-3 text-slate-400 font-sans">{formatDate(event.createdAt)}</td>

                      {/* Published At */}
                      <td className="px-4 py-3 text-slate-400 font-sans">
                        {event.publishedAt ? (
                          <span className="text-slate-300">{formatDate(event.publishedAt)}</span>
                        ) : (
                          <span className="text-amber-400/80 italic text-[11px] flex items-center gap-1">
                            <Clock className="w-3 h-3" />
                            Awaiting Relay
                          </span>
                        )}
                      </td>

                      {/* Actions */}
                      <td className="px-4 py-3 text-right">
                        <Button
                          variant="secondary"
                          size="sm"
                          onClick={() => setSelectedEvent(event)}
                          className="text-xs h-7 px-2.5 font-sans gap-1 bg-slate-800 hover:bg-slate-700 text-slate-200 border border-slate-700"
                        >
                          <Code2 className="w-3.5 h-3.5 text-emerald-400" />
                          <span>Payload</span>
                        </Button>
                      </td>
                    </tr>
                  );
                })}
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

      {/* Event Payload & Metadata Inspection Modal */}
      {selectedEvent && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-950/80 backdrop-blur-sm animate-in fade-in duration-150">
          <div className="bg-slate-900 border border-slate-700 rounded-2xl w-full max-w-3xl max-h-[90vh] flex flex-col shadow-2xl overflow-hidden">
            {/* Modal Header */}
            <div className="px-6 py-4 border-b border-slate-800 flex items-center justify-between bg-slate-900/90">
              <div className="flex items-center gap-3">
                <div className="p-2 rounded-lg bg-emerald-500/15 border border-emerald-500/30 text-emerald-400">
                  <Layers className="w-5 h-5" />
                </div>
                <div>
                  <h3 className="text-base font-bold text-slate-100 flex items-center gap-2">
                    <span>Transactional Outbox Event</span>
                    <span
                      className={`text-xs px-2 py-0.5 rounded font-mono border ${getEventTypeBadgeClass(
                        selectedEvent.eventType
                      )}`}
                    >
                      {selectedEvent.eventType}
                    </span>
                  </h3>
                  <p className="text-xs text-slate-400 font-mono mt-0.5">ID: {selectedEvent.id}</p>
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
                    Status
                  </span>
                  <span
                    className={`font-semibold mt-1 inline-block ${
                      selectedEvent.status === 'PUBLISHED' ? 'text-emerald-400' : 'text-amber-400'
                    }`}
                  >
                    {selectedEvent.status}
                  </span>
                </div>

                <div>
                  <span className="text-slate-500 block text-[11px] uppercase tracking-wider font-semibold">
                    Aggregate
                  </span>
                  <span className="text-slate-200 font-mono mt-1 inline-block truncate max-w-full">
                    {selectedEvent.aggregateType} / {truncateId(selectedEvent.aggregateId, 8)}
                  </span>
                </div>

                <div>
                  <span className="text-slate-500 block text-[11px] uppercase tracking-wider font-semibold">
                    Created At
                  </span>
                  <span className="text-slate-300 font-sans mt-1 inline-block">
                    {formatDate(selectedEvent.createdAt)}
                  </span>
                </div>

                <div>
                  <span className="text-slate-500 block text-[11px] uppercase tracking-wider font-semibold">
                    Published At
                  </span>
                  <span className="text-slate-300 font-sans mt-1 inline-block">
                    {selectedEvent.publishedAt ? formatDate(selectedEvent.publishedAt) : '—'}
                  </span>
                </div>
              </div>

              {/* Error Alert if present */}
              {selectedEvent.lastError && (
                <div className="p-3.5 rounded-xl bg-rose-500/10 border border-rose-500/30 text-rose-300 text-xs space-y-1">
                  <div className="flex items-center gap-1.5 font-semibold text-rose-400">
                    <AlertTriangle className="w-4 h-4" />
                    <span>Publisher Relay Error (Retry #{selectedEvent.retryCount})</span>
                  </div>
                  <pre className="font-mono text-[11px] whitespace-pre-wrap text-rose-200 bg-rose-950/40 p-2.5 rounded border border-rose-800/40">
                    {selectedEvent.lastError}
                  </pre>
                </div>
              )}

              {/* Payload Section */}
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <Code2 className="w-4 h-4 text-emerald-400" />
                    <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-300">
                      Serialized Event Payload (JSON)
                    </h4>
                  </div>
                  <CopyButton text={selectedEvent.payload} />
                </div>
                <pre className="bg-slate-950 p-4 rounded-xl font-mono text-xs text-emerald-300 overflow-x-auto border border-slate-800 leading-relaxed">
                  {parseJson(selectedEvent.payload)}
                </pre>
              </div>

              {/* Headers Section */}
              {selectedEvent.headers && Object.keys(selectedEvent.headers).length > 0 && (
                <div className="space-y-2">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <Info className="w-4 h-4 text-sky-400" />
                      <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-300">
                        Event Transport Headers (Map)
                      </h4>
                    </div>
                    <CopyButton text={JSON.stringify(selectedEvent.headers, null, 2)} />
                  </div>
                  <pre className="bg-slate-950 p-4 rounded-xl font-mono text-xs text-sky-300 overflow-x-auto border border-slate-800">
                    {JSON.stringify(selectedEvent.headers, null, 2)}
                  </pre>
                </div>
              )}
            </div>

            {/* Modal Footer */}
            <div className="px-6 py-3.5 bg-slate-900/90 border-t border-slate-800 flex justify-between items-center">
              {selectedEvent.aggregateType.toLowerCase() === 'payment' && (
                <Link to={`/payments/${selectedEvent.aggregateId}`}>
                  <Button variant="outline" size="sm" className="text-xs gap-1.5">
                    <span>Inspect Payment Entity</span>
                    <ArrowRight className="w-3.5 h-3.5 text-slate-400" />
                  </Button>
                </Link>
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
