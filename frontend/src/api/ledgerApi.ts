import { apiRequest } from './fetchClient';
import { Journal, MerchantBalance } from '../models/ledger';
import { PageResponse } from '../models/payment';

export interface ListJournalsParams {
  page?: number;
  size?: number;
  sortBy?: string;
  direction?: 'asc' | 'desc' | string;
}

export const ledgerApi = {
  /**
   * Fetch current balance projection for the active merchant
   */
  async getMerchantBalance(): Promise<MerchantBalance> {
    return apiRequest<MerchantBalance>('/v1/ledger/balance', {
      method: 'GET',
    });
  },

  /**
   * List paginated double-entry financial journals
   */
  async listJournals(params: ListJournalsParams = {}): Promise<PageResponse<Journal>> {
    const query = new URLSearchParams();
    if (params.page !== undefined) query.set('page', params.page.toString());
    if (params.size !== undefined) query.set('size', params.size.toString());
    if (params.sortBy) query.set('sortBy', params.sortBy);
    if (params.direction) query.set('direction', params.direction);

    const queryString = query.toString();
    const endpoint = `/v1/ledger/journals${queryString ? `?${queryString}` : ''}`;
    return apiRequest<PageResponse<Journal>>(endpoint, {
      method: 'GET',
    });
  },

  /**
   * Fetch a single journal by ID with all immutable ledger entries
   */
  async getJournal(id: string): Promise<Journal> {
    return apiRequest<Journal>(`/v1/ledger/journals/${id}`, {
      method: 'GET',
    });
  },
};
