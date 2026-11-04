import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { Modal } from '../../components/common/Modal';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { StatusChip } from '../../components/common/StatusChip';
import { masterApi } from '../../features/admin/masterApi';
import { useAuthStore } from '../../store/authStore';
import { ApiError } from '../../types/api';
import type { Shift } from '../../types/master';

const formatHours = (minutes: number): string => {
  const hours = Math.floor(minutes / 60);
  const remainder = minutes % 60;
  return remainder === 0 ? `${hours}h` : `${hours}h ${remainder}m`;
};

export const ShiftsPage = () => {
  const queryClient = useQueryClient();
  const canManage = useAuthStore((state) => state.hasPermission)('SHIFT_MANAGE');

  const [dialog, setDialog] = useState<{ open: boolean; shift: Shift | null }>({
    open: false,
    shift: null,
  });

  const { data: shifts, isLoading, error } = useQuery({
    queryKey: ['master', 'shifts', false],
    queryFn: () => masterApi.shifts.list(false),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['master', 'shifts'] });

  const save = useMutation({
    mutationFn: (payload: Record<string, unknown>) =>
      dialog.shift ? masterApi.shifts.update(dialog.shift.id, payload) : masterApi.shifts.create(payload),
    onSuccess: () => {
      invalidate();
      setDialog({ open: false, shift: null });
    },
  });

  const remove = useMutation({
    mutationFn: (id: number) => masterApi.shifts.deactivate(id),
    onSuccess: invalidate,
  });

  if (isLoading) {
    return (
      <div className="flex justify-center py-xl">
        <Spinner className="h-8 w-8 text-primary" />
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-[1280px]">
      <PageHeader
        title="Shifts"
        description="Working patterns and their hour thresholds. Every attendance rule reads from here — nothing is hardcoded."
        actions={
          canManage ? (
            <button
              type="button"
              className="btn-primary"
              onClick={() => {
                save.reset();
                setDialog({ open: true, shift: null });
              }}
            >
              <Icon name="add" className="text-[18px]" />
              Add shift
            </button>
          ) : null
        }
      />

      {error && (
        <Alert variant="error" title="Could not load shifts" className="mb-lg">
          Please try again in a moment.
        </Alert>
      )}

      {remove.isError && (
        <Alert variant="error" title="Could not deactivate that shift" className="mb-lg">
          {remove.error instanceof ApiError ? remove.error.message : 'Please try again.'}
        </Alert>
      )}

      <div className="grid gap-gutter md:grid-cols-2 xl:grid-cols-3">
        {(shifts ?? []).map((shift) => (
          <article key={shift.id} className="card flex flex-col p-lg">
            <header className="mb-md flex items-start justify-between gap-sm">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <h2 className="text-headline-md text-on-surface">{shift.name}</h2>
                  {shift.defaultShift && <StatusChip label="Default" tone="info" />}
                </div>
                <p className="mt-unit font-mono text-body-sm text-on-surface-variant">{shift.code}</p>
              </div>
              <StatusChip
                label={shift.active ? 'Active' : 'Inactive'}
                tone={shift.active ? 'success' : 'neutral'}
              />
            </header>

            <p className="mb-md font-mono text-headline-md tabular-nums text-primary">
              {shift.startTime} – {shift.endTime}
              {shift.nightShift && (
                <span className="ml-2 align-middle text-body-sm font-sans text-on-surface-variant">
                  crosses midnight
                </span>
              )}
            </p>

            <dl className="grid flex-1 grid-cols-2 gap-x-md gap-y-sm border-t border-outline-variant/60 pt-md">
              <div>
                <dt className="text-body-sm text-on-surface-variant">Full day</dt>
                <dd className="text-body-md tabular-nums text-on-surface">{formatHours(shift.fullDayMinutes)}</dd>
              </div>
              <div>
                <dt className="text-body-sm text-on-surface-variant">Half day</dt>
                <dd className="text-body-md tabular-nums text-on-surface">{formatHours(shift.halfDayMinutes)}</dd>
              </div>
              <div>
                <dt className="text-body-sm text-on-surface-variant">Grace</dt>
                <dd className="text-body-md tabular-nums text-on-surface">{shift.gracePeriodMinutes} min</dd>
              </div>
              <div>
                <dt className="text-body-sm text-on-surface-variant">Max break</dt>
                <dd className="text-body-md tabular-nums text-on-surface">{shift.maxBreakMinutes} min</dd>
              </div>
              <div className="col-span-2">
                <dt className="text-body-sm text-on-surface-variant">Assigned</dt>
                <dd className="text-body-md tabular-nums text-on-surface">
                  {shift.assignedEmployeeCount} employee{shift.assignedEmployeeCount === 1 ? '' : 's'}
                </dd>
              </div>
            </dl>

            {canManage && (
              <div className="mt-md flex gap-sm border-t border-outline-variant/60 pt-md">
                <button
                  type="button"
                  className="btn-secondary flex-1"
                  onClick={() => {
                    save.reset();
                    setDialog({ open: true, shift });
                  }}
                >
                  <Icon name="edit" className="text-[18px]" />
                  Edit
                </button>
                {shift.active && !shift.defaultShift && (
                  <button
                    type="button"
                    className="btn-secondary text-error"
                    onClick={() => remove.mutate(shift.id)}
                    disabled={remove.isPending}
                    aria-label={`Deactivate ${shift.name}`}
                  >
                    <Icon name="block" className="text-[18px]" />
                  </button>
                )}
              </div>
            )}
          </article>
        ))}
      </div>

      <Modal
        open={dialog.open}
        onClose={() => setDialog({ open: false, shift: null })}
        title={dialog.shift ? `Edit ${dialog.shift.name}` : 'Add shift'}
        description="Thresholds are checked against each other and against the shift's own span."
        size="lg"
        footer={
          <>
            <button type="button" className="btn-secondary" onClick={() => setDialog({ open: false, shift: null })}>
              Cancel
            </button>
            <button type="submit" form="shift-form" className="btn-primary" disabled={save.isPending}>
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

        <form
          id="shift-form"
          className="grid gap-md sm:grid-cols-2"
          onSubmit={(event) => {
            event.preventDefault();
            const formData = new FormData(event.currentTarget);
            const asNumber = (key: string) => Number(formData.get(key));

            save.mutate({
              code: String(formData.get('code') ?? '').trim().toUpperCase(),
              name: String(formData.get('name') ?? '').trim(),
              startTime: String(formData.get('startTime') ?? ''),
              endTime: String(formData.get('endTime') ?? ''),
              gracePeriodMinutes: asNumber('gracePeriodMinutes'),
              earlyExitGraceMinutes: asNumber('earlyExitGraceMinutes'),
              standardWorkMinutes: asNumber('standardWorkMinutes'),
              fullDayMinutes: asNumber('fullDayMinutes'),
              halfDayMinutes: asNumber('halfDayMinutes'),
              minAttendanceMinutes: asNumber('minAttendanceMinutes'),
              maxBreakMinutes: asNumber('maxBreakMinutes'),
              punchInWindowBeforeMinutes: asNumber('punchInWindowBeforeMinutes'),
              punchOutWindowAfterMinutes: asNumber('punchOutWindowAfterMinutes'),
              defaultShift: formData.get('defaultShift') === 'on',
              active: true,
            });
          }}
        >
          <div>
            <label htmlFor="shift-code" className="field-label">Code</label>
            <input id="shift-code" name="code" required maxLength={30} pattern="[A-Za-z0-9_-]+"
              defaultValue={dialog.shift?.code ?? ''} className="field-input font-mono uppercase" />
          </div>
          <div>
            <label htmlFor="shift-name" className="field-label">Name</label>
            <input id="shift-name" name="name" required maxLength={120}
              defaultValue={dialog.shift?.name ?? ''} className="field-input" />
          </div>
          <div>
            <label htmlFor="shift-start" className="field-label">Start time</label>
            <input id="shift-start" name="startTime" type="time" required
              defaultValue={dialog.shift?.startTime ?? '09:30'} className="field-input font-mono" />
          </div>
          <div>
            <label htmlFor="shift-end" className="field-label">End time</label>
            <input id="shift-end" name="endTime" type="time" required
              defaultValue={dialog.shift?.endTime ?? '18:30'} className="field-input font-mono" />
            <p className="mt-unit text-body-sm text-on-surface-variant">
              An end at or before the start marks this a night shift.
            </p>
          </div>

          <fieldset className="grid gap-md sm:col-span-2 sm:grid-cols-3">
            <legend className="mb-sm text-label-md uppercase text-on-surface-variant">
              Hour thresholds (minutes)
            </legend>
            {[
              { name: 'standardWorkMinutes', label: 'Standard work', fallback: 480 },
              { name: 'fullDayMinutes', label: 'Full day', fallback: 480 },
              { name: 'halfDayMinutes', label: 'Half day', fallback: 240 },
              { name: 'minAttendanceMinutes', label: 'Minimum attendance', fallback: 120 },
              { name: 'gracePeriodMinutes', label: 'Late grace', fallback: 15 },
              { name: 'earlyExitGraceMinutes', label: 'Early exit grace', fallback: 15 },
              { name: 'maxBreakMinutes', label: 'Max break', fallback: 60 },
              { name: 'punchInWindowBeforeMinutes', label: 'Punch-in window before', fallback: 120 },
              { name: 'punchOutWindowAfterMinutes', label: 'Punch-out window after', fallback: 240 },
            ].map((field) => (
              <div key={field.name}>
                <label htmlFor={`shift-${field.name}`} className="field-label">{field.label}</label>
                <input
                  id={`shift-${field.name}`}
                  name={field.name}
                  type="number"
                  min={0}
                  max={1440}
                  required
                  defaultValue={
                    (dialog.shift?.[field.name as keyof Shift] as number | undefined) ?? field.fallback
                  }
                  className="field-input tabular-nums"
                />
              </div>
            ))}
          </fieldset>

          <label className="flex cursor-pointer items-center gap-2 sm:col-span-2">
            <input type="checkbox" name="defaultShift" defaultChecked={dialog.shift?.defaultShift ?? false}
              className="h-4 w-4 rounded-sm border-outline-variant text-primary focus:ring-primary" />
            <span className="text-body-md text-on-surface">
              Make this the default shift
              <span className="block text-body-sm text-on-surface-variant">
                New employees get this shift when none is chosen. Only one shift can be the default.
              </span>
            </span>
          </label>
        </form>
      </Modal>
    </div>
  );
};
