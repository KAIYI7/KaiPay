import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { dltApi } from '../../api/dltApi';
import { outboxApi } from '../../api/outboxApi';
import { PageResponse } from '../../models/payment';
import { DltExplorerPage } from './DltExplorerPage';
import { OutboxStreamPage } from '../OutboxStream/OutboxStreamPage';

const clients: QueryClient[] = [];
const privateMarker = 'private-customer-contact-token';
const id = '11111111-1111-1111-1111-111111111111';
const createdAt = '2026-10-18T00:00:00Z';

afterEach(() => {
  cleanup();
  clients.splice(0).forEach((client) => client.clear());
  vi.restoreAllMocks();
});

function page<T>(event: T): PageResponse<T> {
  return { content: [event], totalElements: 1, totalPages: 1, size: 10, number: 0,
    first: true, last: true, empty: false };
}

function mount(element: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  clients.push(client);
  return render(<QueryClientProvider client={client}>
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>{element}</MemoryRouter>
  </QueryClientProvider>);
}

describe('browser diagnostic privacy presentation', () => {
  it('describes omitted DLT evidence while retaining record and correlation metadata', async () => {
    vi.spyOn(dltApi, 'listDeadLetterEvents').mockResolvedValue(page({
      id, originalTopic: 'kaipay.payment.requests-dlt', originalPartition: 2, originalOffset: 42,
      exceptionClass: privateMarker, failureMessage: privateMarker, retryCount: 3,
      payload: JSON.stringify({ unexpected: privateMarker }),
      headers: { 'X-Correlation-Id': 'safe-correlation' }, createdAt,
    }));
    const { container } = mount(<DltExplorerPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Inspect' }));

    expect(screen.getByText('Payload Omitted')).toBeTruthy();
    expect(screen.getByText('Exception Details Omitted')).toBeTruthy();
    expect(screen.getByText('Validated Correlation ID')).toBeTruthy();
    expect(screen.getByText('3 retries')).toBeTruthy();
    expect(container.textContent).toContain('safe-correlation');
    expect(container.textContent).not.toContain(privateMarker);
    expect(container.innerHTML).not.toContain(privateMarker);
    expect(screen.queryByText(/Serialized Payload/)).toBeNull();
  });

  it('describes omitted outbox evidence while retaining quarantine and retry status', async () => {
    vi.spyOn(outboxApi, 'listOutboxEvents').mockResolvedValue(page({
      id, aggregateType: 'PAYMENT', aggregateId: id, eventType: 'PaymentInitiatedEvent',
      status: 'QUARANTINED', retryCount: 2, payload: JSON.stringify({ unexpected: privateMarker }),
      lastError: privateMarker, headers: { 'X-Correlation-Id': 'safe-correlation' }, createdAt,
      lastAttemptAt: createdAt, quarantinedAt: createdAt,
    }));
    const { container } = mount(<OutboxStreamPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Inspect' }));

    expect(screen.getByText('Payload Omitted')).toBeTruthy();
    expect(screen.getByText('Error details are omitted from browser diagnostics.')).toBeTruthy();
    expect(screen.getByText('Publisher Relay Error (Retry #2)')).toBeTruthy();
    expect(screen.getByText('Validated Correlation ID')).toBeTruthy();
    expect(container.textContent).toContain('QUARANTINED');
    expect(container.textContent).toContain('safe-correlation');
    expect(container.textContent).not.toContain(privateMarker);
    expect(container.innerHTML).not.toContain(privateMarker);
    expect(screen.queryByText(/Serialized Event Payload/)).toBeNull();
  });
});
