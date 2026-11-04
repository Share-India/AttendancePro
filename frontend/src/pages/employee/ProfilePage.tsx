import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { StatusChip, employmentStatusTone } from '../../components/common/StatusChip';
import { TextField } from '../../components/form/TextField';
import { employeeApi } from '../../features/employee/employeeApi';
import { selfProfileSchema, type SelfProfileValues } from '../../features/employee/employeeSchemas';
import { ApiError } from '../../types/api';

const ReadOnly = ({ label, value }: { label: string; value: string | null | undefined }) => (
  <div>
    <dt className="text-body-sm text-on-surface-variant">{label}</dt>
    <dd className="mt-0.5 text-body-md text-on-surface">{value || <span className="text-outline">—</span>}</dd>
  </div>
);

export const ProfilePage = () => {
  const queryClient = useQueryClient();

  const { data, isLoading, error } = useQuery({
    queryKey: ['employees', 'me'],
    queryFn: () => employeeApi.me(),
  });

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isDirty },
  } = useForm<SelfProfileValues>({
    resolver: zodResolver(selfProfileSchema),
    defaultValues: {
      personalEmail: '',
      mobile: '',
      alternateMobile: '',
      bloodGroup: '',
      emergencyContactName: '',
      emergencyContactNumber: '',
    },
  });

  useEffect(() => {
    if (!data) return;
    reset({
      personalEmail: data.personalEmail ?? '',
      mobile: data.mobile ?? '',
      alternateMobile: data.alternateMobile ?? '',
      bloodGroup: data.bloodGroup ?? '',
      emergencyContactName: data.emergencyContactName ?? '',
      emergencyContactNumber: data.emergencyContactNumber ?? '',
    });
  }, [data, reset]);

  const mutation = useMutation({
    mutationFn: (values: SelfProfileValues) =>
      employeeApi.updateMe({
        personalEmail: values.personalEmail || null,
        mobile: values.mobile || null,
        alternateMobile: values.alternateMobile || null,
        bloodGroup: values.bloodGroup || null,
        emergencyContactName: values.emergencyContactName || null,
        emergencyContactNumber: values.emergencyContactNumber || null,
      }),
    onSuccess: (updated) => {
      queryClient.setQueryData(['employees', 'me'], updated);
      reset({
        personalEmail: updated.personalEmail ?? '',
        mobile: updated.mobile ?? '',
        alternateMobile: updated.alternateMobile ?? '',
        bloodGroup: updated.bloodGroup ?? '',
        emergencyContactName: updated.emergencyContactName ?? '',
        emergencyContactNumber: updated.emergencyContactNumber ?? '',
      });
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
    return (
      <div className="mx-auto max-w-2xl">
        <Alert variant="error" title="Could not load your profile">
          {error instanceof ApiError ? error.message : 'Please try again in a moment.'}
        </Alert>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-[1280px]">
      <PageHeader
        title="My Profile"
        description="Your employee record. Contact details are yours to update; the rest is maintained by HR."
      />

      {mutation.isSuccess && !isDirty && (
        <Alert variant="success" className="mb-lg">
          Your details have been saved.
        </Alert>
      )}

      {mutation.isError && (
        <Alert variant="error" title="Could not save" className="mb-lg">
          {mutation.error instanceof ApiError ? mutation.error.message : 'Please try again.'}
        </Alert>
      )}

      <div className="grid gap-gutter lg:grid-cols-3">
        <section className="card p-lg lg:col-span-1">
          <div className="flex flex-col items-center gap-sm text-center">
            {data.profilePhotoUrl ? (
              <img src={data.profilePhotoUrl} alt="" className="h-24 w-24 rounded-full object-cover" />
            ) : (
              <span
                className="flex h-24 w-24 items-center justify-center rounded-full bg-primary-fixed text-display-lg text-on-primary-fixed"
                aria-hidden="true"
              >
                {data.fullName.charAt(0).toUpperCase()}
              </span>
            )}
            <p className="text-headline-md text-on-surface">{data.fullName}</p>
            <p className="font-mono text-body-sm text-on-surface-variant">{data.employeeCode}</p>
            <StatusChip
              label={data.employmentStatus.replace('_', ' ')}
              tone={employmentStatusTone(data.employmentStatus)}
            />
          </div>

          <dl className="mt-lg space-y-md border-t border-outline-variant/60 pt-lg">
            <ReadOnly label="Department" value={data.departmentName} />
            <ReadOnly label="Designation" value={data.designationName} />
            <ReadOnly label="Office" value={data.officeName} />
            <ReadOnly label="Reporting manager" value={data.reportingManagerName} />
            <ReadOnly label="Joining date" value={data.joiningDate} />
            <ReadOnly label="Employment type" value={data.employmentType} />
            <ReadOnly
              label="Shift"
              value={
                data.currentShift
                  ? `${data.currentShift.shiftName} (${data.currentShift.startTime}–${data.currentShift.endTime})`
                  : null
              }
            />
          </dl>

          <p className="mt-lg text-body-sm text-outline">
            Something wrong above? These fields affect attendance and leave, so HR maintains them.
          </p>
        </section>

        <form
          onSubmit={handleSubmit((values) => mutation.mutate(values))}
          className="card flex flex-col gap-md p-lg lg:col-span-2"
          noValidate
        >
          <h2 className="text-headline-md text-on-surface">Contact details</h2>

          <div className="grid gap-md sm:grid-cols-2">
            <TextField
              label="Personal email"
              type="email"
              leadingIcon="mail"
              error={errors.personalEmail?.message}
              {...register('personalEmail')}
            />
            <TextField
              label="Mobile"
              leadingIcon="phone"
              error={errors.mobile?.message}
              {...register('mobile')}
            />
            <TextField
              label="Alternate mobile"
              leadingIcon="phone_forwarded"
              error={errors.alternateMobile?.message}
              {...register('alternateMobile')}
            />
            <TextField
              label="Blood group"
              leadingIcon="bloodtype"
              placeholder="e.g. O+"
              error={errors.bloodGroup?.message}
              {...register('bloodGroup')}
            />
            <TextField
              label="Emergency contact name"
              leadingIcon="contact_emergency"
              error={errors.emergencyContactName?.message}
              {...register('emergencyContactName')}
            />
            <TextField
              label="Emergency contact number"
              leadingIcon="call"
              error={errors.emergencyContactNumber?.message}
              {...register('emergencyContactNumber')}
            />
          </div>

          <div className="mt-sm flex justify-end">
            <button type="submit" className="btn-primary" disabled={mutation.isPending || !isDirty}>
              {mutation.isPending ? (
                <>
                  <Spinner className="h-4 w-4" />
                  Saving
                </>
              ) : (
                <>
                  <Icon name="check" className="text-[18px]" />
                  Save changes
                </>
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
