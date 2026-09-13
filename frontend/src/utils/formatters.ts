/**
 * Formats minor currency units (cents) into human-readable currency strings.
 * e.g., 1050 cents -> "$10.50"
 */
export function formatCentsToCurrency(amountCents: number, currency: string = 'USD'): string {
  const dollars = amountCents / 100;
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: currency.toUpperCase(),
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(dollars);
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
