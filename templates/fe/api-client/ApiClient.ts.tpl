import type { {{entityName}} } from './types';

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

async function handleResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const error = await response.json().catch(() => ({ message: response.statusText }));
    throw new Error(error.message ?? `HTTP ${response.status}`);
  }
  return response.json();
}

export const {{entityName}}Api = {
  async getAll(): Promise<{{entityName}}[]> {
    const response = await fetch(`${BASE_URL}/api/v1/{{resourcePath}}`);
    return handleResponse<{{entityName}}[]>(response);
  },

  async getById(id: string): Promise<{{entityName}}> {
    const response = await fetch(`${BASE_URL}/api/v1/{{resourcePath}}/${id}`);
    return handleResponse<{{entityName}}>(response);
  },

  async create(request: Omit<{{entityName}}, 'id'>): Promise<{{entityName}}> {
    const response = await fetch(`${BASE_URL}/api/v1/{{resourcePath}}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
    return handleResponse<{{entityName}}>(response);
  },

  async update(id: string, request: Partial<{{entityName}}>): Promise<{{entityName}}> {
    const response = await fetch(`${BASE_URL}/api/v1/{{resourcePath}}/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
    return handleResponse<{{entityName}}>(response);
  },

  async delete(id: string): Promise<void> {
    const response = await fetch(`${BASE_URL}/api/v1/{{resourcePath}}/${id}`, {
      method: 'DELETE',
    });
    if (!response.ok) {
      throw new Error(`Failed to delete: ${response.statusText}`);
    }
  },
};
