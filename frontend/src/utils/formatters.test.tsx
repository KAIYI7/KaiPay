import { describe, expect, it } from 'vitest';
import { formatCentsToCurrency, parseMyrToSen } from './formatters';

describe('MYR minor units', () => {
  it.each([['0.01', 1], ['1', 100], ['10.50', 1050], ['1.01', 101], ['90071992547409.91', Number.MAX_SAFE_INTEGER]])(
    'parses %s exactly as %s sen', (input, expected) => expect(parseMyrToSen(input)).toBe(expected));

  it.each(['', '0', '-1', '0.001', '1.005', '1e2', '12abc', 'Infinity', '90071992547409.92']) (
    'rejects invalid or inexact input %s', (input) => expect(parseMyrToSen(input)).toBeNull());

  it('defaults new balances to MYR and preserves explicit historical USD', () => {
    expect(formatCentsToCurrency(1050)).toBe('RM\u00a010.50');
    expect(formatCentsToCurrency(1050, 'USD')).toContain('US$');
    expect(formatCentsToCurrency(1050, 'USD')).not.toContain('RM');
  });
});
