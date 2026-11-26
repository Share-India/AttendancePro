import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { attendancePolicyApi } from '../../features/admin/masterApi';
import { ApiError } from '../../types/api';
import type { AttendancePolicy, AttendancePolicyUpdate } from '../../types/attendance';

/** A whole-number field with its unit and a line saying what it decides. */
const NumberField = ({
  label,
  hint,
  unit,
  value,
  onChange,
}: {
  label: string;
  hint: string;
  unit: string;
  value: number;
  onChange: (next: number) => void;
}) => (
  <div>
    <label className="field-label">{label}</label>
    <div className="flex items-center gap-sm">
      <input
        type="number"
        className="field-input w-28 tabular-nums"
        value={value}
        onChange={(event) => onChange(Number(event.target.value))}
      />
      <span className="text-body-sm text-on-surface-variant">{unit}</span>
    </div>
    <p className="mt-unit text-body-sm text-on-surface-variant">{hint}</p>
  </div>
);

const ToggleField = ({
  label,
  hint,
  checked,
  onChange,
}: {
  label: string;
  hint: string;
  checked: boolean;
  onChange: (next: boolean) => void;
}) => (
  <label className="flex cursor-pointer items-start gap-sm rounded-md border border-outline-variant/60 p-sm hover:bg-surface-variant/40">
    <input
      type="checkbox"
      className="mt-1 h-4 w-4 accent-primary"
      checked={checked}
      onChange={(event) => onChange(event.target.checked)}
    />
    <span>
      <span className="block text-body-md text-on-surface">{label}</span>
      <span className="block text-body-sm text-on-surface-variant">{hint}</span>
    </span>
  </label>
);

const Section = ({
  title,
  description,
  children,
}: {
  title: string;
  description: string;
  children: React.ReactNode;
}) => (
  <section className="card p-lg">
    <h2 className="text-headline-md text-on-surface">{title}</h2>
    <p className="mt-unit text-body-sm text-on-surface-variant">{description}</p>
    <div className="mt-lg grid gap-lg sm:grid-cols-2">{children}</div>
  </section>
);

/** Hours and minutes, for a figure entered in minutes. */
const asHours = (minutes: number): string => {
  if (!Number.isFinite(minutes) || minutes <= 0) return '—';
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h === 0) return `${m}m`;
  return m === 0 ? `${h}h` : `${h}h ${m}m`;
};

export const AttendanceRulesPage = () => {
  const queryClient = useQueryClient();
  const { data, isLoading, error } = useQuery({
    queryKey: ['admin', 'attendance-policies'],
    queryFn: () => attendancePolicyApi.findAll(),
  });

  const policy: AttendancePolicy | undefined = data?.[0];
  const [form, setForm] = useState<AttendancePolicyUpdate | null>(null);

  // Loaded once the policy arrives, and again whenever a save returns a fresh
  // copy. Editing a draft rather than the query data keeps a half-finished
  // change from being thrown away by a background refetch.
  useEffect(() => {
    if (!policy) return;
    const { id, code, active, lateMarkResetPeriod, shiftOverridesDayLengths, ...editable } = policy;
    void id;
    void code;
    void active;
    void lateMarkResetPeriod;
    void shiftOverridesDayLengths;
    setForm(editable);
  }, [policy]);

  const save = useMutation({
    mutationFn: () => attendancePolicyApi.update(policy!.id, form!),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'attendance-policies'] });
      // Every settled day is measured against these, so anything already on
      // screen was computed under the old rules.
      queryClient.invalidateQueries({ queryKey: ['attendance'] });
    },
  });

  const set = <K extends keyof AttendancePolicyUpdate>(
    key: K,
    value: AttendancePolicyUpdate[K],
  ) => setForm((current) => (current ? { ...current, [key]: value } : current));

  if (isLoading) {
    return (
      <div className="flex justify-center py-xl">
        <Spinner className="h-8 w-8 text-primary" />
      </div>
    );
  }

  if (error || !policy || !form) {
    return (
      <Alert variant="error" title="Could not load the attendance rules">
        {error instanceof ApiError ? error.message : 'Please try again.'}
      </Alert>
    );
  }

  return (
    <div className="flex flex-col gap-lg">
      <PageHeader
        title="Attendance rules"
        description="What counts as a day worked, how late is late, and how many requests somebody may raise."
      />

      {save.isError && (
        <Alert variant="error" title="Could not save">
          {save.error instanceof ApiError ? save.error.message : 'Please try again.'}
        </Alert>
      )}

      {save.isSuccess && !save.isPending && (
        <Alert variant="success">
          Saved. The new rules apply to days settled from now on — days already closed keep the
          figures they were settled with.
        </Alert>
      )}

      <Section
        title="Requests"
        description="How far back a correction may reach, and how many each person gets."
      >
        <NumberField
          label="Correction window"
          unit="days"
          hint="How old a day can be and still be corrected. Past this, only HR can adjust it."
          value={form.regularizationWindowDays}
          onChange={(next) => set('regularizationWindowDays', next)}
        />
        <NumberField
          label="Corrections per month"
          unit="per person"
          hint="Counted against the month of the day being corrected. Rejected requests still count."
          value={form.maxRegularizationsPerMonth}
          onChange={(next) => set('maxRegularizationsPerMonth', next)}
        />
        <NumberField
          label="Work-from-home days per month"
          unit="days"
          hint="The most days one person may be approved to work remotely in a month."
          value={form.maxWfhDaysPerMonth}
          onChange={(next) => set('maxWfhDaysPerMonth', next)}
        />
        <NumberField
          label="Work-from-home notice"
          unit="days"
          hint="How far ahead a request must be raised. Zero allows same-day requests."
          value={form.wfhMinNoticeDays}
          onChange={(next) => set('wfhMinNoticeDays', next)}
        />
      </Section>

      <Section
        title="Missing punch-in"
        description="Nobody is emailed unless this is on. Each person is reminded once a day, at most."
      >
        <div className="sm:col-span-2">
          <ToggleField
            label="Email anyone who has not punched in"
            hint="Sent only to the person themselves, and only on a working day they are not on leave, on holiday or on a weekly off."
            checked={form.punchInReminderEnabled}
            onChange={(next) => set('punchInReminderEnabled', next)}
          />
        </div>
        <NumberField
          label="Send after"
          unit={`minutes past shift start (${asHours(form.punchInReminderDelayMinutes)})`}
          hint="Counted from each person's own shift, so a 08:30 and a 10:00 start are both reminded at the same point in their morning."
          value={form.punchInReminderDelayMinutes}
          onChange={(next) => set('punchInReminderDelayMinutes', next)}
        />
        <div className="self-end">
          {form.punchInReminderEnabled && form.punchInReminderDelayMinutes < form.gracePeriodMinutes ? (
            <Alert variant="warning">
              This is inside the {form.gracePeriodMinutes}-minute grace period, so people will be
              chased for arriving on time.
            </Alert>
          ) : null}
        </div>
      </Section>

      <Section
        title="Lateness"
        description="Grace is a monthly allowance shared across the month, not a fresh window each morning."
      >
        <NumberField
          label="Grace period"
          unit="minutes"
          hint="Used only where a shift sets no grace of its own."
          value={form.gracePeriodMinutes}
          onChange={(next) => set('gracePeriodMinutes', next)}
        />
        <NumberField
          label="Early-exit grace"
          unit="minutes"
          hint="How early someone may leave before it is recorded against the day."
          value={form.earlyExitGraceMinutes}
          onChange={(next) => set('earlyExitGraceMinutes', next)}
        />
        <NumberField
          label="Late marks before a half day"
          unit="marks"
          hint={`Reset ${form.lateMarksForHalfDay > 0 ? policy.lateMarkResetPeriod.toLowerCase() : 'never'}.`}
          value={form.lateMarksForHalfDay}
          onChange={(next) => set('lateMarksForHalfDay', next)}
        />
      </Section>

      <Section title="Breaks" description="What a break costs the working day.">
        <NumberField
          label="Longest single break"
          unit="minutes"
          hint="A break beyond this is flagged for the manager, never blocked."
          value={form.maxBreakMinutes}
          onChange={(next) => set('maxBreakMinutes', next)}
        />
        <ToggleField
          label="Deduct breaks from working hours"
          hint="Off means a break counts as time worked. Individual break types can still override this."
          checked={form.deductBreakFromWorking}
          onChange={(next) => set('deductBreakFromWorking', next)}
        />
        <ToggleField
          label="Allow punching on a holiday"
          hint="The day stays a holiday; the hours are recorded against it."
          checked={form.allowPunchOnHoliday}
          onChange={(next) => set('allowPunchOnHoliday', next)}
        />
        <ToggleField
          label="Allow punching on a weekly off"
          hint="Needed for anyone who works the occasional Saturday outside their roster."
          checked={form.allowPunchOnWeeklyOff}
          onChange={(next) => set('allowPunchOnWeeklyOff', next)}
        />
      </Section>

      <Section
        title="What counts as a day"
        description="Minutes actually worked, after deductible breaks come off — not time on the premises."
      >
        <div className="sm:col-span-2">
          {policy.shiftOverridesDayLengths ? (
            <Alert variant="warning" title="Your shifts set these themselves">
              Every active shift carries its own day lengths, and a shift's value wins. Changing
              them here will have no effect on anyone. Edit the shift instead.
            </Alert>
          ) : (
            <Alert variant="info">
              These apply only where a shift sets no value of its own.
            </Alert>
          )}
        </div>

        <NumberField
          label="Full day"
          unit={`minutes (${asHours(form.fullDayMinutes)})`}
          hint="Worked this much and the day is Present. Must be reachable after breaks are deducted."
          value={form.fullDayMinutes}
          onChange={(next) => set('fullDayMinutes', next)}
        />
        <NumberField
          label="Short day"
          unit={`minutes (${asHours(form.shortDayMinutes)})`}
          hint="Above this but below a full day: paid in full, marked short."
          value={form.shortDayMinutes}
          onChange={(next) => set('shortDayMinutes', next)}
        />
        <NumberField
          label="Half day"
          unit={`minutes (${asHours(form.halfDayMinutes)})`}
          hint="Above this but below a short day: half a day's pay."
          value={form.halfDayMinutes}
          onChange={(next) => set('halfDayMinutes', next)}
        />
        <NumberField
          label="Minimum to count at all"
          unit={`minutes (${asHours(form.minAttendanceMinutes)})`}
          hint="Below this the day is Absent rather than short hours."
          value={form.minAttendanceMinutes}
          onChange={(next) => set('minAttendanceMinutes', next)}
        />
      </Section>

      <div className="sticky bottom-0 flex justify-end gap-sm border-t border-outline-variant/60 bg-surface py-md">
        <button
          type="button"
          className="btn-primary"
          disabled={save.isPending}
          onClick={() => save.mutate()}
        >
          {save.isPending ? (
            <Spinner className="h-4 w-4" />
          ) : (
            <Icon name="check" className="text-[18px]" />
          )}
          Save rules
        </button>
      </div>
    </div>
  );
};
