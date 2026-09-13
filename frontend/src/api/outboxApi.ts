import { apiRequest } from './fetchClient';
import { OutboxEvent, OutboxEventStatus } from '../models/outbox';
import { PageResponse } from '../models/payment';

export interface ListOutboxEventsParams {
  page?: number;
  size?: number;
  status?: OutboxEventStatus | '';
}

export const outboxApi = {
  /**
   * List paginated transactional outbox events from the outbox buffer table.
   * Calls GET /v1/events/outbox
   */
  async listOutboxEvents(params: ListOutboxEventsParams = {}): Promise<PageResponse<OutboxEvent>> {
    const query = new URLSearchParams();
    if (params.page !== undefined) query.set('page', params.page.toString());
    if (params.size !== undefined) query.set('size', params.size.toString());
    if (params.status) query.set('status', params.status);

    const queryString = query.toString();
    const endpoint = `/v1/events/outbox${queryString ? `?${queryString}` : ''}`;
    return apiRequest<PageResponse<OutboxEvent>>(endpoint, { method: 'GET' });
  },
};
