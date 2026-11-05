import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { Modal } from '../../components/common/Modal';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { StatusChip } from '../../components/common/StatusChip';
import { masterApi } from '../../features/admin/masterApi';
import { employeeApi } from '../../features/employee/employeeApi';
import { useAuthStore } from '../../store/authStore';
import { ApiError } from '../../types/api';
import {
  WEEKDAYS,
  WEEKLY_OFF_SCOPES,
  WEEKLY_OFF_SCOPE_LABEL,
  type WeeklyOff,
  type WeeklyOffScope,
} from '../../types/master';

export const WeeklyOffsPage = () => {
  const queryClient = useQueryClient();
  const canManage = useAuthStore((state) => state.hasPermission)('WEEKLY_OFF_MANAGE');

  const [dialog, setDialog] = useState<{ open: boolean; rule: WeeklyOff | null }>({
    open: false,
    rule: null,
  });
  const [scope, setScope] = useState<WeeklyOffScope>('GLOBAL');

  const { data: rules, isLoading, error } = useQuery({
    queryKey: ['master', 'weeklyOffs'],
    queryFn: () => masterApi.weeklyOffs.list(true),
  });

  // Only fetched once a scope that needs a target is chosen, so opening the
  // dialog for a company-wide rule costs nothing.
  const { data: offices } = useQuery({
    queryKey: ['master', 'offices', true],
    queryFn: () => masterApi.offices.list(true),
    enabled: dialog.open && scope === 'OFFICE',
  });
  const { data: departments } = useQuery({
    queryKey: ['master', 'departments', true],
    queryFn: () => masterApi.departments.list(true),
    enabled: dialog.open && scope === 'DEPARTMENT',
  });
  const { data: employees } = useQuery({
    queryKey: ['employees', 'forScopePicker'],
    queryFn: () => employeeApi.search({ page: 0, size: 200 }),
    enabled: dialog.open && scope === 'EMPLOYEE',
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['master', 'weeklyOffs'] });

  const save = useMutation({
    mutationFn: (payload: Record<string, unknown>) =>
      dialog.rule
        ? masterApi.weeklyOffs.update(dialog.rule.id, payload)
        : masterApi.weeklyOffs.create(payload),
    onSuccess: () => {
      invalidate();
      setDialog({ open: false, rule: null });
    },
  });

  const remove = useMutation({
    mutationFn: (id: number) => masterApi.weeklyOffs.remove(id),
    onSuccess: invalidate,
  });

  const open = (rule: WeeklyOff | null) => {
    save.reset();
    setScope(rule?.scopeType ?? 'GLOBAL');
    setDialog({ open: true, rule });
  };

  const list = rules ?? [];
  const companyWide = list.filter((r) => r.scopeType === 'GLOBAL');
  const exceptions = list.filter((r) => r.scopeType !== 'GLOBAL');

  const scopeOptions =
    scope === 'OFFICE'
      ? (offices ?? []).map((o) => ({ id: o.id, label: o.name }))
      : scope === 'DEPARTMENT'
        ? (departments ?? []).map((d) => ({ id: d.id, label: d.name }))
        : scope === 'EMPLOYEE'
          ? (employees?.content ?? []).map((e) => ({
              id: e.id,
              label: `${e.fullName} (${e.employeeCode})`,
            }))
          : [];

  return (
    <div className="mx-auto max-w-[960px]">
      <PageHeader
        title="Weekly Offs"
        description="Recurring non-working days. Alternate Saturdays are two rules — Saturday in week 1 and Saturday in week 3."
        actions={
          canManage ? (
            <button type="button" className="btn-primary" onClick={() => open(null)}>
              <Icon name="add" className="text-[18px]" />
              Add rule
            </button>
          ) : null
        }
      />

      {/*
        The single most confusing thing about this model, surfaced where the
        mistake gets made rather than left in a doc nobody opens. Every rule
        means "off"; a more specific rule picks which off-rule wins, it cannot
        hand a day back. So a company-wide Saturday rule makes per-group
        Saturdays impossible.
      */}
      <Alert variant="info" title="A more specific rule cannot cancel a broader one" className="mb-lg">
        Every rule here marks a day <strong>off</strong>. Where rules overlap the most specific one
        wins, but it can never turn a day back into a working day. For groups working different
        Saturdays there must be <strong>no company-wide Saturday rule</strong> — give each group its
        own weeks instead.
      </Alert>

      {error && (
        <Alert variant="error" title="Could not load the rules" className="mb-lg">
          Please try again in a moment.
        </Alert>
      )}

      {remove.isError && (
        <Alert variant="error" title="Could not remove that rule" className="mb-lg">
          {remove.error instanceof ApiError ? remove.error.message : 'Please try again.'}
        </Alert>
      )}

      {isLoading ? (
        <div className="flex justify-center py-xl">
          <Spinner className="h-8 w-8 text-primary" />
        </div>
      ) : list.length === 0 ? (
        <div className="card flex flex-col items-center gap-sm p-xl text-center">
          <Icon name="event_repeat" className="text-[40px] text-outline" />
          <p className="text-headline-md text-on-surface">No weekly offs</p>
          <p className="max-w-md text-body-md text-on-surface-variant">
            Every day of the week counts as a working day for everybody until a rule says otherwise.
          </p>
        </div>
      ) : (
        <div className="flex flex-col gap-lg">
          {[
            { heading: 'Everyone', rows: companyWide },
            { heading: 'Exceptions', rows: exceptions },
          ]
            .filter((group) => group.rows.length > 0)
            .map((group) => (
              <section key={group.heading}>
                <h2 className="mb-sm text-label-md uppercase text-on-surface-variant">
                  {group.heading}
                </h2>
                <ul className="flex flex-col gap-sm">
                  {group.rows.map((rule) => (
                    <li key={rule.id} className="card flex flex-wrap items-center gap-md p-md">
                      <div className="w-[150px] shrink-0">
                        <p className="text-title-sm text-on-surface">{rule.summary}</p>
                        {(rule.effectiveFrom || rule.effectiveTo) && (
                          <p className="text-body-sm text-on-surface-variant">
                            {rule.effectiveFrom ?? '…'} to {rule.effectiveTo ?? '…'}
                          </p>
                        )}
                      </div>

                      <div className="min-w-0 flex-1">
                        <p className="text-body-md text-on-surface">{rule.scopeName}</p>
                      </div>

                      <StatusChip
                        label={WEEKLY_OFF_SCOPE_LABEL[rule.scopeType]}
                        tone={rule.scopeType === 'GLOBAL' ? 'info' : 'neutral'}
                      />

                      {canManage && (
                        <div className="flex items-center gap-xs">
                          <button type="button" className="btn-ghost" onClick={() => open(rule)}>
                            <Icon name="edit" className="text-[18px]" />
                            <span className="sr-only sm:not-sr-only">Edit</span>
                          </button>
                          <button
                            type="button"
                            className="btn-ghost text-error"
                            disabled={remove.isPending}
                            onClick={() => remove.mutate(rule.id)}
                          >
                            <Icon name="delete" className="text-[18px]" />
                            <span className="sr-only sm:not-sr-only">Remove</span>
                          </button>
                        </div>
                      )}
                    </li>
                  ))}
                </ul>
              </section>
            ))}
        </div>
      )}

      <Modal
        open={dialog.open}
        onClose={() => setDialog({ open: false, rule: null })}
        title={dialog.rule ? 'Amend rule' : 'Add weekly off'}
        footer={
          <>
            <button
              type="button"
              className="btn-secondary"
              onClick={() => setDialog({ open: false, rule: null })}
            >
              Cancel
            </button>
            <button type="submit" form="weekly-off-form" className="btn-primary" disabled={save.isPending}>
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
          id="weekly-off-form"
          className="grid gap-md sm:grid-cols-2"
          onSubmit={(event) => {
            event.preventDefault();
            const data = new FormData(event.currentTarget);
            const week = String(data.get('weekOfMonth') ?? '');
            const from = String(data.get('effectiveFrom') ?? '');
            const to = String(data.get('effectiveTo') ?? '');
            save.mutate({
              scopeType: scope,
              scopeId: scope === 'GLOBAL' ? null : Number(data.get('scopeId')),
              dayOfWeek: Number(data.get('dayOfWeek')),
              // Empty means every week; the API treats null that way.
              weekOfMonth: week === '' ? null : Number(week),
              effectiveFrom: from || null,
              effectiveTo: to || null,
              active: true,
            });
          }}
        >
          <div>
            <label htmlFor="wo-scope" className="field-label">
              Applies to
            </label>
            <select
              id="wo-scope"
              value={scope}
              onChange={(event) => setScope(event.target.value as WeeklyOffScope)}
              className="field-input"
            >
              {WEEKLY_OFF_SCOPES.map((value) => (
                <option key={value} value={value}>
                  {WEEKLY_OFF_SCOPE_LABEL[value]}
                </option>
              ))}
            </select>
          </div>

          {scope !== 'GLOBAL' && (
            <div>
              <label htmlFor="wo-target" className="field-label">
                Which one
              </label>
              <select
                id="wo-target"
                name="scopeId"
                required
                defaultValue={dialog.rule?.scopeId ?? ''}
                className="field-input"
              >
                <option value="" disabled>
                  {scopeOptions.length === 0 ? 'Loading…' : 'Choose…'}
                </option>
                {scopeOptions.map((option) => (
                  <option key={option.id} value={option.id}>
                    {option.label}
                  </option>
                ))}
              </select>
            </div>
          )}

          <div>
            <label htmlFor="wo-day" className="field-label">
              Day
            </label>
            <select
              id="wo-day"
              name="dayOfWeek"
              defaultValue={dialog.rule?.dayOfWeek ?? 7}
              className="field-input"
            >
              {WEEKDAYS.map((day) => (
                <option key={day.value} value={day.value}>
                  {day.label}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label htmlFor="wo-week" className="field-label">
              Which week
            </label>
            <select
              id="wo-week"
              name="weekOfMonth"
              defaultValue={dialog.rule?.weekOfMonth ?? ''}
              className="field-input"
            >
              <option value="">Every week</option>
              {[1, 2, 3, 4, 5].map((week) => (
                <option key={week} value={week}>
                  {['1st', '2nd', '3rd', '4th', '5th'][week - 1]} only
                </option>
              ))}
            </select>
            <p className="mt-unit text-body-sm text-on-surface-variant">
              Counted as the nth of that weekday in the month. A month with five of them has no
              rule on the fifth, so it is worked.
            </p>
          </div>

          <div>
            <label htmlFor="wo-from" className="field-label">
              Starts <span className="text-on-surface-variant">(optional)</span>
            </label>
            <input
              id="wo-from"
              name="effectiveFrom"
              type="date"
              defaultValue={dialog.rule?.effectiveFrom ?? ''}
              className="field-input tabular-nums"
            />
          </div>

          <div>
            <label htmlFor="wo-to" className="field-label">
              Ends <span className="text-on-surface-variant">(optional)</span>
            </label>
            <input
              id="wo-to"
              name="effectiveTo"
              type="date"
              defaultValue={dialog.rule?.effectiveTo ?? ''}
              className="field-input tabular-nums"
            />
          </div>
        </form>
      </Modal>
    </div>
  );
};
