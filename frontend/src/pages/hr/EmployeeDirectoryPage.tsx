import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Icon } from '../../components/common/Icon';
import { PageHeader } from '../../components/common/PageHeader';
import { StatusChip, employmentStatusTone } from '../../components/common/StatusChip';
import { DataTable, type Column } from '../../components/table/DataTable';
import { PERMISSIONS } from '../../constants/permissions';
import { ROUTES } from '../../constants/routes';
import { useDepartments, useOffices } from '../../features/admin/masterQueries';
import { employeeApi } from '../../features/employee/employeeApi';
import { useDebounce } from '../../hooks/useDebounce';
import { useAuthStore } from '../../store/authStore';
import { ApiError } from '../../types/api';
import { EMPLOYMENT_STATUSES, type EmployeeSummary } from '../../types/employee';

export const EmployeeDirectoryPage = () => {
  const navigate = useNavigate();
  const hasPermission = useAuthStore((state) => state.hasPermission);

  const [search, setSearch] = useState('');
  const [departmentId, setDepartmentId] = useState<string>('');
  const [officeId, setOfficeId] = useState<string>('');
  const [status, setStatus] = useState<string>('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [sort, setSort] = useState('employeeCode,asc');

  // Typing should not fire a request per keystroke.
  const debouncedSearch = useDebounce(search, 350);

  const { data: departments } = useDepartments();
  const { data: offices } = useOffices();

  const params = useMemo(
    () => ({
      search: debouncedSearch || undefined,
      departmentId: departmentId ? Number(departmentId) : undefined,
      officeId: officeId ? Number(officeId) : undefined,
      employmentStatus: status || undefined,
      page,
      size,
      sort,
    }),
    [debouncedSearch, departmentId, officeId, status, page, size, sort],
  );

  const { data, isLoading, error } = useQuery({
    queryKey: ['employees', 'search', params],
    queryFn: () => employeeApi.search(params),
    // Keeps the previous page visible while the next one loads, instead of
    // collapsing the table to a spinner on every page change.
    placeholderData: keepPreviousData,
  });

  const columns: Column<EmployeeSummary>[] = [
    {
      key: 'employee',
      header: 'Employee',
      sortKey: 'employeeCode',
      render: (row) => (
        <div className="flex items-center gap-sm">
          {row.profilePhotoUrl ? (
            <img src={row.profilePhotoUrl} alt="" className="h-9 w-9 rounded-full object-cover" />
          ) : (
            <span
              className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary-fixed text-body-sm font-semibold text-on-primary-fixed"
              aria-hidden="true"
            >
              {row.fullName.charAt(0).toUpperCase()}
            </span>
          )}
          <div className="min-w-0">
            <p className="truncate font-medium text-on-surface">{row.fullName}</p>
            <p className="truncate font-mono text-body-sm text-on-surface-variant">
              {row.employeeCode}
            </p>
          </div>
        </div>
      ),
    },
    {
      key: 'email',
      header: 'Email',
      sortKey: 'officialEmail',
      render: (row) => <span className="truncate text-on-surface-variant">{row.officialEmail}</span>,
    },
    {
      key: 'department',
      header: 'Department',
      render: (row) => row.department ?? <span className="text-outline">—</span>,
    },
    {
      key: 'designation',
      header: 'Designation',
      render: (row) => row.designation ?? <span className="text-outline">—</span>,
    },
    {
      key: 'office',
      header: 'Office',
      render: (row) => row.office ?? <span className="text-outline">—</span>,
    },
    {
      key: 'joiningDate',
      header: 'Joined',
      sortKey: 'joiningDate',
      className: 'tabular-nums whitespace-nowrap',
      render: (row) => new Date(row.joiningDate).toLocaleDateString(),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <StatusChip
          label={row.employmentStatus.replace('_', ' ')}
          tone={employmentStatusTone(row.employmentStatus)}
        />
      ),
    },
  ];

  const resetToFirstPage = <T,>(setter: (value: T) => void) => (value: T) => {
    setter(value);
    setPage(0);
  };

  return (
    <div className="mx-auto max-w-[1280px]">
      <PageHeader
        title="Employees"
        description="Everyone you have access to. Managers see their team; HR sees the organisation."
        actions={
          hasPermission(PERMISSIONS.EMPLOYEE_VIEW_ALL) ? (
            <>
              <button type="button" className="btn-secondary" onClick={() => navigate(ROUTES.employeeImport)}>
                <Icon name="upload_file" className="text-[18px]" />
                Bulk import
              </button>
              <button type="button" className="btn-primary" onClick={() => navigate(ROUTES.employeeNew)}>
                <Icon name="person_add" className="text-[18px]" />
                Add employee
              </button>
            </>
          ) : null
        }
      />

      <div className="card mb-lg flex flex-col gap-md p-md lg:flex-row lg:items-end">
        <div className="flex-1">
          <label htmlFor="employee-search" className="field-label">
            Search
          </label>
          <div className="relative">
            <span className="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3 text-outline">
              <Icon name="search" className="text-[20px]" />
            </span>
            <input
              id="employee-search"
              type="search"
              value={search}
              onChange={(event) => resetToFirstPage(setSearch)(event.target.value)}
              placeholder="Name, employee ID or email"
              className="field-input pl-10"
            />
          </div>
        </div>

        <div className="grid flex-1 grid-cols-1 gap-md sm:grid-cols-3">
          <div>
            <label htmlFor="filter-department" className="field-label">
              Department
            </label>
            <select
              id="filter-department"
              value={departmentId}
              onChange={(event) => resetToFirstPage(setDepartmentId)(event.target.value)}
              className="field-input"
            >
              <option value="">All departments</option>
              {departments?.map((department) => (
                <option key={department.id} value={department.id}>
                  {department.name}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label htmlFor="filter-office" className="field-label">
              Office
            </label>
            <select
              id="filter-office"
              value={officeId}
              onChange={(event) => resetToFirstPage(setOfficeId)(event.target.value)}
              className="field-input"
            >
              <option value="">All offices</option>
              {offices?.map((office) => (
                <option key={office.id} value={office.id}>
                  {office.name}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label htmlFor="filter-status" className="field-label">
              Status
            </label>
            <select
              id="filter-status"
              value={status}
              onChange={(event) => resetToFirstPage(setStatus)(event.target.value)}
              className="field-input"
            >
              <option value="">All statuses</option>
              {EMPLOYMENT_STATUSES.map((option) => (
                <option key={option} value={option}>
                  {option.replace('_', ' ')}
                </option>
              ))}
            </select>
          </div>
        </div>
      </div>

      <DataTable
        columns={columns}
        rows={data?.content ?? []}
        rowKey={(row) => row.id}
        loading={isLoading}
        error={error instanceof ApiError ? error.message : error ? 'Could not load employees.' : null}
        emptyTitle="No employees found"
        emptyMessage="Try clearing a filter or searching for something else."
        onRowClick={(row) => navigate(ROUTES.employeeDetail(row.id))}
        page={data?.page ?? 0}
        size={size}
        totalElements={data?.totalElements ?? 0}
        totalPages={data?.totalPages ?? 0}
        onPageChange={setPage}
        onSizeChange={(next) => {
          setSize(next);
          setPage(0);
        }}
        sort={sort}
        onSortChange={(next) => {
          setSort(next);
          setPage(0);
        }}
      />
    </div>
  );
};
