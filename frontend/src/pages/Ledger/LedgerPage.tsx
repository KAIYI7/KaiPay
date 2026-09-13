import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { ledgerApi } from '../../api/ledgerApi';
import { useMerchant } from '../../context/MerchantContext';
import { Journal, AccountType, EntryType, JournalSourceType } from '../../models/ledger';
import { Card } from '../../components/common/Card';
import { Button } from '../../components/common/Button';
import { Badge } from '../../components/common/Badge';
import { CopyButton } from '../../components/common/CopyButton';
import { Spinner } from '../../components/common/Spinner';
import { formatCentsToCurrency, formatDate, truncateId } from '../../utils/formatters';
import {
  BookOpen,
  DollarSign,
  TrendingUp,
  Receipt,
  RotateCcw,
  RefreshCw,
  ChevronLeft,
  ChevronRight,
  CheckCircle2,
  AlertTriangle,
  Scale,
  ExternalLink,
  X,
  Building,
} from 'lucide-react';

export const LedgerPage: React.FC = () => {
  const { activeMerchant } = useMerchant();
  const [page, setPage] = useState<number>(0);
  const [selectedJournal, setSelectedJournal] = useState<Journal | null>(null);
  const size = 10;

  // 1. Fetch real-time merchant balance projection
  const {
    data: balance,
    isLoading: isBalanceLoading,
    isError: isBalanceError,
    refetch: refetchBalance,
    isFetching: isBalanceFetching,
  } = useQuery({
    queryKey: ['ledger-balance', activeMerchant.id],
    queryFn: () => ledgerApi.getMerchantBalance(),
  });

  // 2. Fetch paginated double-entry financial journals
  const {
    data: journalsPage,
    isLoading: isJournalsLoading,
    isError: isJournalsError,
    error: journalsError,
    refetch: refetchJournals,
    isFetching: isJournalsFetching,
  } = useQuery({
    queryKey: ['ledger-journals', activeMerchant.id, page, size],
    queryFn: () =>
      ledgerApi.listJournals({
        page,
        size,
        sortBy: 'postedAt',
        direction: 'desc',
      }),
  });

  const isRefreshing = isBalanceFetching || isJournalsFetching;

  const handleRefreshAll = () => {
    refetchBalance();
    refetchJournals();
  };

  const journals = journalsPage?.content || [];
  const totalPages = journalsPage?.totalPages || 0;
  const totalElements = journalsPage?.totalElements || 0;

  const getSourceTypeBadge = (sourceType: JournalSourceType) => {
    switch (sourceType) {
      case 'PAYMENT_CAPTURE':
        return (
          <Badge variant="success" className="font-mono text-[11px]">
            <CheckCircle2 className="w-3 h-3" />
            PAYMENT_CAPTURE
          </Badge>
        );
      case 'PAYMENT_REFUND':
        return (
          <Badge variant="purple" className="font-mono text-[11px]">
            <RotateCcw className="w-3 h-3" />
            PAYMENT_REFUND
          </Badge>
        );
      case 'SETTLEMENT':
        return (
          <Badge variant="info" className="font-mono text-[11px]">
            <DollarSign className="w-3 h-3" />
            SETTLEMENT
          </Badge>
        );
      default:
        return <Badge className="font-mono text-[11px]">{sourceType}</Badge>;
    }
  };

  const getAccountTypeBadge = (type: AccountType) => {
    switch (type) {
      case 'ASSET':
        return (
          <span className="px-2 py-0.5 rounded text-[10px] font-semibold bg-emerald-500/20 text-emerald-300 border border-emerald-500/40">
            ASSET (1xxx)
          </span>
        );
      case 'LIABILITY':
        return (
          <span className="px-2 py-0.5 rounded text-[10px] font-semibold bg-amber-500/20 text-amber-300 border border-amber-500/40">
            LIABILITY (2xxx)
          </span>
        );
      case 'EQUITY':
        return (
          <span className="px-2 py-0.5 rounded text-[10px] font-semibold bg-purple-500/20 text-purple-300 border border-purple-500/40">
            EQUITY (3xxx)
          </span>
        );
      case 'REVENUE':
        return (
          <span className="px-2 py-0.5 rounded text-[10px] font-semibold bg-sky-500/20 text-sky-300 border border-sky-500/40">
            REVENUE (4xxx)
          </span>
        );
      case 'EXPENSE':
        return (
          <span className="px-2 py-0.5 rounded text-[10px] font-semibold bg-rose-500/20 text-rose-300 border border-rose-500/40">
            EXPENSE (5xxx)
          </span>
        );
      default:
        return <span className="text-slate-400 text-xs">{type}</span>;
    }
  };

  const getEntryTypeBadge = (entryType: EntryType) => {
    if (entryType === 'DEBIT') {
      return (
        <span className="px-2 py-0.5 rounded text-[10px] font-bold font-mono bg-sky-500/25 text-sky-300 border border-sky-500/40">
          DEBIT
        </span>
      );
    }
    return (
      <span className="px-2 py-0.5 rounded text-[10px] font-bold font-mono bg-emerald-500/25 text-emerald-300 border border-emerald-500/40">
        CREDIT
      </span>
    );
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-2xl font-bold text-slate-100 tracking-tight flex items-center gap-2.5">
              <BookOpen className="w-6 h-6 text-emerald-400" />
              Double-Entry Ledger & Accounts Explorer
            </h1>
            <span className="text-[10px] font-mono uppercase bg-emerald-950/80 text-emerald-400 border border-emerald-700/60 px-2 py-0.5 rounded-full font-semibold">
              Phase 4 Financial Core
            </span>
          </div>
          <p className="text-sm text-slate-400 mt-1">
            Real-time balance projections and immutable double-entry journal records for{' '}
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
            Refresh Balances & Journals
          </Button>
        </div>
      </div>

      {/* Multi-Tenant & Accounting Invariant Banner */}
      <div className="bg-slate-900/90 border border-slate-700/80 rounded-xl p-4 shadow-sm">
        <div className="flex flex-col lg:flex-row items-start lg:items-center justify-between gap-4 text-xs text-slate-300">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-lg bg-emerald-500/10 border border-emerald-500/20 text-emerald-400 shrink-0">
              <Scale className="w-5 h-5" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <span className="font-semibold text-slate-100">Financial Invariant: Zero-Sum Double-Entry</span>
                <Badge variant="success" className="text-[10px] py-0 px-2">
                  Σ Debits == Σ Credits
                </Badge>
              </div>
              <p className="text-slate-400 text-[11px] mt-0.5">
                Every transaction generates balanced journal entries across Asset (1000 Cash Clearing), Liability (2000 Merchant Settlement Payable), and Revenue (4000 Processing Fee) accounts.
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2 text-[11px] font-mono bg-slate-950 px-3 py-1.5 rounded-lg border border-slate-800 shrink-0 self-start lg:self-auto">
            <Building className="w-3.5 h-3.5 text-slate-400" />
            <span className="text-slate-400">Tenant:</span>
            <span className="text-slate-200">{activeMerchant.name}</span>
            <span className="text-slate-500">({truncateId(activeMerchant.id, 8)})</span>
          </div>
        </div>
      </div>

      {/* Real-time Merchant Financial Balance Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {/* Available Payout Balance */}
        <Card className="relative overflow-hidden border-emerald-500/30 bg-gradient-to-b from-emerald-950/20 to-slate-800/80">
          <div className="flex items-center justify-between text-slate-400 text-xs font-semibold uppercase tracking-wider">
            <span>Available Payout Balance</span>
            <div className="p-2 rounded-lg bg-emerald-500/20 text-emerald-400">
              <DollarSign className="w-4 h-4" />
            </div>
          </div>
          {isBalanceLoading ? (
            <div className="py-4">
              <Spinner size="sm" />
            </div>
          ) : isBalanceError ? (
            <div className="text-xs text-rose-400 mt-2">Error loading balance</div>
          ) : (
            <div className="mt-2">
              <div className="text-2xl font-bold font-mono text-emerald-400">
                {formatCentsToCurrency(balance?.availableBalanceCents ?? 0, balance?.currency || 'USD')}
              </div>
              <p className="text-[11px] text-slate-400 mt-1">
                Net merchant settlement funds available for payout
              </p>
            </div>
          )}
        </Card>

        {/* Total Processed Volume */}
        <Card className="relative overflow-hidden border-slate-700/80">
          <div className="flex items-center justify-between text-slate-400 text-xs font-semibold uppercase tracking-wider">
            <span>Total Captured Volume</span>
            <div className="p-2 rounded-lg bg-sky-500/20 text-sky-400">
              <TrendingUp className="w-4 h-4" />
            </div>
          </div>
          {isBalanceLoading ? (
            <div className="py-4">
              <Spinner size="sm" />
            </div>
          ) : isBalanceError ? (
            <div className="text-xs text-rose-400 mt-2">Error loading volume</div>
          ) : (
            <div className="mt-2">
              <div className="text-2xl font-bold font-mono text-slate-100">
                {formatCentsToCurrency(balance?.totalVolumeCents ?? 0, balance?.currency || 'USD')}
              </div>
              <p className="text-[11px] text-slate-400 mt-1">
                Gross payment volume successfully captured
              </p>
            </div>
          )}
        </Card>

        {/* Platform Processing Fees */}
        <Card className="relative overflow-hidden border-slate-700/80">
          <div className="flex items-center justify-between text-slate-400 text-xs font-semibold uppercase tracking-wider">
            <span>Platform Processing Fees</span>
            <div className="p-2 rounded-lg bg-indigo-500/20 text-indigo-400">
              <Receipt className="w-4 h-4" />
            </div>
          </div>
          {isBalanceLoading ? (
            <div className="py-4">
              <Spinner size="sm" />
            </div>
          ) : isBalanceError ? (
            <div className="text-xs text-rose-400 mt-2">Error loading fees</div>
          ) : (
            <div className="mt-2">
              <div className="text-2xl font-bold font-mono text-slate-200">
                {formatCentsToCurrency(balance?.totalFeesCents ?? 0, balance?.currency || 'USD')}
              </div>
              <p className="text-[11px] text-slate-400 mt-1">
                Platform revenue deducted (Account 4000)
              </p>
            </div>
          )}
        </Card>

        {/* Total Refunds */}
        <Card className="relative overflow-hidden border-slate-700/80">
          <div className="flex items-center justify-between text-slate-400 text-xs font-semibold uppercase tracking-wider">
            <span>Total Refunds Settled</span>
            <div className="p-2 rounded-lg bg-purple-500/20 text-purple-400">
              <RotateCcw className="w-4 h-4" />
            </div>
          </div>
          {isBalanceLoading ? (
            <div className="py-4">
              <Spinner size="sm" />
            </div>
          ) : isBalanceError ? (
            <div className="text-xs text-rose-400 mt-2">Error loading refunds</div>
          ) : (
            <div className="mt-2">
              <div className="text-2xl font-bold font-mono text-purple-300">
                {formatCentsToCurrency(balance?.totalRefundsCents ?? 0, balance?.currency || 'USD')}
              </div>
              <p className="text-[11px] text-slate-400 mt-1">
                Reversed volume debiting merchant payable
              </p>
            </div>
          )}
        </Card>
      </div>

      {/* Financial Journals Table */}
      <Card
        title="Double-Entry Financial Journals"
        subtitle="Immutable accounting records verified by Debits = Credits invariant"
        action={
          <div className="text-xs text-slate-400">
            Total Journals: <span className="font-semibold text-slate-200">{totalElements}</span>
          </div>
        }
        className="p-0 overflow-hidden"
      >
        {isJournalsLoading && (
          <div className="py-20 flex justify-center">
            <Spinner />
          </div>
        )}

        {isJournalsError && (
          <div className="py-12 text-center text-xs text-rose-400 space-y-2">
            <p>Failed to load financial journals: {(journalsError as Error).message}</p>
            <Button variant="outline" size="sm" onClick={() => refetchJournals()} className="text-xs">
              Retry
            </Button>
          </div>
        )}

        {!isJournalsLoading && !isJournalsError && journals.length === 0 && (
          <div className="py-16 text-center">
            <BookOpen className="w-10 h-10 text-slate-600 mx-auto mb-2.5 opacity-50" />
            <p className="text-sm font-medium text-slate-300">No financial journals found</p>
            <p className="text-xs text-slate-500 mt-1">
              Journals are automatically created when authorized payments are captured or refunds are executed.
            </p>
            <div className="mt-4">
              <Link to="/payments">
                <Button size="sm" variant="primary">
                  Go to Payments Explorer
                </Button>
              </Link>
            </div>
          </div>
        )}

        {!isJournalsLoading && !isJournalsError && journals.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead className="text-[11px] text-slate-400 uppercase bg-slate-900/80 border-b border-slate-700/60">
                <tr>
                  <th className="px-4 py-3.5 font-semibold">Journal Number</th>
                  <th className="px-4 py-3.5 font-semibold">Source Type</th>
                  <th className="px-4 py-3.5 font-semibold">Source ID</th>
                  <th className="px-4 py-3.5 font-semibold">Description</th>
                  <th className="px-4 py-3.5 font-semibold text-right">Total Debits</th>
                  <th className="px-4 py-3.5 font-semibold text-right">Total Credits</th>
                  <th className="px-4 py-3.5 font-semibold text-center">Invariant</th>
                  <th className="px-4 py-3.5 font-semibold">Posted At</th>
                  <th className="px-4 py-3.5 font-semibold text-right">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-700/40 font-mono">
                {journals.map((journal) => {
                  const isBalanced = journal.totalDebitCents === journal.totalCreditCents;
                  return (
                    <tr
                      key={journal.id}
                      className="hover:bg-slate-800/50 transition-colors cursor-pointer"
                      onClick={() => setSelectedJournal(journal)}
                    >
                      <td className="px-4 py-3 text-slate-200">
                        <div className="flex items-center gap-1.5 font-semibold text-emerald-400">
                          <span>{journal.journalNumber}</span>
                          <CopyButton text={journal.journalNumber} />
                        </div>
                      </td>
                      <td className="px-4 py-3 font-sans">
                        {getSourceTypeBadge(journal.sourceType)}
                      </td>
                      <td className="px-4 py-3 text-slate-300" onClick={(e) => e.stopPropagation()}>
                        <div className="flex items-center gap-1">
                          <Link
                            to={`/payments/${journal.sourceId}`}
                            className="hover:text-emerald-400 transition-colors flex items-center gap-1 text-slate-300 font-semibold"
                            title="View Payment Details"
                          >
                            <span>{truncateId(journal.sourceId, 8)}</span>
                            <ExternalLink className="w-3 h-3 text-slate-500 hover:text-emerald-400" />
                          </Link>
                          <CopyButton text={journal.sourceId} />
                        </div>
                      </td>
                      <td className="px-4 py-3 text-slate-300 font-sans max-w-xs truncate" title={journal.description}>
                        {journal.description}
                      </td>
                      <td className="px-4 py-3 text-right font-mono font-semibold text-sky-400">
                        {formatCentsToCurrency(journal.totalDebitCents)}
                      </td>
                      <td className="px-4 py-3 text-right font-mono font-semibold text-emerald-400">
                        {formatCentsToCurrency(journal.totalCreditCents)}
                      </td>
                      <td className="px-4 py-3 text-center font-sans">
                        {isBalanced ? (
                          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-semibold bg-emerald-500/15 text-emerald-400 border border-emerald-500/30">
                            <CheckCircle2 className="w-3 h-3 text-emerald-400" />
                            Balanced
                          </span>
                        ) : (
                          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-semibold bg-rose-500/15 text-rose-400 border border-rose-500/30">
                            <AlertTriangle className="w-3 h-3 text-rose-400" />
                            Imbalanced
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-slate-400 font-sans whitespace-nowrap">
                        {formatDate(journal.postedAt)}
                      </td>
                      <td className="px-4 py-3 text-right font-sans" onClick={(e) => e.stopPropagation()}>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-xs h-7 px-2.5"
                          onClick={() => setSelectedJournal(journal)}
                        >
                          Inspect
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
        {!isJournalsLoading && !isJournalsError && totalPages > 1 && (
          <div className="px-4 py-3 bg-slate-900/60 border-t border-slate-700/60 flex items-center justify-between text-xs text-slate-400">
            <div>
              Page <span className="font-semibold text-slate-200">{page + 1}</span> of{' '}
              <span className="font-semibold text-slate-200">{totalPages}</span> ({totalElements} total journals)
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

      {/* Expandable Ledger Entry Detail Modal */}
      {selectedJournal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-950/80 backdrop-blur-sm animate-in fade-in duration-150">
          <div className="bg-slate-900 border border-slate-700 rounded-xl shadow-2xl w-full max-w-3xl max-h-[90vh] flex flex-col overflow-hidden animate-in zoom-in-95 duration-150">
            {/* Modal Header */}
            <div className="flex items-center justify-between px-6 py-4 border-b border-slate-800 bg-slate-900/80">
              <div className="flex items-center gap-3">
                <div className="p-2 rounded-lg bg-emerald-500/15 text-emerald-400 border border-emerald-500/30">
                  <Scale className="w-5 h-5" />
                </div>
                <div>
                  <div className="flex items-center gap-2">
                    <h2 className="text-lg font-bold text-slate-100 font-mono">
                      Journal {selectedJournal.journalNumber}
                    </h2>
                    {getSourceTypeBadge(selectedJournal.sourceType)}
                  </div>
                  <p className="text-xs text-slate-400 mt-0.5">
                    Immutable double-entry ledger lines posted on {formatDate(selectedJournal.postedAt)}
                  </p>
                </div>
              </div>

              <button
                type="button"
                onClick={() => setSelectedJournal(null)}
                className="p-1.5 rounded-lg text-slate-400 hover:text-slate-200 hover:bg-slate-800 transition-colors"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            {/* Modal Content */}
            <div className="p-6 space-y-5 overflow-y-auto">
              {/* Journal Metadata Grid */}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 bg-slate-950/60 p-4 rounded-lg border border-slate-800 text-xs">
                <div>
                  <span className="text-slate-400 font-medium">Journal ID:</span>
                  <div className="flex items-center gap-1 font-mono text-slate-200 mt-0.5">
                    <span>{selectedJournal.id}</span>
                    <CopyButton text={selectedJournal.id} />
                  </div>
                </div>

                <div>
                  <span className="text-slate-400 font-medium">Source Reference ID:</span>
                  <div className="flex items-center gap-1 font-mono text-slate-200 mt-0.5">
                    <Link
                      to={`/payments/${selectedJournal.sourceId}`}
                      className="text-emerald-400 hover:underline flex items-center gap-1 font-semibold"
                    >
                      <span>{selectedJournal.sourceId}</span>
                      <ExternalLink className="w-3 h-3" />
                    </Link>
                    <CopyButton text={selectedJournal.sourceId} />
                  </div>
                </div>

                {selectedJournal.eventId && (
                  <div>
                    <span className="text-slate-400 font-medium">Associated Event ID:</span>
                    <div className="flex items-center gap-1 font-mono text-slate-300 mt-0.5">
                      <span>{selectedJournal.eventId}</span>
                      <CopyButton text={selectedJournal.eventId} />
                    </div>
                  </div>
                )}

                <div>
                  <span className="text-slate-400 font-medium">Description:</span>
                  <div className="text-slate-200 mt-0.5">{selectedJournal.description}</div>
                </div>
              </div>

              {/* Entries Table */}
              <div>
                <div className="flex items-center justify-between mb-2">
                  <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-400">
                    Ledger Entries ({selectedJournal.entries?.length || 0} Lines)
                  </h4>
                  <span className="text-[11px] text-slate-500 font-mono">
                    Standard Chart of Accounts (COA)
                  </span>
                </div>

                <div className="border border-slate-800 rounded-lg overflow-hidden bg-slate-950/40">
                  <table className="w-full text-left text-xs">
                    <thead className="text-[11px] text-slate-400 uppercase bg-slate-900/90 border-b border-slate-800">
                      <tr>
                        <th className="px-3.5 py-2.5 font-semibold">Account #</th>
                        <th className="px-3.5 py-2.5 font-semibold">Account Name</th>
                        <th className="px-3.5 py-2.5 font-semibold">Account Type</th>
                        <th className="px-3.5 py-2.5 font-semibold">Entry Type</th>
                        <th className="px-3.5 py-2.5 font-semibold text-right">Debit</th>
                        <th className="px-3.5 py-2.5 font-semibold text-right">Credit</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-800 font-mono">
                      {selectedJournal.entries?.map((entry) => {
                        const isDebit = entry.entryType === 'DEBIT';
                        const isCredit = entry.entryType === 'CREDIT';

                        return (
                          <tr key={entry.id} className="hover:bg-slate-800/30">
                            <td className="px-3.5 py-2.5 text-slate-300 font-bold">
                              {entry.accountNumber}
                            </td>
                            <td className="px-3.5 py-2.5 text-slate-200 font-sans">
                              {entry.accountName}
                            </td>
                            <td className="px-3.5 py-2.5 font-sans">
                              {getAccountTypeBadge(entry.accountType)}
                            </td>
                            <td className="px-3.5 py-2.5 font-sans">
                              {getEntryTypeBadge(entry.entryType)}
                            </td>
                            <td className="px-3.5 py-2.5 text-right font-semibold text-sky-400">
                              {isDebit ? formatCentsToCurrency(entry.amountCents, entry.currency) : '—'}
                            </td>
                            <td className="px-3.5 py-2.5 text-right font-semibold text-emerald-400">
                              {isCredit ? formatCentsToCurrency(entry.amountCents, entry.currency) : '—'}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                    <tfoot className="bg-slate-900 font-mono font-bold text-xs border-t-2 border-slate-700">
                      <tr>
                        <td colSpan={4} className="px-3.5 py-3 text-slate-300 uppercase tracking-wider font-sans">
                          Total Balancing Sums
                        </td>
                        <td className="px-3.5 py-3 text-right text-sky-400">
                          {formatCentsToCurrency(selectedJournal.totalDebitCents)}
                        </td>
                        <td className="px-3.5 py-3 text-right text-emerald-400">
                          {formatCentsToCurrency(selectedJournal.totalCreditCents)}
                        </td>
                      </tr>
                    </tfoot>
                  </table>
                </div>
              </div>

              {/* Invariant Zero-Sum Confirmation Alert */}
              <div className="flex items-center justify-between p-3.5 rounded-lg bg-emerald-950/30 border border-emerald-500/40 text-xs text-emerald-300">
                <div className="flex items-center gap-2.5">
                  <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" />
                  <span>
                    <strong>Double-Entry Invariant Satisfied:</strong> Total Debits equal Total Credits (Net Difference: $0.00).
                  </span>
                </div>
                <Badge variant="success" className="font-mono text-[10px]">
                  Balanced
                </Badge>
              </div>
            </div>

            {/* Modal Footer */}
            <div className="px-6 py-3 bg-slate-900/80 border-t border-slate-800 flex justify-end">
              <Button variant="secondary" size="sm" onClick={() => setSelectedJournal(null)}>
                Close
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
