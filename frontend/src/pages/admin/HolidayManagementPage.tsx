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
import { HOLIDAY_TYPES, HOLIDAY_TYPE_LABEL, type Holiday } from '../../types/master';

const MONTHS = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

/** yyyy-MM-dd without going through Date, which would apply a timezone. */
const formatDate = (iso: string): string => {
  const [year, month, day] = iso.split('-').map(Number);
  return `${day} ${MONTHS[month - 1]} ${year}`;
};

const weekdayOf = (iso: string): string => {
  const [year, month, day] = iso.split('-').map(Number);
  return new Date(Date.UTC(year, month - 1, day)).toLocaleDateString('en-GB', {
    weekday: 'long',
    timeZone: 'UTC',
  });
};

const currentYear = new Date().getFullYear();
const YEAR_OPTIONS = [currentYear - 1, currentYear, currentYear + 1];

export const HolidayManagementPage = () => {
  const queryClient = useQueryClient();
  const canManage = useAuthStore((state) => state.hasPermission)('HOLIDAY_MANAGE');

  const [year, setYear] = useState(currentYear);
  const [showWithdrawn, setShowWithdrawn] = useState(false);
  const [dialog, setDialog] = useState<{ open: boolean; holiday: Holiday | null }>({
    open: false,
    holiday: null,
  });

  const { data: holidays, isLoading, error } = useQuery({
    queryKey: ['master', 'holidays', year, showWithdrawn],
    queryFn: () => masterApi.holidays.list(year, !showWithdrawn),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['master', 'holidays'] });

  const save = useMutation({
    mutationFn: (payload: Record<string, unknown>) =>
      dialog.holiday
        ? masterApi.holidays.update(dialog.holiday.id, payload)
        : masterApi.holidays.create(payload),
    onSuccess: () => {
      invalidate();
      setDialog({ open: false, holiday: null });
    },
  });

  const withdraw = useMutation({
    mutationFn: (id: number) => masterApi.holidays.withdraw(id),
    onSuccess: invalidate,
  });

  const rows = holidays ?? [];
  const upcoming = rows.filter((h) => h.active && !h.past).length;

  return (
    <div className="mx-auto max-w-[960px]">
      <PageHeader
        title="Holidays"
        description="The company holiday calendar. Every holiday applies to everyone, and a holiday is why somebody counts as excused rather than absent on that day."
        actions={
          canManage ? (
            <button
              type="button"
              className="btn-primary"
              onClick={() => {
                save.reset();
                setDialog({ open: true, holiday: null });
              }}
            >
              <Icon name="add" className="text-[18px]" />
              Declare holiday
            </button>
          ) : null
        }
      />

      <div className="mb-lg flex flex-wrap items-center gap-md">
        <div className="flex items-center gap-sm">
          <label htmlFor="holiday-year" className="text-body-md text-on-surface-variant">
            Year
          </label>
          <select
            id="holiday-year"
            value={year}
            onChange={(event) => setYear(Number(event.target.value))}
            className="field-input w-auto tabular-nums"
          >
            {YEAR_OPTIONS.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </div>

        <label className="flex cursor-pointer items-center gap-2">
          <input
            type="checkbox"
            checked={showWithdrawn}
            onChange={(event) => setShowWithdrawn(event.target.checked)}
            className="h-4 w-4 rounded-sm border-outline-variant text-primary focus:ring-primary"
          />
          <span className="text-body-md text-on-surface">Show withdrawn</span>
        </label>

        {!isLoading && (
          <span className="text-body-sm text-on-surface-variant sm:ml-auto">
            {rows.length} in {year}
            {upcoming > 0 && ` · ${upcoming} still to come`}
          </span>
        )}
      </div>

      {error && (
        <Alert variant="error" title="Could not load holidays" className="mb-lg">
          Please try again in a moment.
        </Alert>
      )}

      {withdraw.isError && (
        <Alert variant="error" title="Could not withdraw that holiday" className="mb-lg">
          {withdraw.error instanceof ApiError ? withdraw.error.message : 'Please try again.'}
        </Alert>
      )}

      {isLoading ? (
        <div className="flex justify-center py-xl">
          <Spinner className="h-8 w-8 text-primary" />
        </div>
      ) : rows.length === 0 ? (
        <div className="card flex flex-col items-center gap-sm p-xl text-center">
          <Icon name="event_busy" className="text-[40px] text-outline" />
          <p className="text-headline-md text-on-surface">No holidays declared for {year}</p>
          <p className="max-w-md text-body-md text-on-surface-variant">
            Until one is declared, every working day in {year} counts as a normal working day and
            nobody is excused from it.
          </p>
        </div>
      ) : (
        <ul className="flex flex-col gap-sm">
          {rows.map((holiday) => (
            <li
              key={holiday.id}
              className={`card flex flex-wrap items-center gap-md p-md ${
                holiday.active ? '' : 'opacity-60'
              }`}
            >
              {/* The date is the thing being scanned for, so it leads. */}
              <div className="w-[120px] shrink-0">
                <p className="text-title-sm tabular-nums text-on-surface">
                  {formatDate(holiday.holidayDate)}
                </p>
                <p className="text-body-sm text-on-surface-variant">
                  {weekdayOf(holiday.holidayDate)}
                </p>
              </div>

              <div className="min-w-0 flex-1">
                <p className="text-body-lg text-on-surface">{holiday.name}</p>
                {holiday.description && (
                  <p className="text-body-sm text-on-surface-variant">{holiday.description}</p>
                )}
              </div>

              <div className="flex items-center gap-sm">
                <StatusChip label={HOLIDAY_TYPE_LABEL[holiday.holidayType]} tone="info" />
                {!holiday.active && <StatusChip label="Withdrawn" tone="neutral" />}
                {holiday.active && holiday.past && <StatusChip label="Past" tone="neutral" />}
              </div>

              {canManage && holiday.active && (
                <div className="flex items-center gap-xs">
                  <button
                    type="button"
                    className="btn-ghost"
                    onClick={() => {
                      save.reset();
                      setDialog({ open: true, holiday });
                    }}
                  >
                    <Icon name="edit" className="text-[18px]" />
                    <span className="sr-only sm:not-sr-only">Edit</span>
                  </button>
                  <button
                    type="button"
                    className="btn-ghost text-error"
                    disabled={withdraw.isPending}
                    onClick={() => withdraw.mutate(holiday.id)}
                  >
                    <Icon name="event_busy" className="text-[18px]" />
                    <span className="sr-only sm:not-sr-only">Withdraw</span>
                  </button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}

      <Modal
        open={dialog.open}
        onClose={() => setDialog({ open: false, holiday: null })}
        title={dialog.holiday ? 'Amend holiday' : 'Declare holiday'}
        footer={
          <>
            <button
              type="button"
              className="btn-secondary"
              onClick={() => setDialog({ open: false, holiday: null })}
            >
              Cancel
            </button>
            <button type="submit" form="holiday-form" className="btn-primary" disabled={save.isPending}>
              {save.isPending ? (
                <Spinner className="h-4 w-4" />
              ) : (
                <Icon name="check" className="text-[18px]" />
              )}
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
          id="holiday-form"
          className="grid gap-md sm:grid-cols-2"
          onSubmit={(event) => {
            event.preventDefault();
            const formData = new FormData(event.currentTarget);
            // holidayYear is deliberately not sent: the server derives it from
            // the date so the two can never disagree.
            save.mutate({
              name: String(formData.get('name') ?? '').trim(),
              holidayDate: String(formData.get('holidayDate') ?? ''),
              holidayType: String(formData.get('holidayType') ?? 'COMPANY'),
              description: String(formData.get('description') ?? '').trim() || null,
              active: true,
            });
          }}
        >
          <div className="sm:col-span-2">
            <label htmlFor="holiday-name" className="field-label">
              Name
            </label>
            <input
              id="holiday-name"
              name="name"
              required
              maxLength={150}
              defaultValue={dialog.holiday?.name ?? ''}
              className="field-input"
              placeholder="Diwali"
            />
          </div>

          <div>
            <label htmlFor="holiday-date" className="field-label">
              Date
            </label>
            <input
              id="holiday-date"
              name="holidayDate"
              type="date"
              required
              defaultValue={dialog.holiday?.holidayDate ?? ''}
              className="field-input tabular-nums"
            />
          </div>

          <div>
            <label htmlFor="holiday-type" className="field-label">
              Type
            </label>
            <select
              id="holiday-type"
              name="holidayType"
              defaultValue={dialog.holiday?.holidayType ?? 'COMPANY'}
              className="field-input"
            >
              {HOLIDAY_TYPES.map((type) => (
                <option key={type} value={type}>
                  {HOLIDAY_TYPE_LABEL[type]}
                </option>
              ))}
            </select>
            <p className="mt-unit text-body-sm text-on-surface-variant">
              All three apply to everybody. Optional and restricted holidays are not supported.
            </p>
          </div>

          <div className="sm:col-span-2">
            <label htmlFor="holiday-description" className="field-label">
              Description <span className="text-on-surface-variant">(optional)</span>
            </label>
            <input
              id="holiday-description"
              name="description"
              maxLength={500}
              defaultValue={dialog.holiday?.description ?? ''}
              className="field-input"
            />
          </div>

          <p className="text-body-sm text-on-surface-variant sm:col-span-2">
            Attendance is worked out when it is read, so changing a past date changes how those
            days already count.
          </p>
        </form>
      </Modal>
    </div>
  );
};
