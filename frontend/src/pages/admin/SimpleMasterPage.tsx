import { useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { Modal } from '../../components/common/Modal';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { StatusChip } from '../../components/common/StatusChip';
import { ApiError } from '../../types/api';

export interface MasterRecord {
  id: number;
  code: string;
  name: string;
  description: string | null;
  active: boolean;
  employeeCount: number;
}

interface SimpleMasterPageProps<T extends MasterRecord> {
  title: string;
  description: string;
  entityLabel: string;
  queryKey: string;
  fetchAll: (activeOnly: boolean) => Promise<T[]>;
  create: (payload: Record<string, unknown>) => Promise<T>;
  update: (id: number, payload: Record<string, unknown>) => Promise<T>;
  deactivate: (id: number) => Promise<void>;
  canManage: boolean;
  /** Extra fields rendered inside the dialog, below code/name. */
  extraFields?: (record: T | null) => ReactNode;
  /** Extra values merged into the submitted payload. */
  collectExtra?: (form: HTMLFormElement) => Record<string, unknown>;
  extraColumns?: Array<{ header: string; render: (record: T) => ReactNode }>;
}

export function SimpleMasterPage<T extends MasterRecord>({
  title,
  description,
  entityLabel,
  queryKey,
  fetchAll,
  create,
  update,
  deactivate,
  canManage,
  extraFields,
  collectExtra,
  extraColumns = [],
}: SimpleMasterPageProps<T>) {
  const queryClient = useQueryClient();
  const [showInactive, setShowInactive] = useState(false);
  const [editing, setEditing] = useState<T | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);

  const { data, isLoading, error } = useQuery({
    queryKey: ['master', queryKey, !showInactive],
    queryFn: () => fetchAll(!showInactive),
  });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['master', queryKey] });
  };

  const save = useMutation({
    mutationFn: (payload: Record<string, unknown>) =>
      editing ? update(editing.id, payload) : create(payload),
    onSuccess: () => {
      invalidate();
      setDialogOpen(false);
      setEditing(null);
    },
  });

  const remove = useMutation({
    mutationFn: (id: number) => deactivate(id),
    onSuccess: invalidate,
  });

  const openDialog = (record: T | null) => {
    save.reset();
    setEditing(record);
    setDialogOpen(true);
  };

  const onSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = event.currentTarget;
    const formData = new FormData(form);

    save.mutate({
      code: String(formData.get('code') ?? '').trim().toUpperCase(),
      name: String(formData.get('name') ?? '').trim(),
      description: String(formData.get('description') ?? '').trim() || null,
      active: true,
      ...(collectExtra ? collectExtra(form) : {}),
    });
  };

  return (
    <div className="mx-auto max-w-[1280px]">
      <PageHeader
        title={title}
        description={description}
        actions={
          canManage ? (
            <button type="button" className="btn-primary" onClick={() => openDialog(null)}>
              <Icon name="add" className="text-[18px]" />
              Add {entityLabel}
            </button>
          ) : null
        }
      />

      <label className="mb-md flex w-fit cursor-pointer items-center gap-2 text-body-md text-on-surface-variant">
        <input
          type="checkbox"
          checked={showInactive}
          onChange={(event) => setShowInactive(event.target.checked)}
          className="h-4 w-4 rounded-sm border-outline-variant text-primary focus:ring-primary"
        />
        Show inactive
      </label>

      {remove.isError && (
        <Alert variant="error" title={`Could not deactivate this ${entityLabel}`} className="mb-lg">
          {remove.error instanceof ApiError ? remove.error.message : 'Please try again.'}
        </Alert>
      )}

      <div className="card overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[560px] border-collapse">
            <thead>
              <tr className="border-b border-outline-variant/60 bg-surface-container-low">
                <th className="px-md py-3 text-left text-label-md uppercase text-on-surface-variant">Code</th>
                <th className="px-md py-3 text-left text-label-md uppercase text-on-surface-variant">Name</th>
                {extraColumns.map((column) => (
                  <th
                    key={column.header}
                    className="px-md py-3 text-left text-label-md uppercase text-on-surface-variant"
                  >
                    {column.header}
                  </th>
                ))}
                <th className="px-md py-3 text-right text-label-md uppercase text-on-surface-variant">
                  Employees
                </th>
                <th className="px-md py-3 text-left text-label-md uppercase text-on-surface-variant">
                  Status
                </th>
                {canManage && <th className="px-md py-3" />}
              </tr>
            </thead>
            <tbody>
              {isLoading && (
                <tr>
                  <td colSpan={6} className="px-md py-xl text-center">
                    <Spinner className="mx-auto h-6 w-6 text-primary" />
                  </td>
                </tr>
              )}

              {!isLoading && error && (
                <tr>
                  <td colSpan={6} className="px-md py-xl text-center text-body-md text-error">
                    Could not load {title.toLowerCase()}.
                  </td>
                </tr>
              )}

              {!isLoading && !error && (data ?? []).length === 0 && (
                <tr>
                  <td colSpan={6} className="px-md py-xl text-center">
                    <Icon name="inbox" className="text-[32px] text-outline-variant" />
                    <p className="mt-sm text-body-md text-on-surface-variant">
                      Nothing here yet. Add the first {entityLabel}.
                    </p>
                  </td>
                </tr>
              )}

              {(data ?? []).map((record) => (
                <tr
                  key={record.id}
                  className="border-b border-outline-variant/40 last:border-b-0 even:bg-surface-container-low/40"
                >
                  <td className="px-md py-3 font-mono text-body-sm text-on-surface">{record.code}</td>
                  <td className="px-md py-3 text-body-md text-on-surface">
                    {record.name}
                    {record.description && (
                      <span className="mt-0.5 block text-body-sm text-on-surface-variant">
                        {record.description}
                      </span>
                    )}
                  </td>
                  {extraColumns.map((column) => (
                    <td key={column.header} className="px-md py-3 text-body-md text-on-surface-variant">
                      {column.render(record)}
                    </td>
                  ))}
                  <td className="px-md py-3 text-right text-body-md tabular-nums text-on-surface">
                    {record.employeeCount}
                  </td>
                  <td className="px-md py-3">
                    <StatusChip
                      label={record.active ? 'Active' : 'Inactive'}
                      tone={record.active ? 'success' : 'neutral'}
                    />
                  </td>
                  {canManage && (
                    <td className="px-md py-3 text-right">
                      <div className="flex justify-end gap-1">
                        <button
                          type="button"
                          onClick={() => openDialog(record)}
                          className="rounded-sm p-1.5 text-on-surface-variant transition-colors hover:bg-surface-container hover:text-primary"
                          aria-label={`Edit ${record.name}`}
                        >
                          <Icon name="edit" className="text-[18px]" />
                        </button>
                        {record.active && (
                          <button
                            type="button"
                            onClick={() => remove.mutate(record.id)}
                            disabled={remove.isPending}
                            className="rounded-sm p-1.5 text-on-surface-variant transition-colors hover:bg-error-container hover:text-error"
                            aria-label={`Deactivate ${record.name}`}
                          >
                            <Icon name="block" className="text-[18px]" />
                          </button>
                        )}
                      </div>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <Modal
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        title={editing ? `Edit ${entityLabel}` : `Add ${entityLabel}`}
        description={
          editing ? undefined : 'The code is a short uppercase identifier used across imports and reports.'
        }
        footer={
          <>
            <button type="button" className="btn-secondary" onClick={() => setDialogOpen(false)}>
              Cancel
            </button>
            <button type="submit" form="master-form" className="btn-primary" disabled={save.isPending}>
              {save.isPending ? <Spinner className="h-4 w-4" /> : <Icon name="check" className="text-[18px]" />}
              Save
            </button>
          </>
        }
      >
        {save.isError && (
          <Alert variant="error" title="Could not save" className="mb-md">
            {save.error instanceof ApiError ? save.error.message : 'Please try again.'}
          </Alert>
        )}

        <form id="master-form" onSubmit={onSubmit} className="flex flex-col gap-md">
          <div>
            <label htmlFor="master-code" className="field-label">
              Code
            </label>
            <input
              id="master-code"
              name="code"
              defaultValue={editing?.code ?? ''}
              required
              maxLength={30}
              pattern="[A-Za-z0-9_-]+"
              placeholder="e.g. FINANCE"
              className="field-input font-mono uppercase"
            />
          </div>

          <div>
            <label htmlFor="master-name" className="field-label">
              Name
            </label>
            <input
              id="master-name"
              name="name"
              defaultValue={editing?.name ?? ''}
              required
              maxLength={120}
              className="field-input"
            />
          </div>

          {extraFields?.(editing)}

          <div>
            <label htmlFor="master-description" className="field-label">
              Description
            </label>
            <textarea
              id="master-description"
              name="description"
              defaultValue={editing?.description ?? ''}
              maxLength={500}
              rows={3}
              className="field-input resize-y"
            />
          </div>
        </form>
      </Modal>
    </div>
  );
}
