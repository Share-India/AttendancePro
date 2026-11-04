import { useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { SelectField } from '../../components/form/SelectField';
import { TextField } from '../../components/form/TextField';
import { ROUTES } from '../../constants/routes';
import { leaveApi } from '../../features/leave/leaveApi';
import { ApiError } from '../../types/api';
import type { LeaveApplyResult } from '../../types/leave';

interface FormValues {
  leaveTypeId: string;
  fromDate: string;
  toDate: string;
  fromDayPortion: string;
  toDayPortion: string;
  reason: string;
  contactDuringLeave: string;
  attachmentUrl: string;
}

const isoToday = (): string => new Date().toISOString().slice(0, 10);

const describeFailure = (error: unknown): { title: string; message: string } => {
  if (!(error instanceof ApiError)) {
    return { title: 'Could not submit', message: 'Something went wrong. Please try again.' };
  }

  switch (error.errorCode) {
    case 'LEAVE_BALANCE_INSUFFICIENT': {
      const detail = error.details as
        | { availableDays?: number; requestedDays?: number; shortfallDays?: number }
        | undefined;
      return {
        title: 'Not enough balance',
        message:
          detail?.availableDays != null
            ? `You have ${detail.availableDays} day(s) available and this needs ${detail.requestedDays}. ` +
              `That is ${detail.shortfallDays} short.`
            : error.message,
      };
    }
    case 'OVERLAPPING_LEAVE':
      return { title: 'Those dates clash', message: error.message };
    case 'POLICY_VIOLATION':
      return { title: 'Not allowed by policy', message: error.message };
    default:
      return { title: 'Could not submit', message: error.message };
  }
};

export const ApplyLeavePage = () => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [result, setResult] = useState<LeaveApplyResult | null>(null);

  const { data: types } = useQuery({ queryKey: ['leave', 'types'], queryFn: leaveApi.types });
  const { data: balances } = useQuery({ queryKey: ['leave', 'balances'], queryFn: leaveApi.balances });

  const {
    register,
    handleSubmit,
    watch,
    formState: { errors },
  } = useForm<FormValues>({
    defaultValues: {
      leaveTypeId: '',
      fromDate: '',
      toDate: '',
      fromDayPortion: 'FULL_DAY',
      toDayPortion: 'FULL_DAY',
      reason: '',
      contactDuringLeave: '',
      attachmentUrl: '',
    },
  });

  const selectedTypeId = watch('leaveTypeId');
  const fromDate = watch('fromDate');
  const toDate = watch('toDate');

  const selectedType = useMemo(
    () => types?.find((type) => String(type.id) === selectedTypeId),
    [types, selectedTypeId],
  );

  const selectedBalance = useMemo(
    () => balances?.find((balance) => String(balance.leaveTypeId) === selectedTypeId),
    [balances, selectedTypeId],
  );

  const singleDay = Boolean(fromDate) && fromDate === toDate;

  const mutation = useMutation({
    mutationFn: (values: FormValues) =>
      leaveApi.apply({
        leaveTypeId: Number(values.leaveTypeId),
        fromDate: values.fromDate,
        toDate: values.toDate,
        fromDayPortion: values.fromDayPortion,
        toDayPortion: singleDay ? values.fromDayPortion : values.toDayPortion,
        reason: values.reason.trim(),
        contactDuringLeave: values.contactDuringLeave || null,
        attachmentUrl: values.attachmentUrl || null,
      }),
    onSuccess: (applied) => {
      queryClient.invalidateQueries({ queryKey: ['leave'] });
      setResult(applied);
    },
  });

  if (result) {
    return (
      <div className="mx-auto max-w-2xl">
        <PageHeader title="Leave submitted" description={result.request.requestNumber} />

        <Alert variant="success" title="Sent for approval" className="mb-lg">
          {result.request.currentApproverName
            ? `${result.request.currentApproverName} has been asked to approve it.`
            : 'It is now awaiting approval.'}
        </Alert>

        {result.countExplanation && (
          <Alert variant="info" title="Why the count differs" className="mb-lg">
            {result.countExplanation}
          </Alert>
        )}

        <div className="card mb-lg p-lg">
          <dl className="grid gap-md sm:grid-cols-2">
            <div>
              <dt className="text-body-sm text-on-surface-variant">Leave type</dt>
              <dd className="text-body-lg text-on-surface">{result.request.leaveTypeName}</dd>
            </div>
            <div>
              <dt className="text-body-sm text-on-surface-variant">Charged</dt>
              <dd className="text-body-lg tabular-nums text-on-surface">
                {result.request.totalDays} of {result.request.calendarDays} calendar days
              </dd>
            </div>
            <div>
              <dt className="text-body-sm text-on-surface-variant">Dates</dt>
              <dd className="text-body-lg text-on-surface">
                {result.request.fromDate} to {result.request.toDate}
              </dd>
            </div>
            <div>
              <dt className="text-body-sm text-on-surface-variant">Balance left</dt>
              <dd className="text-body-lg tabular-nums text-on-surface">
                {result.remainingDays} days
              </dd>
            </div>
          </dl>

          <div className="mt-lg border-t border-outline-variant/60 pt-md">
            <p className="mb-sm text-label-md uppercase text-on-surface-variant">Days</p>
            <ul className="space-y-1">
              {result.request.days.map((day) => (
                <li key={day.date} className="flex items-center justify-between gap-md text-body-sm">
                  <span className="tabular-nums text-on-surface">
                    {day.date} · {day.dayOfWeek.slice(0, 3)}
                  </span>
                  <span className={day.deductible ? 'text-on-surface' : 'text-outline'}>
                    {day.deductible
                      ? day.dayPortion === 'FULL_DAY'
                        ? 'Charged'
                        : 'Half day'
                      : `Not charged · ${day.dayType.replace('_', ' ').toLowerCase()}`}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        </div>

        <div className="flex flex-wrap gap-sm">
          <button type="button" className="btn-secondary" onClick={() => setResult(null)}>
            Apply again
          </button>
          <button type="button" className="btn-primary" onClick={() => navigate(ROUTES.myLeaves)}>
            View my leave
          </button>
        </div>
      </div>
    );
  }

  const failure = mutation.error ? describeFailure(mutation.error) : null;

  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader
        title="Apply for Leave"
        description="Holidays and weekly offs inside the range may not be charged, depending on the leave type."
      />

      {failure && (
        <Alert variant="error" title={failure.title} className="mb-lg">
          {failure.message}
        </Alert>
      )}

      <form
        onSubmit={handleSubmit((values) => mutation.mutate(values))}
        className="card flex flex-col gap-md p-lg"
        noValidate
      >
        <SelectField
          label="Leave type"
          placeholder="Choose a leave type"
          options={(types ?? []).map((type) => ({
            value: type.id,
            label: `${type.name}${type.paid ? '' : ' (unpaid)'}`,
          }))}
          error={errors.leaveTypeId?.message}
          {...register('leaveTypeId', { required: 'Choose a leave type' })}
        />

        {selectedType && selectedBalance && (
          <Alert variant={Number(selectedBalance.availableDays) > 0 ? 'info' : 'warning'}>
            {Number(selectedBalance.availableDays)} day
            {Number(selectedBalance.availableDays) === 1 ? '' : 's'} available.
            {selectedType.minNoticeDays > 0 && !selectedType.exemptFromNotice && (
              <> Needs {selectedType.minNoticeDays} day(s) notice.</>
            )}
            {selectedType.countsHolidaysWeeklyOffs
              ? ' Holidays and weekly offs inside the range are charged.'
              : ' Holidays and weekly offs inside the range are not charged.'}
          </Alert>
        )}

        <div className="grid gap-md sm:grid-cols-2">
          <TextField
            label="From"
            type="date"
            min={selectedType?.allowBackdated ? undefined : isoToday()}
            error={errors.fromDate?.message}
            {...register('fromDate', { required: 'Choose a start date' })}
          />
          <TextField
            label="To"
            type="date"
            min={fromDate || undefined}
            error={errors.toDate?.message}
            {...register('toDate', { required: 'Choose an end date' })}
          />
        </div>

        {selectedType?.allowHalfDay && (
          <div className="grid gap-md sm:grid-cols-2">
            <SelectField
              label={singleDay ? 'Duration' : 'First day'}
              options={[
                { value: 'FULL_DAY', label: 'Full day' },
                { value: 'FIRST_HALF', label: 'First half' },
                { value: 'SECOND_HALF', label: 'Second half' },
              ]}
              {...register('fromDayPortion')}
            />
            {!singleDay && (
              <SelectField
                label="Last day"
                options={[
                  { value: 'FULL_DAY', label: 'Full day' },
                  { value: 'FIRST_HALF', label: 'First half' },
                ]}
                {...register('toDayPortion')}
              />
            )}
          </div>
        )}

        <div>
          <label htmlFor="reason" className="field-label">
            Reason
          </label>
          <textarea
            id="reason"
            rows={3}
            maxLength={1000}
            className="field-input resize-y"
            placeholder="Your approver sees this."
            {...register('reason', { required: 'Give a reason' })}
          />
          {errors.reason && (
            <p className="field-error">
              <Icon name="error" className="text-[14px]" />
              {errors.reason.message}
            </p>
          )}
        </div>

        <div className="grid gap-md sm:grid-cols-2">
          <TextField
            label="Contact while away"
            hint="Optional. How to reach you if something urgent comes up."
            {...register('contactDuringLeave')}
          />
          {(selectedType?.requiresAttachment || selectedType?.attachmentRequiredAfterDays != null) && (
            <TextField
              label="Supporting document"
              hint={
                selectedType.requiresAttachment
                  ? 'Required for this leave type.'
                  : `Required beyond ${selectedType.attachmentRequiredAfterDays} day(s).`
              }
              placeholder="Link to the document"
              {...register('attachmentUrl')}
            />
          )}
        </div>

        <div className="mt-sm flex justify-end gap-sm">
          <button type="button" className="btn-secondary" onClick={() => navigate(-1)}>
            Cancel
          </button>
          <button type="submit" className="btn-primary" disabled={mutation.isPending}>
            {mutation.isPending ? (
              <>
                <Spinner className="h-4 w-4" />
                Submitting
              </>
            ) : (
              <>
                <Icon name="send" className="text-[18px]" />
                Submit request
              </>
            )}
          </button>
        </div>
      </form>
    </div>
  );
};
