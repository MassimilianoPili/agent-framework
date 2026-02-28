import { useState } from 'react';
import type { {{entityName}} } from '@/api/generated/types';

interface {{entityName}}Props {
  data: {{entityName}};
  onUpdate?: (updated: {{entityName}}) => void;
  onDelete?: (id: string) => void;
}

export function {{entityName}}Card({ data, onUpdate, onDelete }: {{entityName}}Props) {
  const [isEditing, setIsEditing] = useState(false);

  const handleDelete = () => {
    if (onDelete && window.confirm('Are you sure?')) {
      onDelete(data.id);
    }
  };

  return (
    <div className="rounded-lg border p-4 shadow-sm">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold">
          {/* TODO: Display entity name/title */}
          {data.id}
        </h3>
        <div className="flex gap-2">
          <button
            onClick={() => setIsEditing(!isEditing)}
            className="text-sm text-blue-600 hover:underline"
          >
            {isEditing ? 'Cancel' : 'Edit'}
          </button>
          <button
            onClick={handleDelete}
            className="text-sm text-red-600 hover:underline"
          >
            Delete
          </button>
        </div>
      </div>

      {isEditing ? (
        <div className="mt-4">
          {/* TODO: Edit form fields */}
          <p>Edit form placeholder</p>
        </div>
      ) : (
        <div className="mt-2 text-sm text-gray-600">
          {/* TODO: Display entity details */}
          <p>Details placeholder</p>
        </div>
      )}
    </div>
  );
}

export default {{entityName}}Card;
