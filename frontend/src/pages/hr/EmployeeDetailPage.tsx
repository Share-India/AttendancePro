import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { PageHeader } from '../../components/common/PageHeader';
import { Modal } from '../../components/common/Modal';
import { Spinner } from '../../components/common/Spinner';
import { SelectField } from '../../components/form/SelectField';
import { StatusChip, employmentStatusTone } from '../../components/common/StatusChip';
import { PERMISSIONS } from '../../constants/permissions';
import { ROUTES } from '../../constants/routes';
import { employeeApi } from '../../features/employee/employeeApi';
import { useAuthStore } from '../../store/authStore';
import { ApiError } from '../../types/api';
import type { EmployeeDetail } from '../../types/employee';

const Field = ({ label, value }: { label: string; value: string | null | undefined }) => (
  <div>
    <dt className="text-body-sm text-on-surface-variant">{label}</dt>
    <dd className="mt-0.5 text-body-md text-on-surface">{value || <span className="text-outline">—</span>}</dd>
  </div>
);

export const EmployeeDetailPage = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const hasPermission = useAuthStore((state) => state.hasPermission);

  const employeeId = Number(id);

  const { data, isLoading, error } = useQuery({
    queryKey: ['employees', 'detail', id],
    queryFn: () => employeeApi.findById(employeeId),
  });

  const [managerOpen, setManagerOpen] = useState(false);
  const [managerId, setManagerId] = useState('');
  const [effectiveFrom, setEffectiveFrom] = useState(new Date().toISOString().slice(0, 10));

  /**
   * Candidates, fetched only while the dialog is open.
   *
   * <p>A reorg is rare; loading two hundred employees every time someone opens
   * a profile to read a phone number would not be.
   */
  const { data: candidates } = useQuery({
    queryKey: ['employees', 'manager-options'],
    queryFn: () => employeeApi.search({ page: 0, size: 200, sort: 'firstName,asc' }),
    enabled: managerOpen,
    staleTime: 5 * 60 * 1000,
  });

  const assignManager = useMutation({
    mutationFn: () =>
      employeeApi.assignManager(employeeId, {
        managerEmployeeId: Number(managerId),
        relationshipType: 'PRIMARY',
        effectiveFrom,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['employees'] });
      // The team dashboard and every approval inbox are scoped by the
      // reporting tree, so they are stale the moment this changes.
      queryClient.invalidateQueries({ queryKey: ['dashboard'] });
      setManagerOpen(false);
    },
  });

  const lifecycle = useMutation({
    mutationFn: (action: 'deactivate' | 'reactivate') =>
      action === 'deactivate'
        ? employeeApi.deactivate(employeeId)
        : employeeApi.reactivate(employeeId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['employees'] });
    },
  });

  if (isLoading) {
    return (
      <div className="flex justify-center py-xl">
        <Spinner className="h-8 w-8 text-primary" />
      </div>
    );
  }

  if (error || !data) {
    const message =
      error instanceof ApiError && error.errorCode === 'ACCESS_DENIED'
        ? 'You do not have access to this employee record.'
        : error instanceof ApiError
          ? error.message
          : 'Could not load this employee.';

    return (
      <div className="mx-auto max-w-2xl">
        <Alert variant="error" title="Unavailable">
          {message}
        </Alert>
        <button type="button" className="btn-secondary mt-lg" onClick={() => navigate(ROUTES.employees)}>
          <Icon name="arrow_back" className="text-[18px]" />
          Back to directory
        </button>
      </div>
    );
  }

  const employee: EmployeeDetail = data;
  const canEdit = hasPermission(PERMISSIONS.EMPLOYEE_VIEW_ALL);
  // The same authority the API enforces on PUT /employees/{id}/manager.
  // Gating on a view permission would offer a button that then fails.
  const canManage = hasPermission(PERMISSIONS.EMPLOYEE_UPDATE);

  return (
    <div className="mx-auto max-w-[1280px]">
      <PageHeader
        title={employee.fullName}
        description={`${employee.employeeCode} · ${employee.designationName ?? 'No designation'}`}
        actions={
          canEdit ? (
            <>
              <button
                type="button"
                className="btn-secondary"
                onClick={() => navigate(ROUTES.employeeAttendance(employee.id))}
              >
                <Icon name="schedule" className="text-[18px]" />
                Attendance
              </button>
              <button
                type="button"
                className="btn-secondary"
                onClick={() => navigate(ROUTES.employeeEdit(employee.id))}
              >
                <Icon name="edit" className="text-[18px]" />
                Edit
              </button>
              {employee.active ? (
                <button
                  type="button"
                  className="btn-secondary text-error"
                  onClick={() => lifecycle.mutate('deactivate')}
                  disabled={lifecycle.isPending}
                >
                  <Icon name="person_off" className="text-[18px]" />
                  Deactivate
                </button>
              ) : (
                <button
                  type="button"
                  className="btn-primary"
                  onClick={() => lifecycle.mutate('reactivate')}
                  disabled={lifecycle.isPending}
                >
                  <Icon name="person_check" className="text-[18px]" />
                  Reactivate
                </button>
              )}
            </>
          ) : null
        }
      />

      {lifecycle.isError && (
        <Alert variant="error" title="Could not change status" className="mb-lg">
          {lifecycle.error instanceof ApiError
            ? lifecycle.error.message
            : 'Something went wrong. Please try again.'}
        </Alert>
      )}

      <div className="grid gap-gutter lg:grid-cols-3">
        <section className="card p-lg lg:col-span-2">
          <div className="mb-lg flex items-center gap-md">
            {employee.profilePhotoUrl ? (
              <img
                src={employee.profilePhotoUrl}
                alt=""
                className="h-16 w-16 rounded-full object-cover"
              />
            ) : (
              <span
                className="flex h-16 w-16 items-center justify-center rounded-full bg-primary-fixed text-headline-lg text-on-primary-fixed"
                aria-hidden="true"
              >
                {employee.fullName.charAt(0).toUpperCase()}
              </span>
            )}
            <div className="min-w-0">
              <p className="truncate text-headline-md text-on-surface">{employee.fullName}</p>
              <div className="mt-xs flex flex-wrap gap-2">
                <StatusChip
                  label={employee.employmentStatus.replace('_', ' ')}
                  tone={employmentStatusTone(employee.employmentStatus)}
                />
                <StatusChip label={employee.employmentType} tone="info" />
                {employee.userLocked && <StatusChip label="Login locked" tone="error" />}
                {!employee.userActive && <StatusChip label="Login disabled" tone="neutral" />}
              </div>
            </div>
          </div>

          <dl className="grid gap-md sm:grid-cols-2">
            <Field label="Employee ID" value={employee.employeeCode} />
            <Field label="Official email" value={employee.officialEmail} />
            <Field label="Personal email" value={employee.personalEmail} />
            <Field label="Mobile" value={employee.mobile} />
            <Field label="Date of birth" value={employee.dateOfBirth} />
            <Field label="Gender" value={employee.gender} />
            <Field label="Department" value={employee.departmentName} />
            <Field label="Designation" value={employee.designationName} />
            <Field label="Office" value={employee.officeName} />
            <Field label="Office timezone" value={employee.officeTimezone} />
            <Field label="Joining date" value={employee.joiningDate} />
            <Field label="Confirmation date" value={employee.confirmationDate} />
            <div>
              <dt className="text-body-sm text-on-surface-variant">Reporting manager</dt>
              <dd className="mt-0.5 flex flex-wrap items-center gap-sm text-body-md text-on-surface">
                {employee.reportingManagerName || (
                  <span className="text-error">Nobody — their requests reach no approver</span>
                )}
                {canManage && (
                  <button
                    type="button"
                    onClick={() => {
                      setManagerId('');
                      setEffectiveFrom(new Date().toISOString().slice(0, 10));
                      setManagerOpen(true);
                    }}
                    className="text-body-sm text-primary underline underline-offset-2"
                  >
                    {employee.reportingManagerName ? 'Change' : 'Set one'}
                  </button>
                )}
              </dd>
            </div>
            <Field label="Exit date" value={employee.exitDate} />
            <Field label="Emergency contact" value={employee.emergencyContactName} />
            <Field label="Emergency number" value={employee.emergencyContactNumber} />
          </dl>
        </section>

        <div className="flex flex-col gap-gutter">
          <section className="card p-lg">
            <h2 className="mb-md flex items-center gap-2 text-headline-md text-on-surface">
              <Icon name="schedule" className="text-[20px] text-primary" />
              Current shift
            </h2>
            {employee.currentShift ? (
              <div>
                <p className="text-body-lg text-on-surface">{employee.currentShift.shiftName}</p>
                <p className="mt-unit font-mono text-body-md tabular-nums text-on-surface-variant">
                  {employee.currentShift.startTime} – {employee.currentShift.endTime}
                </p>
                <p className="mt-sm text-body-sm text-on-surface-variant">
                  Effective from {employee.currentShift.effectiveFrom}
                  {employee.currentShift.effectiveTo && ` to ${employee.currentShift.effectiveTo}`}
                </p>
              </div>
            ) : (
              <Alert variant="warning">
                No shift assigned. Attendance cannot be computed until one is set.
              </Alert>
            )}
          </section>

          <section className="card p-lg">
            <h2 className="mb-md flex items-center gap-2 text-headline-md text-on-surface">
              <Icon name="badge" className="text-[20px] text-primary" />
              Access
            </h2>
            <dl className="space-y-md">
              <Field label="Username" value={employee.username} />
              <div>
                <dt className="text-body-sm text-on-surface-variant">Roles</dt>
                <dd className="mt-xs flex flex-wrap gap-1">
                  {employee.roles.length > 0 ? (
                    employee.roles.map((role) => (
                      <StatusChip key={role} label={role.replace('_', ' ')} tone="info" />
                    ))
                  ) : (
                    <span className="text-outline">—</span>
                  )}
                </dd>
              </div>
            </dl>
          </section>

          {employee.shiftHistory.length > 1 && (
            <section className="card p-lg">
              <h2 className="mb-md text-headline-md text-on-surface">Shift history</h2>
              <ol className="space-y-sm">
                {employee.shiftHistory.map((assignment) => (
                  <li
                    key={assignment.id}
                    className="flex items-baseline justify-between gap-md border-b border-outline-variant/40 pb-sm last:border-b-0 last:pb-0"
                  >
                    <span className="text-body-md text-on-surface">{assignment.shiftName}</span>
                    <span className="whitespace-nowrap font-mono text-body-sm tabular-nums text-on-surface-variant">
                      {assignment.effectiveFrom} → {assignment.effectiveTo ?? 'open'}
                    </span>
                  </li>
                ))}
              </ol>
            </section>
          )}
        </div>
      </div>

      <Modal
        open={managerOpen}
        onClose={() => setManagerOpen(false)}
        title={`Reporting manager for ${employee.fullName}`}
        description="Recorded with an effective date, so history survives a re-org."
        footer={
          <>
            <button type="button" className="btn-secondary" onClick={() => setManagerOpen(false)}>
              Cancel
            </button>
            <button
              type="button"
              className="btn-primary"
              disabled={!managerId || assignManager.isPending}
              onClick={() => assignManager.mutate()}
            >
              {assignManager.isPending ? (
                <Spinner className="h-4 w-4" />
              ) : (
                <Icon name="check" className="text-[18px]" />
              )}
              Save
            </button>
          </>
        }
      >
        <div className="flex flex-col gap-md">
          {assignManager.isError && (
            <Alert variant="error" title="Could not change the manager">
              {assignManager.error instanceof ApiError
                ? assignManager.error.message
                : 'Please try again.'}
            </Alert>
          )}

          <SelectField
            label="Manager"
            placeholder="Choose someone"
            value={managerId}
            onChange={(event) => setManagerId(event.target.value)}
            options={(candidates?.content ?? [])
              // Nobody reports to themselves, and the API refuses it anyway.
              .filter((candidate) => candidate.id !== employee.id)
              .map((candidate) => ({
                value: candidate.id,
                label: `${candidate.fullName} (${candidate.employeeCode})`,
              }))}
          />

          <div className="flex flex-col">
            <label htmlFor="manager-effective-from" className="field-label">
              Effective from
            </label>
            <input
              id="manager-effective-from"
              type="date"
              value={effectiveFrom}
              onChange={(event) => setEffectiveFrom(event.target.value)}
              className="field-input tabular-nums"
            />
            <p className="mt-1 text-body-sm text-on-surface-variant">
              Approvals already decided keep the manager who decided them. This applies from the
              date you choose onwards.
            </p>
          </div>
        </div>
      </Modal>
    </div>
  );
};
