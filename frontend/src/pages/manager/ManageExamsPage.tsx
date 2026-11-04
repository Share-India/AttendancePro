import { useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';

import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { Modal } from '../../components/common/Modal';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { StatusChip } from '../../components/common/StatusChip';
import { SelectField } from '../../components/form/SelectField';
import { DataTable, type Column } from '../../components/table/DataTable';
import { ROUTES } from '../../constants/routes';
import { examApi } from '../../features/exam/examApi';
import { ApiError } from '../../types/api';
import { EXAM_STATUS_LABEL, EXAM_STATUS_TONE, type ExamSummary } from '../../types/exam';

const STATUS_OPTIONS = [
  { value: '', label: 'All statuses' },
  { value: 'DRAFT', label: 'Draft' },
  { value: 'QUESTIONS_LOADED', label: 'Questions loaded' },
  { value: 'PUBLISHED', label: 'Published' },
  { value: 'OPEN', label: 'Open now' },
  { value: 'CLOSED', label: 'Closed' },
  { value: 'CANCELLED', label: 'Cancelled' },
];

const describeFailure = (error: unknown): { title: string; message: string } => {
  if (!(error instanceof ApiError)) {
    return { title: 'That did not work', message: 'Something went wrong. Try again.' };
  }
  switch (error.errorCode) {
    case 'POLICY_VIOLATION':
      return { title: 'Not ready for that', message: error.message };
    case 'VALIDATION_ERROR':
      return { title: 'Check the details', message: error.message };
    case 'ACCESS_DENIED':
      return { title: 'Not yours to manage', message: error.message };
    default:
      return { title: 'That did not work', message: error.message };
  }
};

const whenLabel = (iso: string): string =>
  new Date(iso).toLocaleString(undefined, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  });

export const ManageExamsPage = () => {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [status, setStatus] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState<ExamSummary | null>(null);
  const [reason, setReason] = useState('');

  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const { data, isLoading, error } = useQuery({
    queryKey: ['exam', 'manage', page, size, status],
    queryFn: () => examApi.list({ page, size, status: status || undefined }),
    placeholderData: keepPreviousData,
  });

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['exam'] });

  const publish = useMutation({
    mutationFn: (id: number) => examApi.publish(id),
    onSuccess: (result) => {
      const split = Object.entries(result.perSet)
        .map(([set, count]) => `${set}: ${count}`)
        .join(', ');
      setNotice(
        `${result.message} Split — ${split}.` +
          (result.testerCount > 0
            ? ` ${result.testerCount} of them set the paper and will be kept out of the figures.`
            : ''),
      );
      refresh();
    },
  });

  const cancel = useMutation({
    mutationFn: (input: { id: number; reason: string }) => examApi.cancel(input.id, input.reason),
    onSuccess: () => {
      setNotice('Exam cancelled, and everybody who was expecting it has been told.');
      setCancelling(null);
      setReason('');
      refresh();
    },
  });

  const busy = publish.isPending || cancel.isPending;

  const columns: Column<ExamSummary>[] = [
    {
      key: 'title',
      header: 'Exam',
      render: (row) => (
        <div className="min-w-0">
          <Link
            to={ROUTES.examDetail(row.id)}
            className="block truncate font-medium text-primary hover:underline"
          >
            {row.title}
          </Link>
          <p className="truncate text-body-sm text-on-surface-variant">
            {row.examCode}
            {row.retake ? ' · retake' : ''}
          </p>
        </div>
      ),
    },
    {
      key: 'window',
      header: 'Opens',
      render: (row) => (
        <span className="whitespace-nowrap tabular-nums">{whenLabel(row.windowStart)}</span>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <StatusChip
          label={EXAM_STATUS_LABEL[row.status] ?? row.status}
          tone={EXAM_STATUS_TONE[row.status] ?? 'neutral'}
        />
      ),
    },
    {
      key: 'paper',
      header: 'Paper',
      render: (row) =>
        row.setCount === 0 ? (
          <span className="text-on-surface-variant">No questions</span>
        ) : (
          <span className="tabular-nums">
            {row.setCount} sets · {row.questionCount} questions
          </span>
        ),
    },
    {
      key: 'sat',
      header: 'Sat it',
      render: (row) =>
        row.rosterSize === 0 ? (
          <span className="text-on-surface-variant">Not published</span>
        ) : (
          <span className="tabular-nums">
            {row.submittedCount} of {row.rosterSize}
          </span>
        ),
    },
    {
      key: 'actions',
      header: '',
      className: 'text-right',
      headerClassName: 'text-right',
      render: (row) => (
        <div className="flex justify-end gap-2">
          {row.status === 'QUESTIONS_LOADED' ? (
            <button
              type="button"
              className="btn-primary"
              disabled={busy}
              onClick={() => {
                setNotice(null);
                publish.mutate(row.id);
              }}
            >
              Publish
            </button>
          ) : null}
          {row.status === 'DRAFT' || row.status === 'QUESTIONS_LOADED' ? (
            <button
              type="button"
              className="btn-ghost"
              onClick={() => navigate(ROUTES.examEdit(row.id))}
            >
              Edit
            </button>
          ) : null}
          {row.status !== 'CANCELLED' && row.status !== 'CLOSED' ? (
            <button
              type="button"
              className="btn-ghost"
              onClick={() => {
                setNotice(null);
                setCancelling(row);
              }}
            >
              Cancel
            </button>
          ) : null}
          <Link to={ROUTES.examDetail(row.id)} className="btn-ghost">
            Open
          </Link>
        </div>
      ),
    },
  ];

  const mutationError = publish.error ?? cancel.error;
  const failure = mutationError ? describeFailure(mutationError) : null;

  return (
    <div className="flex flex-col gap-lg">
      <PageHeader
        title="Exams"
        description="Create an exam, upload the three question sets, then publish to deal the papers."
        actions={
          <Link to={ROUTES.examNew} className="btn-primary inline-flex items-center gap-2">
            <Icon name="add" />
            New exam
          </Link>
        }
      />

      {notice ? <Alert variant="success">{notice}</Alert> : null}
      {failure ? (
        <Alert variant="error" title={failure.title}>
          {failure.message}
        </Alert>
      ) : null}

      <div className="max-w-xs">
        <SelectField
          label="Status"
          options={STATUS_OPTIONS}
          value={status}
          onChange={(event) => {
            setStatus(event.target.value);
            setPage(0);
          }}
        />
      </div>

      <DataTable
        columns={columns}
        rows={data?.content ?? []}
        rowKey={(row) => row.id}
        loading={isLoading}
        error={error instanceof ApiError ? error.message : error ? 'Could not load exams.' : null}
        emptyTitle="No exams"
        emptyMessage="Create one, upload the workbook, and publish it to your teams."
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
        open={cancelling !== null}
        onClose={() => setCancelling(null)}
        title="Cancel this exam?"
        description={cancelling ? cancelling.title : undefined}
        footer={
          <div className="flex justify-end gap-sm">
            <button type="button" className="btn-ghost" onClick={() => setCancelling(null)}>
              Keep it
            </button>
            <button
              type="button"
              className="btn-primary inline-flex items-center gap-2"
              disabled={!reason.trim() || cancel.isPending}
              onClick={() =>
                cancelling && cancel.mutate({ id: cancelling.id, reason: reason.trim() })
              }
            >
              {cancel.isPending ? <Spinner className="h-4 w-4" /> : null}
              Cancel exam
            </button>
          </div>
        }
      >
        <label className="field-label" htmlFor="cancel-reason">
          Why?
        </label>
        <textarea
          id="cancel-reason"
          className="field-input"
          rows={3}
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          placeholder="Everybody who was expecting it will see this."
        />
      </Modal>
    </div>
  );
};
