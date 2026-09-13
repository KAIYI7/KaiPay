export interface ApiResponse<T> {
  success: boolean;
  message: string;
  data: T;
  timestamp: string;
}

export interface ValidationError {
  field: string;
  message: string;
}

export interface ErrorResponse {
  status: number;
  error: string;
  message: string;
  path: string;
  timestamp: string;
  validationErrors?: ValidationError[];
}

export class ApiError extends Error {
  status: number;
  errorName: string;
  path: string;
  validationErrors?: ValidationError[];

  constructor(errorResponse: ErrorResponse) {
    super(errorResponse.message);
    this.name = 'ApiError';
    this.status = errorResponse.status;
    this.errorName = errorResponse.error;
    this.path = errorResponse.path;
    this.validationErrors = errorResponse.validationErrors;
  }
}
