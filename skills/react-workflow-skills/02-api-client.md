# Skill: API Client (OpenAPI Generated + Error Handling)

## Generated Client Setup

The TypeScript client is generated from OpenAPI specs using `openapi-typescript-codegen`:

```bash
npx openapi-typescript-codegen \
  --input ../contracts/user-api.yaml \
  --output src/api/generated \
  --client axios \
  --useOptions
```

This generates:
- `src/api/generated/services/UserService.ts` — typed methods for each operation
- `src/api/generated/models/` — TypeScript interfaces for all schemas
- `src/api/generated/core/` — Axios client setup, request handling

## Axios Instance Configuration

```typescript
// src/lib/apiClient.ts
import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { OpenAPI } from '@/api/generated';

const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 30_000,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Inject auth token
apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = localStorage.getItem('access_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Configure the generated client to use our axios instance
OpenAPI.BASE = import.meta.env.VITE_API_BASE_URL || '/api';
OpenAPI.TOKEN = async () => localStorage.getItem('access_token') || '';

export default apiClient;
```

## Error Handling

### Error Envelope Type

```typescript
// src/lib/errorHandler.ts
export interface ErrorEnvelope {
  code: string;
  message: string;
  traceId: string;
  details?: FieldError[];
}

export interface FieldError {
  field: string;
  reason: string;
}

export function isErrorEnvelope(data: unknown): data is ErrorEnvelope {
  return (
    typeof data === 'object' &&
    data !== null &&
    'code' in data &&
    'message' in data &&
    'traceId' in data
  );
}

export function extractError(error: unknown): ErrorEnvelope {
  if (axios.isAxiosError(error)) {
    const axiosError = error as AxiosError<ErrorEnvelope>;
    if (axiosError.response?.data && isErrorEnvelope(axiosError.response.data)) {
      return axiosError.response.data;
    }
    return {
      code: 'NETWORK_ERROR',
      message: axiosError.message || 'Network error',
      traceId: '',
    };
  }
  return {
    code: 'UNKNOWN_ERROR',
    message: error instanceof Error ? error.message : 'An unexpected error occurred',
    traceId: '',
  };
}
```

### Response Interceptor (Global Error Handling)

```typescript
// Add to apiClient.ts
apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    if (error.response?.status === 401) {
      // Token expired — redirect to login or refresh token
      localStorage.removeItem('access_token');
      window.location.href = '/login';
      return Promise.reject(error);
    }

    if (error.response?.status === 503) {
      // Service unavailable — could trigger a global notification
      console.error('Service unavailable', error.response.data);
    }

    return Promise.reject(error);
  }
);
```

## Retry with Exponential Backoff

```typescript
// src/lib/retry.ts
import axios, { type AxiosError } from 'axios';

interface RetryConfig {
  maxRetries: number;
  baseDelayMs: number;
  maxDelayMs: number;
  retryableStatuses: number[];
}

const DEFAULT_RETRY: RetryConfig = {
  maxRetries: 3,
  baseDelayMs: 500,
  maxDelayMs: 10_000,
  retryableStatuses: [408, 429, 500, 502, 503, 504],
};

export async function withRetry<T>(
  fn: () => Promise<T>,
  config: Partial<RetryConfig> = {}
): Promise<T> {
  const { maxRetries, baseDelayMs, maxDelayMs, retryableStatuses } = {
    ...DEFAULT_RETRY,
    ...config,
  };

  let lastError: unknown;

  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    try {
      return await fn();
    } catch (error) {
      lastError = error;

      if (attempt === maxRetries) break;

      const status = axios.isAxiosError(error)
        ? (error as AxiosError).response?.status
        : undefined;

      if (status && !retryableStatuses.includes(status)) {
        break; // Non-retryable status — fail immediately
      }

      // Exponential backoff with jitter
      const delay = Math.min(
        baseDelayMs * Math.pow(2, attempt) + Math.random() * 200,
        maxDelayMs
      );
      await new Promise((resolve) => setTimeout(resolve, delay));
    }
  }

  throw lastError;
}
```

### Usage in Hooks

```typescript
import { withRetry } from '@/lib/retry';
import { UserService } from '@/api/generated';

export function useUsers({ page = 0, size = 20 } = {}) {
  const [data, setData] = useState<PagedUsers | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ErrorEnvelope | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);

    withRetry(() => UserService.listUsers({ page, size }))
      .then(result => { if (!cancelled) setData(result); })
      .catch(err => { if (!cancelled) setError(extractError(err)); })
      .finally(() => { if (!cancelled) setLoading(false); });

    return () => { cancelled = true; };
  }, [page, size]);

  return { data, loading, error };
}
```

## Displaying Errors in UI

```tsx
import { type ErrorEnvelope } from '@/lib/errorHandler';

interface ErrorBannerProps {
  error: ErrorEnvelope;
}

const ErrorBanner: FC<ErrorBannerProps> = ({ error }) => (
  <div role="alert" className="error-banner">
    <strong>{error.code}</strong>: {error.message}
    {error.details && (
      <ul>
        {error.details.map((d, i) => (
          <li key={i}>{d.field}: {d.reason}</li>
        ))}
      </ul>
    )}
    {error.traceId && (
      <small>Trace ID: {error.traceId}</small>
    )}
  </div>
);
```

## Rules for Workers

1. NEVER edit files in `src/api/generated/`. They are overwritten by codegen.
2. All API calls go through the generated services. Do not write raw `axios.get(...)` calls.
3. Every hook that fetches data must handle loading, error, and success states.
4. Use `withRetry` for all read operations. Do NOT retry mutating operations (POST, PUT, DELETE) — they are not idempotent.
5. Error display must use `ErrorEnvelope` structure for consistency with the backend.
6. The `access_token` handling shown here is placeholder. Actual auth flow depends on the OIDC provider (Keycloak).
7. Never store tokens in `localStorage` in production if XSS is a concern. Use HttpOnly cookies or in-memory storage. Adjust based on threat model.
