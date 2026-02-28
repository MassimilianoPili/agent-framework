import { useState, useEffect, useCallback } from 'react';
import type { {{entityName}} } from '@/api/generated/types';
import { {{entityName}}Api } from '@/api/generated/{{entityName}}Api';

interface Use{{entityName}}DataResult {
  data: {{entityName}}[];
  loading: boolean;
  error: Error | null;
  refetch: () => Promise<void>;
  create: (request: Omit<{{entityName}}, 'id'>) => Promise<{{entityName}}>;
  update: (id: string, request: Partial<{{entityName}}>) => Promise<{{entityName}}>;
  remove: (id: string) => Promise<void>;
}

export function use{{entityName}}Data(): Use{{entityName}}DataResult {
  const [data, setData] = useState<{{entityName}}[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);

  const refetch = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);
      const result = await {{entityName}}Api.getAll();
      setData(result);
    } catch (err) {
      setError(err instanceof Error ? err : new Error('Failed to fetch'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refetch();
  }, [refetch]);

  const create = useCallback(async (request: Omit<{{entityName}}, 'id'>) => {
    const created = await {{entityName}}Api.create(request);
    setData((prev) => [...prev, created]);
    return created;
  }, []);

  const update = useCallback(async (id: string, request: Partial<{{entityName}}>) => {
    const updated = await {{entityName}}Api.update(id, request);
    setData((prev) => prev.map((item) => (item.id === id ? updated : item)));
    return updated;
  }, []);

  const remove = useCallback(async (id: string) => {
    await {{entityName}}Api.delete(id);
    setData((prev) => prev.filter((item) => item.id !== id));
  }, []);

  return { data, loading, error, refetch, create, update, remove };
}
