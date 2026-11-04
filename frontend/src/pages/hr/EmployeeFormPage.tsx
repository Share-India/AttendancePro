import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { SelectField } from '../../components/form/SelectField';
import { TextField } from '../../components/form/TextField';
import { ROUTES } from '../../constants/routes';
import { useDepartments, useDesignations, useOffices, useShifts } from '../../features/admin/masterQueries';
import { employeeApi } from '../../features/employee/employeeApi';
import {
  employeeFormSchema,
  toCreatePayload,
  toUpdatePayload,
  type EmployeeFormValues,
} from '../../features/employee/employeeSchemas';
import { ApiError } from '../../types/api';
import { EMPLOYMENT_TYPES } from '../../types/employee';

const EMPTY: EmployeeFormValues = {
  employeeCode: '',
  firstName: '',
  middleName: '',
  lastName: '',
  officialEmail: '',
  personalEmail: '',
  mobile: '',
  dateOfBirth: '',
  gender: '',
  joiningDate: '',
  departmentId: '',
  designationId: '',
  officeId: '',
  employmentType: 'PERMANENT',
  reportingManagerId: '',
  shiftId: '',
  emergencyContactName: '',
  emergencyContactNumber: '',
};

export const EmployeeFormPage = () => {
  const { id } = useParams<{ id: string }>();
  const isEdit = Boolean(id);
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [temporaryPassword, setTemporaryPassword] = useState<string | null>(null);

  const { data: departments } = useDepartments();
  const { data: designations } = useDesignations();
  const { data: offices } = useOffices();
  const { data: shifts } = useShifts();

  const { data: existing, isLoading } = useQuery({
    queryKey: ['employees', 'detail', id],
    queryFn: () => employeeApi.findById(Number(id)),
    enabled: isEdit,
  });

  const {
    register,
    handleSubmit,
    reset,
    watch,
    formState: { errors },
  } = useForm<EmployeeFormValues>({
    resolver: zodResolver(employeeFormSchema),
    defaultValues: EMPTY,
  });

  useEffect(() => {
    if (!existing) return;
    reset({
      employeeCode: existing.employeeCode,
      firstName: existing.firstName,
      middleName: existing.middleName ?? '',
      lastName: existing.lastName,
      officialEmail: existing.officialEmail,
      personalEmail: existing.personalEmail ?? '',
      mobile: existing.mobile ?? '',
      dateOfBirth: existing.dateOfBirth ?? '',
      gender: existing.gender ?? '',
      joiningDate: existing.joiningDate,
      departmentId: existing.departmentId ? String(existing.departmentId) : '',
      designationId: existing.designationId ? String(existing.designationId) : '',
      officeId: existing.officeId ? String(existing.officeId) : '',
      employmentType: existing.employmentType,
      reportingManagerId: existing.reportingManagerId ? String(existing.reportingManagerId) : '',
      shiftId: existing.currentShift ? String(existing.currentShift.shiftId) : '',
      emergencyContactName: existing.emergencyContactName ?? '',
      emergencyContactNumber: existing.emergencyContactNumber ?? '',
    });
  }, [existing, reset]);

  /**
   * Candidate managers.
   *
   * <p>Anyone active can be one — the system has no "is a manager" flag, and
   * inventing one here would disagree with the reporting table, which is what
   * actually decides. Sorted by name because a picker ordered by employee code
   * is only usable by someone who has memorised the codes.
   */
  const { data: managerOptions } = useQuery({
    queryKey: ['employees', 'manager-options'],
    queryFn: () => employeeApi.search({ page: 0, size: 200, sort: 'firstName,asc' }),
    staleTime: 5 * 60 * 1000,
  });

  const mutation = useMutation({
    mutationFn: async (values: EmployeeFormValues) => {
      if (isEdit) {
        return employeeApi.update(Number(id), toUpdatePayload(values));
      }
      return employeeApi.create(toCreatePayload(values));
    },
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: ['employees'] });

      if (isEdit) {
        navigate(ROUTES.employeeDetail(Number(id)));
        return;
      }
      // The temporary password is shown once and never again, so the page
      // stays put rather than navigating away with it unread.
      const created = result as { temporaryPassword?: string };
      setTemporaryPassword(created.temporaryPassword ?? null);
    },
  });

  if (isEdit && isLoading) {
    return (
      <div className="flex justify-center py-xl">
        <Spinner className="h-8 w-8 text-primary" />
      </div>
    );
  }

  if (temporaryPassword) {
    return (
      <div className="mx-auto max-w-2xl">
        <PageHeader title="Employee created" description="Share these credentials securely." />

        <Alert variant="success" title="Account provisioned" className="mb-lg">
          The employee must change this password at first sign-in. It is shown only now — it is
          stored hashed and cannot be retrieved later.
        </Alert>

        <div className="card mb-lg p-lg">
          <dl className="space-y-md">
            <div>
              <dt className="text-label-md uppercase text-on-surface-variant">Employee ID</dt>
              <dd className="mt-unit font-mono text-headline-md text-on-surface">
                {(mutation.data as { employee?: { employeeCode: string } })?.employee?.employeeCode}
              </dd>
            </div>
            <div>
              <dt className="text-label-md uppercase text-on-surface-variant">Temporary password</dt>
              <dd className="mt-unit flex items-center gap-sm">
                <code className="rounded-DEFAULT bg-surface-container px-3 py-2 font-mono text-headline-md text-on-surface">
                  {temporaryPassword}
                </code>
                <button
                  type="button"
                  className="btn-ghost"
                  onClick={() => navigator.clipboard?.writeText(temporaryPassword)}
                >
                  <Icon name="content_copy" className="text-[18px]" />
                  Copy
                </button>
              </dd>
            </div>
          </dl>
        </div>

        <div className="flex flex-wrap gap-sm">
          <button
            type="button"
            className="btn-secondary"
            onClick={() => {
              setTemporaryPassword(null);
              reset(EMPTY);
              mutation.reset();
            }}
          >
            <Icon name="person_add" className="text-[18px]" />
            Add another
          </button>
          <button type="button" className="btn-primary" onClick={() => navigate(ROUTES.employees)}>
            Back to directory
          </button>
        </div>
      </div>
    );
  }

  const failure = mutation.error instanceof ApiError ? mutation.error : null;

  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader
        title={isEdit ? `Edit ${existing?.fullName ?? 'employee'}` : 'Add employee'}
        description={
          isEdit
            ? 'Roles, manager and shift are changed from the employee record, each separately audited.'
            : 'Creating an employee also provisions their login with a temporary password.'
        }
      />

      {failure && (
        <Alert variant="error" title="Could not save" className="mb-lg">
          {failure.message}
          {failure.fieldErrors.length > 0 && (
            <ul className="mt-sm list-disc pl-5">
              {failure.fieldErrors.map((fieldError) => (
                <li key={fieldError.field}>
                  {fieldError.field}: {fieldError.message}
                </li>
              ))}
            </ul>
          )}
        </Alert>
      )}

      <form
        onSubmit={handleSubmit((values) => mutation.mutate(values))}
        className="flex flex-col gap-lg"
        noValidate
      >
        <section className="card p-lg">
          <h2 className="mb-md text-headline-md text-on-surface">Identity</h2>
          <div className="grid gap-md sm:grid-cols-2">
            <TextField
              label="Employee ID"
              placeholder="EMP1002"
              disabled={isEdit}
              hint={isEdit ? 'Employee ID cannot change once issued.' : undefined}
              error={errors.employeeCode?.message}
              {...register('employeeCode')}
            />
            <TextField
              label="Official email"
              type="email"
              placeholder="name@company.com"
              error={errors.officialEmail?.message}
              {...register('officialEmail')}
            />
            <TextField label="First name" error={errors.firstName?.message} {...register('firstName')} />
            <TextField label="Middle name" error={errors.middleName?.message} {...register('middleName')} />
            <TextField label="Last name" error={errors.lastName?.message} {...register('lastName')} />
            <TextField
              label="Date of birth"
              type="date"
              error={errors.dateOfBirth?.message}
              {...register('dateOfBirth')}
            />
          </div>
        </section>

        <section className="card p-lg">
          <h2 className="mb-md text-headline-md text-on-surface">Employment</h2>
          <div className="grid gap-md sm:grid-cols-2">
            <TextField
              label="Joining date"
              type="date"
              error={errors.joiningDate?.message}
              {...register('joiningDate')}
            />
            <SelectField
              label="Employment type"
              options={EMPLOYMENT_TYPES.map((type) => ({ value: type, label: type }))}
              error={errors.employmentType?.message}
              {...register('employmentType')}
            />
            <SelectField
              label="Office"
              placeholder="Select an office"
              hint="Sets the geofence candidates and the timezone every date is resolved in."
              options={(offices ?? []).map((office) => ({
                value: office.id,
                label: `${office.name} (${office.timezone})`,
              }))}
              error={errors.officeId?.message}
              {...register('officeId')}
            />
            <SelectField
              label="Department"
              placeholder="No department"
              options={(departments ?? []).map((department) => ({
                value: department.id,
                label: department.name,
              }))}
              error={errors.departmentId?.message}
              {...register('departmentId')}
            />
            <SelectField
              label="Designation"
              placeholder="No designation"
              options={(designations ?? []).map((designation) => ({
                value: designation.id,
                label: designation.name,
              }))}
              error={errors.designationId?.message}
              {...register('designationId')}
            />
            {!isEdit && (
              <SelectField
                label="Reporting manager"
                placeholder="No manager"
                hint="Without one, their leave and work-from-home requests reach nobody."
                options={(managerOptions?.content ?? [])
                  .filter((candidate) => candidate.employeeCode !== watch('employeeCode'))
                  .map((candidate) => ({
                    value: candidate.id,
                    label: `${candidate.fullName} (${candidate.employeeCode})`,
                  }))}
                error={errors.reportingManagerId?.message}
                {...register('reportingManagerId')}
              />
            )}

            {!isEdit && (
              <SelectField
                label="Shift"
                placeholder="Use the default shift"
                hint="Attendance cannot be computed without a shift."
                options={(shifts ?? []).map((shift) => ({
                  value: shift.id,
                  label: `${shift.name} (${shift.startTime}–${shift.endTime})`,
                }))}
                error={errors.shiftId?.message}
                {...register('shiftId')}
              />
            )}
          </div>
        </section>

        <section className="card p-lg">
          <h2 className="mb-md text-headline-md text-on-surface">Contact</h2>
          <div className="grid gap-md sm:grid-cols-2">
            <TextField label="Mobile" error={errors.mobile?.message} {...register('mobile')} />
            <TextField
              label="Personal email"
              type="email"
              error={errors.personalEmail?.message}
              {...register('personalEmail')}
            />
            <TextField
              label="Emergency contact name"
              error={errors.emergencyContactName?.message}
              {...register('emergencyContactName')}
            />
            <TextField
              label="Emergency contact number"
              error={errors.emergencyContactNumber?.message}
              {...register('emergencyContactNumber')}
            />
          </div>
        </section>

        <div className="flex flex-wrap justify-end gap-sm">
          <button type="button" className="btn-secondary" onClick={() => navigate(-1)}>
            Cancel
          </button>
          <button type="submit" className="btn-primary" disabled={mutation.isPending}>
            {mutation.isPending ? (
              <>
                <Spinner className="h-4 w-4" />
                Saving
              </>
            ) : (
              <>
                <Icon name="check" className="text-[18px]" />
                {isEdit ? 'Save changes' : 'Create employee'}
              </>
            )}
          </button>
        </div>
      </form>
    </div>
  );
};
