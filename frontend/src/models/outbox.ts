export type OutboxEventStatus = 'PENDING' | 'PUBLISHED';

export interface OutboxEvent {
  id: string;
  aggregateType: string;
  aggregateId: string;
  eventType: string;
  payload: string;
  headers: Record<string, unknown>;
  status: 'PENDING' | 'PUBLISHED';
  retryCount: number;
  lastError?: string | null;
  createdAt: string;
  publishedAt?: string | null;
}
