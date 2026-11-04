import { useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { Modal } from '../../components/common/Modal';
import { PageHeader } from '../../components/common/PageHeader';
import { StatusChip } from '../../components/common/StatusChip';
import { DataTable, type Column } from '../../components/table/DataTable';
import { leaveApi } from '../../features/leave/leaveApi';
import { ApiError } from '../../types/api';
import { LEAVE_STATUS_TONE, type LeaveRequest } from '../../types/leave';

const STATUSES = ['', 'PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'CANCELLED'];

const label = (value: string): string =>
  value.charAt(0) + value.slice(1).toLowerCase();

export const MyLeavesPage = () => {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [selected, setSelected] = useState<LeaveRequest | null>(null);

  const { data, isLoading, error } = useQuery({
    queryKey: ['leave', 'my', status, page, size],
    queryFn: () => leaveApi.myRequests({ status: status || undefined, page, size }),
    placeholderData: keepPreviousData,
  });

  const act = useMutation({
    mutationFn: ({ id, action }: { id: number; action: 'withdraw' | 'cancel' }) =>
      action === 'withdraw' ? leaveApi.withdraw(id) : leaveApi.cancel(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['leave'] });
      setSelected(null);
    },
  });

  const columns: Column<LeaveRequest>[] = [
    {
      key: 'type',
      header: 'Leave',
      render: (row) => (
        <div className="min-w-0">
          <p className="truncate text-on-surface">{row.leaveTypeName}</p>
          <p className="truncate font-mono text-body-sm text-on-surface-variant">
            {row.requestNumber}
          </p>
        </div>
      ),
    },
    {
      key: 'dates',
      header: 'Dates',
      className: 'whitespace-nowrap',
      render: (row) => (
        <div>
          <p className="tabular-nums text-on-surface">
            {new Date(row.fromDate).toLocaleDateString(undefined, { day: '2-digit', month: 'short' })}
            {row.fromDate !== row.toDate && (
              <>
                {' – '}
                {new Date(row.toDate).toLocaleDateString(undefined, { day: '2-digit', month: 'short' })}
              </>
            )}
          </p>
          {row.totalDays !== row.calendarDays && (
            <p className="text-body-sm text-on-surface-variant">
              {row.calendarDays} calendar days
            </p>
          )}
        </div>
      ),
    },
    {
      key: 'days',
      header: 'Charged',
      className: 'tabular-nums whitespace-nowrap',
      render: (row) => `${Number(row.totalDays)}`,
    },
    {
      key: 'approver',
      header: 'With',
      render: (row) =>
        row.currentApproverName ?? <span className="text-outline">—</span>,
    },
    {
      key: 'applied',
      header: 'Applied',
      className: 'tabular-nums whitespace-nowrap',
      render: (row) => new Date(row.appliedAt).toLocaleDateString(),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <StatusChip label={label(row.status)} tone={LEAVE_STATUS_TONE[row.status] ?? 'neutral'} />
      ),
    },
  ];

  return (
    <div className="mx-auto max-w-[1280px]">
      <PageHeader
        title="Leave History"
        description="Every request you have made, and where each one stands."
      />

      {act.isError && (
        <Alert variant="error" title="Could not update that request" className="mb-lg">
          {act.error instanceof ApiError ? act.error.message : 'Please try again.'}
        </Alert>
      )}

      <div className="mb-lg flex flex-wrap gap-1" role="group" aria-label="Filter by status">
        {STATUSES.map((option) => (
          <button
            key={option || 'ALL'}
            type="button"
            onClick={() => {
              setStatus(option);
              setPage(0);
            }}
            className={
              status === option
                ? 'rounded-DEFAULT bg-secondary-container px-3 py-2 text-label-md text-primary'
                : 'rounded-DEFAULT px-3 py-2 text-label-md text-on-surface-variant transition-colors hover:bg-surface-container-low'
            }
          >
            {option ? label(option) : 'All'}
          </button>
        ))}
      </div>

      <DataTable
        columns={columns}
        rows={data?.content ?? []}
        rowKey={(row) => row.id}
        loading={isLoading}
        error={error instanceof ApiError ? error.message : error ? 'Could not load your leave.' : null}
        emptyTitle="No leave requests"
        emptyMessage="Requests you make will appear here."
        onRowClick={setSelected}
        page={data?.page ?? 0}
        size={size}
        totalElements={data?.totalElements ?? 0}
        totalPages={data?.totalPages ?? 0}
        onPageChange={setPage}
        onSizeChange={(next) => {
          setSize(next);
          setPage(0);
        }}
      />

      <Modal
        open={selected !== null}
        onClose={() => setSelected(null)}
        title={selected ? selected.leaveTypeName : ''}
        description={selected?.requestNumber}
        footer={
          selected && (selected.canWithdraw || selected.canCancel) ? (
            <>
              <button type="button" className="btn-secondary" onClick={() => setSelected(null)}>
                Close
              </button>
              <button
                type="button"
                className="btn-primary"
                disabled={act.isPending}
                onClick={() =>
                  act.mutate({
                    id: selected.id,
                    action: selected.canWithdraw ? 'withdraw' : 'cancel',
                  })
                }
              >
                <Icon name="undo" className="text-[18px]" />
                {selected.canWithdraw ? 'Withdraw request' : 'Cancel leave'}
              </button>
            </>
          ) : undefined
        }
      >
        {selected && (
          <div className="flex flex-col gap-lg">
            <dl className="grid gap-md sm:grid-cols-2">
              <div>
                <dt className="text-body-sm text-on-surface-variant">Status</dt>
                <dd className="mt-unit">
                  <StatusChip
                    label={label(selected.status)}
                    tone={LEAVE_STATUS_TONE[selected.status] ?? 'neutral'}
                  />
                </dd>
              </div>
              <div>
                <dt className="text-body-sm text-on-surface-variant">Charged</dt>
                <dd className="text-body-md tabular-nums text-on-surface">
                  {Number(selected.totalDays)} of {selected.calendarDays} calendar days
                </dd>
              </div>
              <div className="sm:col-span-2">
                <dt className="text-body-sm text-on-surface-variant">Reason</dt>
                <dd className="text-body-md text-on-surface">{selected.reason}</dd>
              </div>
              {selected.cancellationReason && (
                <div className="sm:col-span-2">
                  <dt className="text-body-sm text-on-surface-variant">Cancellation reason</dt>
                  <dd className="text-body-md text-on-surface">{selected.cancellationReason}</dd>
                </div>
              )}
            </dl>

            <section>
              <h3 className="mb-sm text-label-md uppercase text-on-surface-variant">Days</h3>
              <ul className="space-y-1">
                {selected.days.map((day) => (
                  <li
                    key={day.date}
                    className="flex items-center justify-between gap-md border-b border-outline-variant/40 pb-1 text-body-sm last:border-b-0"
                  >
                    <span className="tabular-nums text-on-surface">
                      {day.date} · {day.dayOfWeek.slice(0, 3)}
                    </span>
                    <span className={day.deductible ? 'text-on-surface' : 'text-outline'}>
                      {day.deductible
                        ? day.dayPortion === 'FULL_DAY'
                          ? 'Charged'
                          : 'Half day'
                        : `Not charged · ${day.dayType.replace('_', ' ').toLowerCase()}`}
                    </span>
                  </li>
                ))}
              </ul>
            </section>

            <section>
              <h3 className="mb-sm text-label-md uppercase text-on-surface-variant">Approvals</h3>
              <ol className="space-y-sm">
                {selected.approvals.map((step) => (
                  <li key={step.level} className="flex items-start gap-sm">
                    <span className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-surface-container text-body-sm tabular-nums text-on-surface-variant">
                      {step.level}
                    </span>
                    <div className="min-w-0">
                      <p className="text-body-md text-on-surface">
                        {step.approverName ?? step.approverRole ?? 'Unassigned'}
                        <span className="ml-2 text-body-sm text-on-surface-variant">
                          {label(step.action)}
                        </span>
                      </p>
                      {step.comments && (
                        <p className="text-body-sm text-on-surface-variant">{step.comments}</p>
                      )}
                    </div>
                  </li>
                ))}
              </ol>
            </section>
          </div>
        )}
      </Modal>
    </div>
  );
};
