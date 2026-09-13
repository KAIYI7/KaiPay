import { apiRequest } from './fetchClient';
import { DeadLetterEvent } from '../models/dlt';
import { PageResponse } from '../models/payment';

export interface ListDeadLetterEventsParams {
  page?: number;
  size?: number;
  paymentId?: string;
}

export const dltApi = {
  /**
   * List paginated dead letter events.
   * Calls GET /v1/events/dlt
   */
  async listDeadLetterEvents(
    params: ListDeadLetterEventsParams = {}
  ): Promise<PageResponse<DeadLetterEvent>> {
    const query = new URLSearchParams();
    if (params.page !== undefined) query.set('page', params.page.toString());
    if (params.size !== undefined) query.set('size', params.size.toString());
    if (params.paymentId) query.set('paymentId', params.paymentId);

    const queryString = query.toString();
    const endpoint = `/v1/events/dlt${queryString ? `?${queryString}` : ''}`;
    return apiRequest<PageResponse<DeadLetterEvent>>(endpoint, { method: 'GET' });
  },
};
