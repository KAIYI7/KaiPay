/**
 * amountCents retains its API name; for MYR it is integer sen (100 sen = RM1).
 * Explicit historical currency codes are preserved for display.
 */
export function formatCentsToCurrency(amountCents: number, currency: string = 'MYR'): string {
  const majorUnits = amountCents / 100;
  return new Intl.NumberFormat('en-MY', {
    style: 'currency',
    currency: currency.toUpperCase(),
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(majorUnits);
}

/** Parse MYR form input without rounding away fractional sen or unsafe integers. */
export function parseMyrToSen(value: string): number | null {
  if (!/^\d+(?:\.\d{1,2})?$/.test(value)) return null;
  const [ringgit, fraction = ''] = value.split('.');
  const sen = Number(ringgit) * 100 + Number(fraction.padEnd(2, '0'));
  return Number.isSafeInteger(sen) && sen > 0 ? sen : null;
}

/**
 * Formats ISO date string into readable date-time.
 */
export function formatDate(isoString: string): string {
  if (!isoString) return '—';
  try {
    const date = new Date(isoString);
    return new Intl.DateTimeFormat('en-US', {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    }).format(date);
  } catch {
    return isoString;
  }
}

/**
 * Generates a random UUID-like idempotency key for testing.
 */
export function generateIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return `key-${crypto.randomUUID()}`;
  }
  return `key-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`;
}

/**
 * Truncates long UUIDs for compact table display.
 */
export function truncateId(id: string, chars: number = 8): string {
  if (!id) return '';
  if (id.length <= chars) return id;
  return `${id.substring(0, chars)}...`;
}
