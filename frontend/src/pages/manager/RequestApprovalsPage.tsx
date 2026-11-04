import { useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { Modal } from '../../components/common/Modal';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { DataTable, type Column } from '../../components/table/DataTable';
import { useAuth } from '../../features/auth/useAuth';
import { PERMISSIONS } from '../../constants/permissions';
import { PunchChange } from '../../features/request/PunchChange';
import { regularizationApi, wfhApi } from '../../features/request/requestApi';
import { ApiError } from '../../types/api';
import {
  REGULARIZATION_REASONS,
  WORK_LOCATIONS,
  type Regularization,
  type WorkFromHomeRequest,
} from '../../types/request';

type Tab = 'corrections' | 'wfh';

const timeOnly = (value: string | null): string =>
  value ? new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '—';

const EmployeeCell = ({ name, code }: { name: string; code: string }) => (
  <div className="flex items-center gap-sm">
    <span
      className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary-fixed text-body-sm font-semibold text-on-primary-fixed"
      aria-hidden="true"
    >
      {name.charAt(0).toUpperCase()}
    </span>
    <div className="min-w-0">
      <p className="truncate text-on-surface">{name}</p>
      <p className="truncate font-mono text-body-sm text-on-surface-variant">{code}</p>
    </div>
  </div>
);

export const RequestApprovalsPage = () => {
  const queryClient = useQueryClient();
  const { hasAnyPermission } = useAuth();

  const canDecideCorrections = hasAnyPermission([
    PERMISSIONS.REGULARIZATION_APPROVE_L1,
    PERMISSIONS.REGULARIZATION_APPROVE_L2,
  ]);
  const canDecideWfh = hasAnyPermission([PERMISSIONS.WFH_APPROVE]);

  const [tab, setTab] = useState<Tab>(canDecideCorrections ? 'corrections' : 'wfh');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [comments, setComments] = useState('');
  const [rejecting, setRejecting] = useState(false);
  const [selectedCorrection, setSelectedCorrection] = useState<Regularization | null>(null);
  const [selectedWfh, setSelectedWfh] = useState<WorkFromHomeRequest | null>(null);

  const corrections = useQuery({
    queryKey: ['regularization', 'approvals', page, size],
    queryFn: () => regularizationApi.pendingApprovals({ page, size }),
    placeholderData: keepPreviousData,
    enabled: tab === 'corrections' && canDecideCorrections,
  });

  const wfh = useQuery({
    queryKey: ['wfh', 'approvals', page, size],
    queryFn: () => wfhApi.pendingApprovals({ page, size }),
    placeholderData: keepPreviousData,
    enabled: tab === 'wfh' && canDecideWfh,
  });

  // Counts come from the same queries the tables use, so a badge can never
  // disagree with the list underneath it.
  const correctionCount = corrections.data?.totalElements;
  const wfhCount = wfh.data?.totalElements;

  const close = () => {
    setSelectedCorrection(null);
    setSelectedWfh(null);
    setComments('');
    setRejecting(false);
  };

  const decide = useMutation({
    // Returns nothing on purpose. The two APIs answer with different shapes and
    // the result is never read — only the invalidation below matters — so
    // widening it to a union would buy a type nobody uses.
    mutationFn: async ({ id, approve, kind }: { id: number; approve: boolean; kind: Tab }): Promise<void> => {
      const api = kind === 'corrections' ? regularizationApi : wfhApi;
      if (approve) {
        await api.approve(id, comments || undefined);
      } else {
        await api.reject(id, comments);
      }
    },
    onSuccess: (_result, variables) => {
      queryClient.invalidateQueries({
        queryKey: [variables.kind === 'corrections' ? 'regularization' : 'wfh'],
      });
      // An approved correction rewrites the day, so anything showing attendance
      // is now stale too.
      queryClient.invalidateQueries({ queryKey: ['attendance'] });
      close();
    },
  });

  const switchTab = (next: Tab) => {
    setTab(next);
    setPage(0);
    close();
  };

  const correctionColumns: Column<Regularization>[] = [
    {
      key: 'employee',
      header: 'Employee',
      render: (row) => <EmployeeCell name={row.employeeName} code={row.employeeCode} />,
    },
    {
      key: 'date',
      header: 'Date',
      className: 'whitespace-nowrap tabular-nums',
      render: (row) => (
        <div>
          <p className="text-on-surface">{new Date(row.attendanceDate).toLocaleDateString()}</p>
          <p className="text-body-sm text-on-surface-variant">{row.dayOfWeek}</p>
        </div>
      ),
    },
    {
      key: 'reason',
      header: 'Reason',
      render: (row) =>
        REGULARIZATION_REASONS.find((option) => option.value === row.reasonType)?.label ??
        row.reasonType,
    },
    {
      key: 'change',
      header: 'Change',
      className: 'whitespace-nowrap tabular-nums',
      render: (row) => <PunchChange request={row} />,
    },
    {
      key: 'applied',
      header: 'Waiting since',
      className: 'whitespace-nowrap tabular-nums',
      render: (row) => new Date(row.appliedAt).toLocaleDateString(),
    },
  ];

  const wfhColumns: Column<WorkFromHomeRequest>[] = [
    {
      key: 'employee',
      header: 'Employee',
      render: (row) => <EmployeeCell name={row.employeeName} code={row.employeeCode} />,
    },
    {
      key: 'dates',
      header: 'Days',
      className: 'whitespace-nowrap tabular-nums',
      render: (row) =>
        row.fromDate === row.toDate
          ? new Date(row.fromDate).toLocaleDateString()
          : `${new Date(row.fromDate).toLocaleDateString()} – ${new Date(row.toDate).toLocaleDateString()}`,
    },
    {
      key: 'count',
      header: 'Working days',
      className: 'tabular-nums',
      render: (row) =>
        row.totalDays === row.calendarDays
          ? `${row.totalDays}`
          : `${row.totalDays} of ${row.calendarDays}`,
    },
    {
      key: 'where',
      header: 'From',
      render: (row) =>
        WORK_LOCATIONS.find((option) => option.value === row.workLocation)?.label ??
        row.workLocation ??
        '—',
    },
    {
      key: 'applied',
      header: 'Waiting since',
      className: 'whitespace-nowrap tabular-nums',
      render: (row) => new Date(row.appliedAt).toLocaleDateString(),
    },
  ];

  const activeQuery = tab === 'corrections' ? corrections : wfh;
  const selected = selectedCorrection ?? selectedWfh;

  const tabs: { id: Tab; label: string; icon: string; count?: number; visible: boolean }[] = [
    {
      id: 'corrections',
      label: 'Corrections',
      icon: 'edit_calendar',
      count: correctionCount,
      visible: canDecideCorrections,
    },
    { id: 'wfh', label: 'Work from home', icon: 'home_work', count: wfhCount, visible: canDecideWfh },
  ];

  return (
    <div className="mx-auto max-w-[1280px]">
      <PageHeader
        title="Request Approvals"
        description="Attendance corrections and work-from-home days waiting on you. The API decides what you may act on — this page only shows it."
      />

      {decide.isError && (
        <Alert variant="error" title="Could not record your decision" className="mb-lg">
          {decide.error instanceof ApiError ? decide.error.message : 'Please try again.'}
        </Alert>
      )}

      <div
        className="mb-lg flex gap-xs border-b border-outline-variant"
        role="tablist"
        aria-label="Request type"
      >
        {tabs
          .filter((entry) => entry.visible)
          .map((entry) => (
            <button
              key={entry.id}
              type="button"
              role="tab"
              aria-selected={tab === entry.id}
              onClick={() => switchTab(entry.id)}
              className={`-mb-px flex items-center gap-xs border-b-2 px-md py-sm text-label-lg transition-colors ${
                tab === entry.id
                  ? 'border-primary text-primary'
                  : 'border-transparent text-on-surface-variant hover:text-on-surface'
              }`}
            >
              <Icon name={entry.icon} className="text-[18px]" />
              {entry.label}
              {entry.count != null && entry.count > 0 && (
                <span className="rounded-full bg-error px-2 py-0.5 text-body-sm tabular-nums text-on-error">
                  {entry.count}
                </span>
              )}
            </button>
          ))}
      </div>

      {tab === 'corrections' ? (
        <DataTable
          columns={correctionColumns}
          rows={corrections.data?.content ?? []}
          rowKey={(row) => row.id}
          loading={corrections.isLoading}
          error={
            corrections.error instanceof ApiError
              ? corrections.error.message
              : corrections.error
                ? 'Could not load corrections.'
                : null
          }
          emptyTitle="Nothing waiting on you"
          emptyMessage="Corrections needing your decision will appear here."
          onRowClick={(row) => {
            setSelectedCorrection(row);
            setComments('');
            setRejecting(false);
          }}
          page={corrections.data?.page ?? 0}
          size={size}
          totalElements={corrections.data?.totalElements ?? 0}
          totalPages={corrections.data?.totalPages ?? 0}
          onPageChange={setPage}
          onSizeChange={(next) => {
            setSize(next);
            setPage(0);
          }}
        />
      ) : (
        <DataTable
          columns={wfhColumns}
          rows={wfh.data?.content ?? []}
          rowKey={(row) => row.id}
          loading={wfh.isLoading}
          error={
            wfh.error instanceof ApiError
              ? wfh.error.message
              : wfh.error
                ? 'Could not load requests.'
                : null
          }
          emptyTitle="Nothing waiting on you"
          emptyMessage="Work-from-home requests needing your decision will appear here."
          onRowClick={(row) => {
            setSelectedWfh(row);
            setComments('');
            setRejecting(false);
          }}
          page={wfh.data?.page ?? 0}
          size={size}
          totalElements={wfh.data?.totalElements ?? 0}
          totalPages={wfh.data?.totalPages ?? 0}
          onPageChange={setPage}
          onSizeChange={(next) => {
            setSize(next);
            setPage(0);
          }}
        />
      )}

      {activeQuery.isFetching && !activeQuery.isLoading && (
        <p className="mt-sm text-body-sm text-on-surface-variant">Refreshing…</p>
      )}

      <Modal
        open={selected !== null}
        onClose={close}
        title={
          selectedCorrection
            ? `${selectedCorrection.employeeName} · correction`
            : selectedWfh
              ? `${selectedWfh.employeeName} · work from home`
              : ''
        }
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
                    decide.mutate({ id: selected.id, approve: false, kind: tab });
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
                onClick={() => decide.mutate({ id: selected.id, approve: true, kind: tab })}
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
        {selectedCorrection && (
          <div className="flex flex-col gap-lg">
            {/* The whole point of an approval screen: what the day holds now,
                beside what it would become. */}
            <div className="grid gap-md sm:grid-cols-2">
              <div className="rounded-DEFAULT bg-surface-container p-md">
                <p className="text-body-sm text-on-surface-variant">Recorded now</p>
                {selectedCorrection.creatingMissingDay ? (
                  <p className="mt-xs text-body-md text-on-surface">Nothing — the day is empty.</p>
                ) : (
                  <p className="mt-xs text-body-md tabular-nums text-on-surface">
                    {timeOnly(selectedCorrection.originalPunchInTime)} –{' '}
                    {timeOnly(selectedCorrection.originalPunchOutTime)}
                    <span className="ml-2 text-on-surface-variant">
                      ({selectedCorrection.originalWorkingMinutes ?? 0}m,{' '}
                      {selectedCorrection.originalStatus})
                    </span>
                  </p>
                )}
              </div>
              <div className="rounded-DEFAULT bg-primary-container p-md">
                <p className="text-body-sm text-on-primary-container">Would become</p>
                <p className="mt-xs text-body-md tabular-nums text-on-primary-container">
                  {timeOnly(selectedCorrection.requestedPunchInTime)} –{' '}
                  {timeOnly(selectedCorrection.requestedPunchOutTime)}
                  {selectedCorrection.requestedWorkMode && (
                    <span className="ml-2 opacity-80">({selectedCorrection.requestedWorkMode})</span>
                  )}
                </p>
              </div>
            </div>

            <dl className="grid gap-md sm:grid-cols-2">
              <div>
                <dt className="text-body-sm text-on-surface-variant">Date</dt>
                <dd className="text-body-md text-on-surface">
                  {new Date(selectedCorrection.attendanceDate).toLocaleDateString()} (
                  {selectedCorrection.dayOfWeek})
                </dd>
              </div>
              <div>
                <dt className="text-body-sm text-on-surface-variant">Reason type</dt>
                <dd className="text-body-md text-on-surface">
                  {REGULARIZATION_REASONS.find(
                    (option) => option.value === selectedCorrection.reasonType,
                  )?.label ?? selectedCorrection.reasonType}
                </dd>
              </div>
              <div className="sm:col-span-2">
                <dt className="text-body-sm text-on-surface-variant">Their explanation</dt>
                <dd className="text-body-md text-on-surface">{selectedCorrection.reason}</dd>
              </div>
            </dl>
          </div>
        )}

        {selectedWfh && (
          <div className="flex flex-col gap-lg">
            <dl className="grid gap-md sm:grid-cols-2">
              <div>
                <dt className="text-body-sm text-on-surface-variant">Dates</dt>
                <dd className="text-body-md tabular-nums text-on-surface">
                  {selectedWfh.fromDate} to {selectedWfh.toDate}
                </dd>
              </div>
              <div>
                <dt className="text-body-sm text-on-surface-variant">Working days</dt>
                <dd className="text-body-md tabular-nums text-on-surface">
                  {selectedWfh.totalDays} of {selectedWfh.calendarDays} calendar days
                </dd>
              </div>
              <div>
                <dt className="text-body-sm text-on-surface-variant">Working from</dt>
                <dd className="text-body-md text-on-surface">
                  {WORK_LOCATIONS.find((option) => option.value === selectedWfh.workLocation)
                    ?.label ?? selectedWfh.workLocation ?? '—'}
                </dd>
              </div>
              {selectedWfh.contactNumber && (
                <div>
                  <dt className="text-body-sm text-on-surface-variant">Reachable on</dt>
                  <dd className="text-body-md text-on-surface">{selectedWfh.contactNumber}</dd>
                </div>
              )}
              <div className="sm:col-span-2">
                <dt className="text-body-sm text-on-surface-variant">Their reason</dt>
                <dd className="text-body-md text-on-surface">{selectedWfh.reason}</dd>
              </div>
            </dl>

            {selectedWfh.days.length > 0 && (
              <div>
                <h3 className="mb-sm text-title-sm text-on-surface">Days covered</h3>
                <ul className="flex flex-wrap gap-xs">
                  {selectedWfh.days.map((day) => (
                    <li
                      key={day}
                      className="rounded-full bg-surface-container-high px-3 py-1 text-body-sm tabular-nums text-on-surface-variant"
                    >
                      {new Date(day).toLocaleDateString(undefined, {
                        weekday: 'short',
                        day: 'numeric',
                        month: 'short',
                      })}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            <Alert variant="warning" title="Approving lifts the geofence">
              On these days they can punch in from anywhere. Only the days listed above are covered.
            </Alert>
          </div>
        )}

        {selected && (
          <div className="mt-lg">
            <label htmlFor="request-decision-comments" className="field-label">
              {rejecting ? 'Reason for rejection (required)' : 'Comments (optional)'}
            </label>
            <textarea
              id="request-decision-comments"
              rows={3}
              maxLength={1000}
              value={comments}
              onChange={(event) => setComments(event.target.value)}
              className={`field-input resize-y ${
                rejecting && !comments.trim() ? 'field-input-error' : ''
              }`}
              placeholder={
                rejecting
                  ? 'They see this, so say what would change your mind.'
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
        )}
      </Modal>
    </div>
  );
};
