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
import { useShifts } from '../../features/admin/masterQueries';
import { employeeApi } from '../../features/employee/employeeApi';
import { useAuthStore } from '../../store/authStore';
import { ApiError } from '../../types/api';
import type { EmployeeDetail, ResetMethod } from '../../types/employee';

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
  const { data: shifts } = useShifts();

  const employeeId = Number(id);

  const { data, isLoading, error } = useQuery({
    queryKey: ['employees', 'detail', id],
    queryFn: () => employeeApi.findById(employeeId),
  });

  const [managerOpen, setManagerOpen] = useState(false);
  const [resetOpen, setResetOpen] = useState(false);
  const [shiftOpen, setShiftOpen] = useState(false);
  const [shiftId, setShiftId] = useState('');
  const [shiftFrom, setShiftFrom] = useState(new Date().toISOString().slice(0, 10));
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

  // Deliberately no onSuccess that closes the dialog. Unlike every other
  // mutation on this page, success is not the end of the interaction: the
  // response carries the one and only readable copy of the password, and the
  // dialog has to stay open to show it. Closing here loses it for good.
  const assignShift = useMutation({
    mutationFn: () =>
      employeeApi.assignShift(employeeId, {
        shiftId: Number(shiftId),
        effectiveFrom: shiftFrom,
      }),
    onSuccess: () => {
      // The record carries currentShift and shiftHistory, and attendance is
      // computed against the shift, so both are stale the moment this lands.
      queryClient.invalidateQueries({ queryKey: ['employees'] });
      queryClient.invalidateQueries({ queryKey: ['attendance'] });
      setShiftOpen(false);
    },
  });

  const resetPassword = useMutation({
    mutationFn: (method: ResetMethod) => employeeApi.resetPassword(employeeId, method),
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
  const canResetPassword = hasPermission(PERMISSIONS.USER_PASSWORD_RESET);
  // The same authority the API enforces on PUT /employees/{id}/shift.
  const canAssignShift = hasPermission(PERMISSIONS.SHIFT_ASSIGN);

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
              {canResetPassword && (
                <button
                  type="button"
                  className="btn-secondary"
                  onClick={() => {
                    resetPassword.reset();
                    setResetOpen(true);
                  }}
                >
                  <Icon name="lock_reset" className="text-[18px]" />
                  Reset password
                </button>
              )}
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

            {/*
              The only way to change a shift after the employee is created. The
              edit form hides its shift field once the record exists and says
              the shift is changed "from the employee record" — this is that,
              and until it existed the sentence pointed at nothing.

              Separate from the edit form on purpose: a shift change is dated
              and keeps its history, so it is not the same kind of edit as
              correcting a phone number.
            */}
            {canAssignShift && (
              <button
                type="button"
                className="mt-md text-body-sm text-primary underline underline-offset-2"
                onClick={() => {
                  assignShift.reset();
                  setShiftId(
                    employee.currentShift ? String(employee.currentShift.shiftId) : '',
                  );
                  setShiftFrom(new Date().toISOString().slice(0, 10));
                  setShiftOpen(true);
                }}
              >
                {employee.currentShift ? 'Change shift' : 'Assign a shift'}
              </button>
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
        open={shiftOpen}
        onClose={() => setShiftOpen(false)}
        title={`Shift for ${employee.fullName}`}
        description="Dated, and kept in the shift history — the previous assignment is closed the day before this one starts, so the two never overlap."
        footer={
          <>
            <button type="button" className="btn-secondary" onClick={() => setShiftOpen(false)}>
              Cancel
            </button>
            <button
              type="button"
              className="btn-primary"
              disabled={!shiftId || !shiftFrom || assignShift.isPending}
              onClick={() => assignShift.mutate()}
            >
              {assignShift.isPending ? (
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
          {assignShift.isError && (
            <Alert variant="error" title="Could not change the shift">
              {assignShift.error instanceof ApiError
                ? assignShift.error.message
                : 'Please try again.'}
            </Alert>
          )}

          <SelectField
            label="Shift"
            placeholder="Choose a shift"
            value={shiftId}
            onChange={(event) => setShiftId(event.target.value)}
            options={(shifts ?? []).map((shift) => ({
              value: shift.id,
              label: `${shift.name} (${shift.startTime}–${shift.endTime})`,
            }))}
          />

          <div>
            <label htmlFor="shift-effective-from" className="field-label">
              Effective from
            </label>
            <input
              id="shift-effective-from"
              type="date"
              value={shiftFrom}
              onChange={(event) => setShiftFrom(event.target.value)}
              className="field-input"
            />
            <p className="mt-unit text-body-sm text-on-surface-variant">
              Attendance before this date keeps being measured against the old shift, so
              back-dating this re-reads days that have already been settled.
            </p>
          </div>
        </div>
      </Modal>

      <Modal
        open={resetOpen}
        onClose={() => setResetOpen(false)}
        title={`Help ${employee.firstName} back into their account`}
        description={
          resetPassword.data
            ? undefined
            : 'Choose how they get back in. The two differ in more than delivery — see below.'
        }
        footer={
          resetPassword.data && resetPassword.data.outcome !== 'NO_EMAIL' ? (
            <button type="button" className="btn-primary" onClick={() => setResetOpen(false)}>
              Done
            </button>
          ) : (
            <>
              <button type="button" className="btn-secondary" onClick={() => setResetOpen(false)}>
                Cancel
              </button>
              <button
                type="button"
                className="btn-secondary"
                disabled={resetPassword.isPending}
                onClick={() => resetPassword.mutate('ONE_TIME_PASSWORD')}
              >
                <Icon name="key" className="text-[18px]" />
                Issue a one-time password
              </button>
              <button
                type="button"
                className="btn-primary"
                disabled={resetPassword.isPending}
                onClick={() => resetPassword.mutate('EMAIL_LINK')}
              >
                {resetPassword.isPending ? (
                  <Spinner className="h-4 w-4" />
                ) : (
                  <Icon name="mail" className="text-[18px]" />
                )}
                Email a setup link
              </button>
            </>
          )
        }
      >
        <div className="flex flex-col gap-md">
          {resetPassword.isError && (
            <Alert variant="error" title="Could not reset the password">
              {resetPassword.error instanceof ApiError
                ? resetPassword.error.message
                : 'Please try again.'}
            </Alert>
          )}

          {!resetPassword.data && (
            <dl className="grid gap-sm text-body-sm">
              <div className="rounded-md border border-outline-variant p-md">
                <dt className="font-medium text-on-surface">Email a setup link</dt>
                <dd className="mt-xs text-on-surface-variant">
                  Sends {employee.firstName} a one-time link to choose their own password.
                  Their current password keeps working until they use it, so nobody is locked
                  out if the mail goes astray. You never see their password.
                </dd>
              </div>
              <div className="rounded-md border border-outline-variant p-md">
                <dt className="font-medium text-on-surface">Issue a one-time password</dt>
                <dd className="mt-xs text-on-surface-variant">
                  Changes their password <strong>now</strong> and signs them out everywhere.
                  Shown to you once, to read out. Use this when the old password must stop
                  working immediately, or when they have no working mailbox.
                </dd>
              </div>
            </dl>
          )}

          {resetPassword.data?.outcome === 'NO_LOGIN' && (
            <Alert variant="warning" title="Nothing was reset">
              {employee.fullName} has no login, so there was no password to reset. Nobody can
              sign in as them until an account is created.
            </Alert>
          )}

          {resetPassword.data?.outcome === 'NO_EMAIL' && (
            <Alert variant="warning" title="No email address on file">
              Nothing was changed — {employee.firstName} has no address to send a link to, and
              invalidating their password with no way to tell them would just lock them out.
              Issue a one-time password instead.
            </Alert>
          )}

          {resetPassword.data?.outcome === 'LINK_SENT' && (
            <Alert variant="success" title="Setup link sent">
              Sent to <strong>{resetPassword.data.sentTo}</strong>. The link works once.
              {employee.firstName}&rsquo;s current password keeps working until they use it.
            </Alert>
          )}

          {resetPassword.data?.credential && (
            <>
              <Alert variant="warning" title="This is the only time it is shown">
                Copy it before you close this. The password is stored hashed — once this closes,
                nobody can read it back, and the only way to help {employee.firstName} is another
                reset.
              </Alert>
              <div className="rounded-md border border-outline-variant bg-surface-container p-md">
                <p className="mb-xs text-label-md uppercase tracking-wide text-on-surface-variant">
                  Sign in as {employee.employeeCode} with
                </p>
                <p className="font-mono text-title-md text-on-surface">
                  {resetPassword.data.credential.password}
                </p>
              </div>
            </>
          )}
        </div>
      </Modal>

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
