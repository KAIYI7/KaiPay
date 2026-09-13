import { ApiResponse, ErrorResponse, ApiError } from '../models/api';

interface RequestOptions extends RequestInit {
  idempotencyKey?: string;
  merchantId?: string;
}

const BASE_URL = ''; // Relative path for Vite proxy (/v1/...)

export async function apiRequest<T>(endpoint: string, options: RequestOptions = {}): Promise<T> {
  const { idempotencyKey, merchantId, headers = {}, ...rest } = options;

  // Retrieve active merchant ID from options or localStorage fallback
  const activeMerchantId =
    merchantId ||
    localStorage.getItem('kaipay_active_merchant_id') ||
    '11111111-1111-1111-1111-111111111111';

  const requestHeaders: Record<string, string> = {
    'Content-Type': 'application/json',
    'X-Merchant-Id': activeMerchantId,
    ...(headers as Record<string, string>),
  };

  if (idempotencyKey) {
    requestHeaders['Idempotency-Key'] = idempotencyKey;
  }

  const url = `${BASE_URL}${endpoint}`;

  try {
    const response = await fetch(url, {
      ...rest,
      headers: requestHeaders,
    });

    const responseBody = await response.json().catch(() => null);

    if (!response.ok) {
      if (responseBody && typeof responseBody === 'object' && 'status' in responseBody) {
        throw new ApiError(responseBody as ErrorResponse);
      }
      throw new ApiError({
        status: response.status,
        error: response.statusText || 'HttpError',
        message: responseBody?.message || `Request failed with status ${response.status}`,
        path: endpoint,
        timestamp: new Date().toISOString(),
      });
    }

    const apiResponse = responseBody as ApiResponse<T>;
    return apiResponse.data;
  } catch (error) {
    if (error instanceof ApiError) {
      throw error;
    }
    // Network or parse error
    throw new ApiError({
      status: 0,
      error: 'NetworkError',
      message: (error as Error).message || 'Failed to connect to backend server',
      path: endpoint,
      timestamp: new Date().toISOString(),
    });
  }
}
