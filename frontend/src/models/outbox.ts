export type OutboxEventStatus = 'PENDING' | 'PUBLISHED' | 'QUARANTINED';

export interface OutboxEvent {
  id: string;
  aggregateType: string;
  aggregateId: string;
  eventType: string;
  payload: string;
  headers: Record<string, unknown>;
  status: OutboxEventStatus;
  retryCount: number;
  lastError?: string | null;
  createdAt: string;
  publishedAt?: string | null;
  nextAttemptAt?: string | null;
  lastAttemptAt?: string | null;
  quarantinedAt?: string | null;
}
