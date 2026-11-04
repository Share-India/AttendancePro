import { useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { Modal } from '../../components/common/Modal';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { StatusChip } from '../../components/common/StatusChip';
import { DataTable, type Column } from '../../components/table/DataTable';
import { leaveApi } from '../../features/leave/leaveApi';
import { ApiError } from '../../types/api';
import type { LeaveRequest } from '../../types/leave';

export const LeaveApprovalsPage = () => {
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [selected, setSelected] = useState<LeaveRequest | null>(null);
  const [comments, setComments] = useState('');
  const [rejecting, setRejecting] = useState(false);

  const { data, isLoading, error } = useQuery({
    queryKey: ['leave', 'approvals', page, size],
    queryFn: () => leaveApi.pendingApprovals({ page, size }),
    placeholderData: keepPreviousData,
  });

  const close = () => {
    setSelected(null);
    setComments('');
    setRejecting(false);
  };

  const decide = useMutation({
    mutationFn: ({ id, approve }: { id: number; approve: boolean }) =>
      approve ? leaveApi.approve(id, comments || undefined) : leaveApi.reject(id, comments),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['leave'] });
      close();
    },
  });

  const columns: Column<LeaveRequest>[] = [
    {
      key: 'employee',
      header: 'Employee',
      render: (row) => (
        <div className="flex items-center gap-sm">
          <span
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary-fixed text-body-sm font-semibold text-on-primary-fixed"
            aria-hidden="true"
          >
            {row.employeeName.charAt(0).toUpperCase()}
          </span>
          <div className="min-w-0">
            <p className="truncate text-on-surface">{row.employeeName}</p>
            <p className="truncate font-mono text-body-sm text-on-surface-variant">
              {row.employeeCode}
            </p>
          </div>
        </div>
      ),
    },
    { key: 'type', header: 'Leave', render: (row) => row.leaveTypeName },
    {
      key: 'dates',
      header: 'Dates',
      className: 'whitespace-nowrap tabular-nums',
      render: (row) =>
        row.fromDate === row.toDate
          ? new Date(row.fromDate).toLocaleDateString()
          : `${new Date(row.fromDate).toLocaleDateString()} – ${new Date(row.toDate).toLocaleDateString()}`,
    },
    {
      key: 'days',
      header: 'Days',
      className: 'tabular-nums',
      render: (row) => `${Number(row.totalDays)}`,
    },
    {
      key: 'level',
      header: 'Level',
      render: (row) => <StatusChip label={`Level ${row.currentLevel}`} tone="info" />,
    },
    {
      key: 'applied',
      header: 'Waiting since',
      className: 'whitespace-nowrap tabular-nums',
      render: (row) => new Date(row.appliedAt).toLocaleDateString(),
    },
  ];

  return (
    <div className="mx-auto max-w-[1280px]">
      <PageHeader
        title="Leave Approvals"
        description="Requests routed to you. You see your own team only — the API enforces that, not just this page."
      />

      {decide.isError && (
        <Alert variant="error" title="Could not record your decision" className="mb-lg">
          {decide.error instanceof ApiError ? decide.error.message : 'Please try again.'}
        </Alert>
      )}

      <DataTable
        columns={columns}
        rows={data?.content ?? []}
        rowKey={(row) => row.id}
        loading={isLoading}
        error={error instanceof ApiError ? error.message : error ? 'Could not load approvals.' : null}
        emptyTitle="Nothing waiting on you"
        emptyMessage="Requests needing your decision will appear here."
        onRowClick={(row) => {
          setSelected(row);
          setComments('');
          setRejecting(false);
        }}
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
        onClose={close}
        title={selected ? `${selected.employeeName} · ${selected.leaveTypeName}` : ''}
        description={selected?.requestNumber}
        footer={
          selected ? (
            <>
              <button type="button" className="btn-secondary" onClick={close}>
                Close
              </button>
              <button
                type="button"
                className="btn-secondary text-error"
                disabled={decide.isPending}
                onClick={() => {
                  // A rejection needs a reason, so the first press reveals the
                  // field rather than sending an empty one the API will refuse.
                  if (!rejecting) {
                    setRejecting(true);
                    return;
                  }
                  if (comments.trim()) {
                    decide.mutate({ id: selected.id, approve: false });
                  }
                }}
              >
                <Icon name="close" className="text-[18px]" />
                {rejecting ? 'Confirm rejection' : 'Reject'}
              </button>
              <button
                type="button"
                className="btn-primary"
                disabled={decide.isPending || rejecting}
                onClick={() => decide.mutate({ id: selected.id, approve: true })}
              >
                {decide.isPending ? (
                  <Spinner className="h-4 w-4" />
                ) : (
                  <Icon name="check" className="text-[18px]" />
                )}
                Approve
              </button>
            </>
          ) : undefined
        }
      >
        {selected && (
          <div className="flex flex-col gap-lg">
            <dl className="grid gap-md sm:grid-cols-2">
              <div>
                <dt className="text-body-sm text-on-surface-variant">Dates</dt>
                <dd className="text-body-md text-on-surface">
                  {selected.fromDate} to {selected.toDate}
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
              {selected.contactDuringLeave && (
                <div>
                  <dt className="text-body-sm text-on-surface-variant">Contact while away</dt>
                  <dd className="text-body-md text-on-surface">{selected.contactDuringLeave}</dd>
                </div>
              )}
              {selected.attachmentUrl && (
                <div>
                  <dt className="text-body-sm text-on-surface-variant">Document</dt>
                  <dd>
                    <a
                      href={selected.attachmentUrl}
                      target="_blank"
                      rel="noreferrer"
                      className="text-body-md text-primary underline underline-offset-2"
                    >
                      Open attachment
                    </a>
                  </dd>
                </div>
              )}
            </dl>

            <div>
              <label htmlFor="decision-comments" className="field-label">
                {rejecting ? 'Reason for rejection (required)' : 'Comments (optional)'}
              </label>
              <textarea
                id="decision-comments"
                rows={3}
                maxLength={1000}
                value={comments}
                onChange={(event) => setComments(event.target.value)}
                className={`field-input resize-y ${
                  rejecting && !comments.trim() ? 'field-input-error' : ''
                }`}
                placeholder={
                  rejecting
                    ? 'The employee sees this, so say what would change your mind.'
                    : 'Anything you want on the record.'
                }
              />
              {rejecting && !comments.trim() && (
                <p className="field-error">
                  <Icon name="error" className="text-[14px]" />
                  A rejection must say why.
                </p>
              )}
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
};
