import { useEffect, useMemo, useState } from 'react';
import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { StatusChip } from '../../components/common/StatusChip';
import { SelectField } from '../../components/form/SelectField';
import { PERMISSIONS } from '../../constants/permissions';
import { useDepartments } from '../../features/admin/masterQueries';
import { useAuth } from '../../features/auth/useAuth';
import { reportApi } from '../../features/report/reportApi';
import { ApiError } from '../../types/api';
import { formatCell, isNumeric, type ReportColumn } from '../../types/report';

const isoToday = (): string => new Date().toISOString().slice(0, 10);
const startOfMonth = (): string => new Date().toISOString().slice(0, 8) + '01';

const STATUS_TONE: Record<string, 'success' | 'warning' | 'error' | 'info' | 'neutral'> = {
  PRESENT: 'success',
  WFH: 'info',
  APPROVED: 'success',
  PENDING: 'warning',
  REJECTED: 'error',
  ABSENT: 'error',
  ON_LEAVE: 'info',
  HALF_DAY: 'warning',
  WITHDRAWN: 'neutral',
  CANCELLED: 'neutral',
};

export const ReportsPage = () => {
  const { hasAnyPermission } = useAuth();
  const canExport = hasAnyPermission([PERMISSIONS.REPORT_EXPORT]);

  const [type, setType] = useState('DAILY_ATTENDANCE');
  const [fromDate, setFromDate] = useState(startOfMonth());
  const [toDate, setToDate] = useState(isoToday());
  const [departmentId, setDepartmentId] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);

  const catalogue = useQuery({
    queryKey: ['reports', 'catalogue'],
    queryFn: reportApi.catalogue,
  });

  const departments = useDepartments();

  const selected = catalogue.data?.find((entry) => entry.type === type);

  const params = useMemo(
    () => ({
      fromDate,
      toDate,
      departmentId: departmentId ? Number(departmentId) : undefined,
      page,
      size,
    }),
    [fromDate, toDate, departmentId, page, size],
  );

  const report = useQuery({
    queryKey: ['reports', type, params],
    queryFn: () => reportApi.run(type, params),
    placeholderData: keepPreviousData,
    // These reports name one person; running them for everyone would be a
    // different report, so the picker asks first.
    enabled: !selected?.requiresEmployee,
  });

  const download = useMutation({
    mutationFn: (format: string) => reportApi.download(type, format, { ...params, page: undefined, size: undefined }),
  });

  useEffect(() => {
    setPage(0);
  }, [type, fromDate, toDate, departmentId]);

  const columns: ReportColumn[] = report.data?.columns ?? [];

  return (
    <div className="mx-auto max-w-[1280px]">
      <PageHeader
        title="Reports"
        description={
          canExport
            ? 'The table and the download come from the same query, so they can never disagree.'
            : 'Scoped to the people you manage. The API decides that, not this page.'
        }
      />

      <section className="card mb-lg p-lg">
        <div className="grid gap-md sm:grid-cols-2 lg:grid-cols-4">
          <SelectField
            label="Report"
            value={type}
            onChange={(event) => setType(event.target.value)}
            options={(catalogue.data ?? []).map((entry) => ({
              value: entry.type,
              label: entry.title,
            }))}
          />
          <div className="flex flex-col">
            <label htmlFor="report-from" className="field-label">From</label>
            <input
              id="report-from"
              type="date"
              value={fromDate}
              max={toDate}
              onChange={(event) => setFromDate(event.target.value)}
              className="field-input tabular-nums"
            />
          </div>
          <div className="flex flex-col">
            <label htmlFor="report-to" className="field-label">To</label>
            <input
              id="report-to"
              type="date"
              value={toDate}
              min={fromDate}
              max={isoToday()}
              onChange={(event) => setToDate(event.target.value)}
              className="field-input tabular-nums"
            />
          </div>
          <SelectField
            label="Department"
            placeholder="All departments"
            value={departmentId}
            onChange={(event) => setDepartmentId(event.target.value)}
            options={(departments.data ?? []).map((department) => ({
              value: String(department.id),
              label: department.name,
            }))}
          />
        </div>

        {selected && (
          <p className="mt-md text-body-sm text-on-surface-variant">{selected.description}</p>
        )}

        {canExport && (
          <div className="mt-lg flex flex-wrap items-center gap-sm">
            <span className="text-body-sm text-on-surface-variant">Download</span>
            {(['CSV', 'EXCEL', 'PDF'] as const).map((format) => (
              <button
                key={format}
                type="button"
                className="btn-secondary"
                disabled={download.isPending || !report.data}
                onClick={() => download.mutate(format)}
              >
                {download.isPending ? (
                  <Spinner className="h-4 w-4" />
                ) : (
                  <Icon
                    name={format === 'PDF' ? 'picture_as_pdf' : format === 'EXCEL' ? 'table_view' : 'description'}
                    className="text-[18px]"
                  />
                )}
                {format === 'EXCEL' ? 'Excel' : format}
              </button>
            ))}
            {report.data && (
              <span className="text-body-sm text-on-surface-variant">
                the whole report, not just this page
              </span>
            )}
          </div>
        )}
      </section>

      {selected?.requiresEmployee && (
        <Alert variant="info" title="Pick a person" className="mb-lg">
          {selected.title} describes one employee. Open someone from the directory and use their
          attendance tab, or choose a different report here.
        </Alert>
      )}

      {report.isError && (
        <Alert variant="error" title="Could not run the report" className="mb-lg">
          {report.error instanceof ApiError ? report.error.message : 'Please try again.'}
        </Alert>
      )}

      {download.isError && (
        <Alert variant="error" title="Could not download" className="mb-lg">
          {download.error instanceof ApiError ? download.error.message : 'Please try again.'}
        </Alert>
      )}

      {report.data && (
        <section>
          <div className="mb-md flex flex-wrap items-baseline justify-between gap-sm">
            <h2 className="text-title-md text-on-surface">
              {report.data.title}
              <span className="ml-sm text-body-sm font-normal text-on-surface-variant">
                {report.data.totalRows} row{report.data.totalRows === 1 ? '' : 's'}
                {report.data.scope === 'TEAM' ? ' · your team' : ' · organisation'}
              </span>
            </h2>
            <span className="text-body-sm text-on-surface-variant">
              {new Date(report.data.fromDate).toLocaleDateString()} –{' '}
              {new Date(report.data.toDate).toLocaleDateString()}
            </span>
          </div>

          <div className="card overflow-x-auto">
            <table className="w-full min-w-[720px] border-collapse text-body-md">
              <thead>
                <tr className="border-b border-outline-variant bg-surface-container-low">
                  {columns.map((column) => (
                    <th
                      key={column.key}
                      scope="col"
                      className={`whitespace-nowrap px-md py-sm text-label-md uppercase tracking-wide text-on-surface-variant ${
                        isNumeric(column.type) ? 'text-right' : 'text-left'
                      }`}
                    >
                      {column.label}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {report.data.rows.length === 0 && (
                  <tr>
                    <td colSpan={columns.length} className="px-md py-xl text-center">
                      <Icon name="inbox" className="text-[28px] text-outline" />
                      <p className="mt-xs text-body-md text-on-surface">Nothing in this range</p>
                      <p className="text-body-sm text-on-surface-variant">
                        Try widening the dates, or clearing the department filter.
                      </p>
                    </td>
                  </tr>
                )}

                {report.data.rows.map((row, index) => (
                  <tr
                    key={index}
                    className="border-b border-outline-variant last:border-b-0 hover:bg-surface-container-low"
                  >
                    {columns.map((column) => {
                      const value = row[column.key];
                      return (
                        <td
                          key={column.key}
                          className={`whitespace-nowrap px-md py-sm ${
                            isNumeric(column.type)
                              ? 'text-right tabular-nums text-on-surface'
                              : 'text-on-surface'
                          }`}
                        >
                          {column.type === 'STATUS' && value ? (
                            <StatusChip
                              label={String(value)}
                              tone={STATUS_TONE[String(value)] ?? 'neutral'}
                            />
                          ) : (
                            formatCell(value, column.type)
                          )}
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {report.data.totalPages > 1 && (
            <div className="mt-md flex flex-wrap items-center justify-between gap-md">
              <label className="flex items-center gap-sm text-body-sm text-on-surface-variant">
                Rows
                <select
                  value={size}
                  onChange={(event) => {
                    setSize(Number(event.target.value));
                    setPage(0);
                  }}
                  className="field-input w-auto py-1"
                >
                  {[25, 50, 100, 200].map((option) => (
                    <option key={option} value={option}>{option}</option>
                  ))}
                </select>
              </label>

              <div className="flex items-center gap-md">
                <button
                  type="button"
                  className="btn-secondary"
                  disabled={report.data.page === 0}
                  onClick={() => setPage((current) => Math.max(0, current - 1))}
                >
                  <Icon name="chevron_left" className="text-[18px]" />
                  Previous
                </button>
                <span className="text-body-sm tabular-nums text-on-surface-variant">
                  {report.data.page + 1} / {report.data.totalPages}
                </span>
                <button
                  type="button"
                  className="btn-secondary"
                  disabled={report.data.page + 1 >= report.data.totalPages}
                  onClick={() => setPage((current) => current + 1)}
                >
                  Next
                  <Icon name="chevron_right" className="text-[18px]" />
                </button>
              </div>
            </div>
          )}
        </section>
      )}
    </div>
  );
};
