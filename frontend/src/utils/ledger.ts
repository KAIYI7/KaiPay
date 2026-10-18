import { Journal } from '../models/ledger';
import { formatCentsToCurrency } from './formatters';

/** Historical journals derive their unit from their entries, never a MYR default. */
export function journalCurrency(journal: Journal): string | undefined {
  const currencies = new Set(journal.entries?.map((entry) => entry.currency));
  if (currencies.size !== 1) return undefined;
  return [...currencies][0] || undefined;
}

export function isJournalBalanced(journal: Journal): boolean {
  return !!journalCurrency(journal) && isFiniteTotal(journal.totalDebitCents) &&
    isFiniteTotal(journal.totalCreditCents) && journal.totalDebitCents === journal.totalCreditCents;
}

function isFiniteTotal(value: number | null | undefined): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

export function formatJournalTotal(journal: Journal, amountCents: number | null | undefined): string {
  const currency = journalCurrency(journal);
  return currency && isFiniteTotal(amountCents) ? formatCentsToCurrency(amountCents, currency) : 'Unavailable: incomplete or inconsistent journal';
}
