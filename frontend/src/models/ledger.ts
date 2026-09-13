export type AccountType = 'ASSET' | 'LIABILITY' | 'EQUITY' | 'REVENUE' | 'EXPENSE';

export type EntryType = 'DEBIT' | 'CREDIT';

export type JournalSourceType = 'PAYMENT_CAPTURE' | 'PAYMENT_REFUND' | 'SETTLEMENT';

export interface LedgerEntry {
  id: string;
  accountNumber: string;
  accountName: string;
  accountType: AccountType;
  entryType: EntryType;
  amountCents: number;
  currency: string;
}

export interface Journal {
  id: string;
  journalNumber: string;
  sourceType: JournalSourceType;
  sourceId: string;
  eventId?: string | null;
  description: string;
  postedAt: string;
  totalDebitCents: number;
  totalCreditCents: number;
  entries: LedgerEntry[];
}

export interface MerchantBalance {
  merchantId: string;
  availableBalanceCents: number;
  pendingSettlementCents: number;
  totalVolumeCents: number;
  totalFeesCents: number;
  totalRefundsCents: number;
  currency: string;
}
