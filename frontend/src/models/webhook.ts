export type WebhookState = 'PENDING' | 'IN_FLIGHT' | 'DELIVERED' | 'TERMINAL' | 'EXHAUSTED' | 'CANCELLED';
export interface WebhookEndpoint { enabled: boolean; endpointId: string | null; keyId: string | null }
export interface WebhookDelivery {
  id: string; eventId: string; type: string; version: number; paymentId: string; refundId: string | null;
  state: WebhookState; attempts: number; nextAttemptAt: string | null; occurredAt: string; createdAt: string;
  expiresAt: string; deliveredAt: string | null; lastResult: string | null; endpointId: string; keyId: string;
}
export interface WebhookAttempt {
  ordinal: number; startedAt: string; completedAt: string | null; deadline: string; result: string;
  httpStatus: number | null; durationMs: number | null; keyId: string;
}
export interface WebhookDetail { delivery: WebhookDelivery; attempts: WebhookAttempt[] }
