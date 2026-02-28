# Skill: React Component Patterns

## Project Structure

```
frontend/src/
├── App.tsx
├── main.tsx
├── api/
│   └── generated/          # OpenAPI codegen output (DO NOT EDIT)
├── components/
│   ├── ui/                 # Generic reusable components (Button, Input, Modal)
│   └── layout/             # Shell, Sidebar, Header
├── features/
│   ├── users/
│   │   ├── UserList.tsx
│   │   ├── UserForm.tsx
│   │   ├── UserDetail.tsx
│   │   ├── useUsers.ts     # Custom hook for user data
│   │   └── types.ts        # Feature-specific types (if not in generated/)
│   └── orders/
│       ├── OrderList.tsx
│       └── useOrders.ts
├── hooks/                  # Shared custom hooks
│   ├── useAuth.ts
│   └── usePagination.ts
├── lib/                    # Utilities and API client setup
│   ├── apiClient.ts
│   └── errorHandler.ts
└── routes/                 # Route definitions
    └── index.tsx
```

## Functional Component Template

```tsx
import { type FC } from 'react';

interface UserCardProps {
  name: string;
  email: string;
  onEdit: (email: string) => void;
}

const UserCard: FC<UserCardProps> = ({ name, email, onEdit }) => {
  return (
    <div className="user-card">
      <h3>{name}</h3>
      <p>{email}</p>
      <button onClick={() => onEdit(email)}>Edit</button>
    </div>
  );
};

export default UserCard;
```

## Props Rules

1. Always define props with a TypeScript `interface` (not `type` alias, for consistency).
2. Destructure props in the function signature.
3. Use `FC<Props>` typing for components.
4. Default values go in the destructuring: `{ size = 'md' }`.
5. Children: use `PropsWithChildren<T>` or explicit `children: ReactNode`.

## Composition over Configuration

```tsx
// CORRECT: Composition — each piece is a separate component
<DataTable>
  <DataTable.Header>
    <DataTable.Column sortable>Name</DataTable.Column>
    <DataTable.Column>Email</DataTable.Column>
  </DataTable.Header>
  <DataTable.Body>
    {users.map(u => (
      <DataTable.Row key={u.id}>
        <DataTable.Cell>{u.name}</DataTable.Cell>
        <DataTable.Cell>{u.email}</DataTable.Cell>
      </DataTable.Row>
    ))}
  </DataTable.Body>
</DataTable>

// WRONG: Configuration — massive props object
<DataTable
  columns={[{key:'name',sortable:true},{key:'email'}]}
  data={users}
  renderRow={(u) => [u.name, u.email]}
/>
```

## Custom Hooks for Data Fetching

```tsx
import { useState, useEffect } from 'react';
import { UserService, type UserResponse, type PagedUsers } from '@/api/generated';

interface UseUsersOptions {
  page?: number;
  size?: number;
}

export function useUsers({ page = 0, size = 20 }: UseUsersOptions = {}) {
  const [data, setData] = useState<PagedUsers | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);

    UserService.listUsers({ page, size })
      .then(result => {
        if (!cancelled) setData(result);
      })
      .catch(err => {
        if (!cancelled) setError(err);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => { cancelled = true; };
  }, [page, size]);

  return { data, loading, error };
}
```

## Feature Page Example

```tsx
import { useState } from 'react';
import { useUsers } from './useUsers';
import UserCard from './UserCard';
import Pagination from '@/components/ui/Pagination';

const UserList: FC = () => {
  const [page, setPage] = useState(0);
  const { data, loading, error } = useUsers({ page });

  if (loading) return <div>Loading...</div>;
  if (error) return <div>Error: {error.message}</div>;
  if (!data) return null;

  return (
    <div>
      <h1>Users</h1>
      <div className="grid">
        {data.content.map(user => (
          <UserCard
            key={user.id}
            name={user.name}
            email={user.email}
            onEdit={(email) => console.log('edit', email)}
          />
        ))}
      </div>
      <Pagination
        page={data.page}
        totalPages={data.totalPages}
        onPageChange={setPage}
      />
    </div>
  );
};

export default UserList;
```

## Server Components Awareness

React 19 introduces Server Components (RSC). For this project:

- The frontend is a **client-side SPA** (Vite build). RSC is NOT used.
- All components are client components by default (no `'use client'` directive needed).
- If RSC adoption happens later, data-fetching hooks will migrate to server components and the presentation layer remains unchanged.

## Rules for Workers

1. One component per file. File name matches component name (`UserCard.tsx` exports `UserCard`).
2. Feature code lives in `features/{name}/`. Shared UI goes in `components/ui/`.
3. Never use `any` type. If the type is unknown, use `unknown` and narrow it.
4. Never fetch data directly in components. Always use a custom hook.
5. Generated API code in `api/generated/` is NEVER edited manually.
6. Use named exports for hooks, default exports for page/feature components.
7. State management: use `useState`/`useReducer` for local state. For shared state, lift to the nearest common ancestor or use React Context. No Redux unless explicitly required.
8. Error boundaries: wrap feature routes in `<ErrorBoundary>` to catch rendering errors gracefully.
