import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { apiClient, unwrap } from '../../api/axiosClient';
import { endpoints } from '../../api/endpoints';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { Modal } from '../../components/common/Modal';
import { PageHeader } from '../../components/common/PageHeader';
import { SelectField } from '../../components/form/SelectField';
import { StatusChip } from '../../components/common/StatusChip';
import { DataTable, type Column } from '../../components/table/DataTable';
import { useDebounce } from '../../hooks/useDebounce';
import { ApiError, type ApiResponse, type PageResponse } from '../../types/api';

interface AuditLog {
  id: number;
  action: string | null;
  entityType: string | null;
  entityId: string | null;
  description: string | null;
  oldValue: string | null;
  newValue: string | null;
  actorUserId: number | null;
  actorUsername: string | null;
  actorName: string | null;
  ipAddress: string | null;
  userAgent: string | null;
  createdAt: string;
}

const ACTION_TONE: Record<string, 'success' | 'warning' | 'error' | 'info' | 'neutral'> = {
  CREATE: 'success',
  UPDATE: 'info',
  DELETE: 'error',
  APPROVE: 'success',
  REJECT: 'error',
  CANCEL: 'neutral',
  LOGIN_SUCCESS: 'neutral',
  LOGIN_FAILURE: 'error',
  ACCOUNT_LOCKED: 'error',
  PASSWORD_CHANGE: 'warning',
  ROLE_ASSIGN: 'warning',
};

const isoToday = (): string => new Date().toISOString().slice(0, 10);

/** Pretty-prints the stored JSON, falling back to the raw text if it is not JSON. */
const pretty = (value: string | null): string => {
  if (!value) return '—';
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
};

export const AuditLogPage = () => {
  const [entityType, setEntityType] = useState('');
  const [action, setAction] = useState('');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState(isoToday());
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const [selected, setSelected] = useState<AuditLog | null>(null);
  const debouncedSearch = useDebounce(search, 300);

  const entityTypes = useQuery({
    queryKey: ['audit', 'entity-types'],
    queryFn: async () =>
      unwrap(await apiClient.get<ApiResponse<string[]>>(endpoints.admin.auditEntityTypes)),
  });

  const logs = useQuery({
    queryKey: ['audit', 'logs', entityType, action, fromDate, toDate, debouncedSearch, page, size],
    queryFn: async () =>
      unwrap(
        await apiClient.get<ApiResponse<PageResponse<AuditLog>>>(endpoints.admin.auditLogs, {
          params: {
            entityType: entityType || undefined,
            action: action || undefined,
            fromDate: fromDate || undefined,
            toDate: toDate || undefined,
            search: debouncedSearch || undefined,
            page,
            size,
          },
        }),
      ),
    placeholderData: keepPreviousData,
  });

  const columns: Column<AuditLog>[] = [
    {
      key: 'when',
      header: 'When',
      className: 'whitespace-nowrap tabular-nums',
      render: (row) => (
        <div>
          <p className="text-on-surface">{new Date(row.createdAt).toLocaleDateString()}</p>
          <p className="text-body-sm text-on-surface-variant">
            {new Date(row.createdAt).toLocaleTimeString()}
          </p>
        </div>
      ),
    },
    {
      key: 'action',
      header: 'Action',
      render: (row) =>
        row.action ? (
          <StatusChip label={row.action} tone={ACTION_TONE[row.action] ?? 'neutral'} />
        ) : (
          '—'
        ),
    },
    {
      key: 'entity',
      header: 'Record',
      render: (row) => (
        <div className="min-w-0">
          <p className="truncate text-on-surface">{row.entityType ?? '—'}</p>
          {row.entityId && (
            <p className="truncate font-mono text-body-sm text-on-surface-variant">
              #{row.entityId}
            </p>
          )}
        </div>
      ),
    },
    {
      key: 'actor',
      header: 'By',
      render: (row) => (
        <div className="min-w-0">
          <p className="truncate text-on-surface">{row.actorName ?? row.actorUsername ?? 'System'}</p>
          {row.ipAddress && (
            <p className="truncate font-mono text-body-sm text-on-surface-variant">
              {row.ipAddress}
            </p>
          )}
        </div>
      ),
    },
    {
      key: 'description',
      header: 'What happened',
      render: (row) => row.description ?? '—',
    },
  ];

  return (
    <div className="mx-auto max-w-[1280px]">
      <PageHeader
        title="Audit Trail"
        description="Every change worth answering for. Read-only by design — nothing in this application can edit or delete an entry."
      />

      <section className="card mb-lg p-lg">
        <div className="grid gap-md sm:grid-cols-2 lg:grid-cols-5">
          <SelectField
            label="Record type"
            placeholder="All types"
            value={entityType}
            onChange={(event) => {
              setEntityType(event.target.value);
              setPage(0);
            }}
            options={(entityTypes.data ?? []).map((type) => ({ value: type, label: type }))}
          />
          <SelectField
            label="Action"
            placeholder="All actions"
            value={action}
            onChange={(event) => {
              setAction(event.target.value);
              setPage(0);
            }}
            options={Object.keys(ACTION_TONE).map((value) => ({ value, label: value }))}
          />
          <div className="flex flex-col">
            <label htmlFor="audit-from" className="field-label">From</label>
            <input
              id="audit-from"
              type="date"
              value={fromDate}
              max={toDate}
              onChange={(event) => {
                setFromDate(event.target.value);
                setPage(0);
              }}
              className="field-input tabular-nums"
            />
          </div>
          <div className="flex flex-col">
            <label htmlFor="audit-to" className="field-label">To</label>
            <input
              id="audit-to"
              type="date"
              value={toDate}
              min={fromDate}
              max={isoToday()}
              onChange={(event) => {
                setToDate(event.target.value);
                setPage(0);
              }}
              className="field-input tabular-nums"
            />
          </div>
          <div className="flex flex-col">
            <label htmlFor="audit-search" className="field-label">Search</label>
            <input
              id="audit-search"
              type="search"
              value={search}
              onChange={(event) => {
                setSearch(event.target.value);
                setPage(0);
              }}
              placeholder="Description or person"
              className="field-input"
            />
          </div>
        </div>
      </section>

      {logs.isError && (
        <Alert variant="error" title="Could not load the audit trail" className="mb-lg">
          {logs.error instanceof ApiError ? logs.error.message : 'Please try again.'}
        </Alert>
      )}

      <DataTable
        columns={columns}
        rows={logs.data?.content ?? []}
        rowKey={(row) => row.id}
        loading={logs.isLoading}
        emptyTitle="Nothing recorded"
        emptyMessage="No entries match these filters."
        onRowClick={setSelected}
        page={logs.data?.page ?? 0}
        size={size}
        totalElements={logs.data?.totalElements ?? 0}
        totalPages={logs.data?.totalPages ?? 0}
        onPageChange={setPage}
        onSizeChange={(next) => {
          setSize(next);
          setPage(0);
        }}
      />

      <Modal
        open={selected !== null}
        onClose={() => setSelected(null)}
        title={selected ? `${selected.action} · ${selected.entityType ?? 'System'}` : ''}
        description={selected ? new Date(selected.createdAt).toLocaleString() : undefined}
        footer={
          <button type="button" className="btn-secondary" onClick={() => setSelected(null)}>
            Close
          </button>
        }
      >
        {selected && (
          <div className="flex flex-col gap-lg">
            <dl className="grid gap-md sm:grid-cols-2">
              <div className="sm:col-span-2">
                <dt className="text-body-sm text-on-surface-variant">What happened</dt>
                <dd className="text-body-md text-on-surface">{selected.description ?? '—'}</dd>
              </div>
              <div>
                <dt className="text-body-sm text-on-surface-variant">By</dt>
                <dd className="text-body-md text-on-surface">
                  {selected.actorName ?? selected.actorUsername ?? 'System'}
                </dd>
              </div>
              <div>
                <dt className="text-body-sm text-on-surface-variant">From</dt>
                <dd className="font-mono text-body-md text-on-surface">
                  {selected.ipAddress ?? '—'}
                </dd>
              </div>
              {selected.userAgent && (
                <div className="sm:col-span-2">
                  <dt className="text-body-sm text-on-surface-variant">Device</dt>
                  <dd className="text-body-sm text-on-surface-variant">{selected.userAgent}</dd>
                </div>
              )}
            </dl>

            {(selected.oldValue || selected.newValue) && (
              <div className="grid gap-md sm:grid-cols-2">
                <div>
                  <h3 className="mb-xs flex items-center gap-1 text-title-sm text-on-surface">
                    <Icon name="history" className="text-[16px]" />
                    Before
                  </h3>
                  <pre className="max-h-56 overflow-auto rounded-DEFAULT bg-surface-container p-md font-mono text-body-sm text-on-surface-variant">
                    {pretty(selected.oldValue)}
                  </pre>
                </div>
                <div>
                  <h3 className="mb-xs flex items-center gap-1 text-title-sm text-on-surface">
                    <Icon name="update" className="text-[16px]" />
                    After
                  </h3>
                  <pre className="max-h-56 overflow-auto rounded-DEFAULT bg-primary-fixed p-md font-mono text-body-sm text-on-primary-fixed">
                    {pretty(selected.newValue)}
                  </pre>
                </div>
              </div>
            )}
          </div>
        )}
      </Modal>
    </div>
  );
};
