import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { Spinner } from '../../components/common/Spinner';
import { TextField } from '../../components/form/TextField';
import { ROUTES } from '../../constants/routes';
import { authApi } from '../../features/auth/authApi';
import { changePasswordSchema, type ChangePasswordFormValues } from '../../features/auth/authSchemas';
import { useAuthStore } from '../../store/authStore';
import { ApiError } from '../../types/api';

const describeFailure = (error: unknown): string => {
  if (!(error instanceof ApiError)) return 'Something went wrong. Please try again.';
  switch (error.errorCode) {
    case 'PASSWORD_MISMATCH':
      return 'Your current password is not correct.';
    case 'PASSWORD_REUSED':
      return 'Choose a password different from your current one.';
    default:
      return error.message;
  }
};

export const ChangePasswordPage = () => {
  const navigate = useNavigate();
  const user = useAuthStore((state) => state.user);
  const clearSession = useAuthStore((state) => state.clearSession);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ChangePasswordFormValues>({
    resolver: zodResolver(changePasswordSchema),
    defaultValues: { currentPassword: '', newPassword: '', confirmPassword: '' },
  });

  const mutation = useMutation({
    mutationFn: (values: ChangePasswordFormValues) => authApi.changePassword(values),
    onSuccess: () => {
      // The API revokes every session on a password change, so the only correct
      // next step is a fresh sign-in.
      clearSession();
      navigate(ROUTES.login, { replace: true });
    },
  });

  return (
    <div className="mx-auto max-w-xl">
      <header className="mb-lg">
        <h1 className="text-headline-lg text-on-surface">Change password</h1>
        <p className="mt-xs text-body-md text-on-surface-variant">
          {user?.mustChangePassword
            ? 'Set your own password before continuing.'
            : 'Update the password for your account.'}
        </p>
      </header>

      {user?.mustChangePassword && (
        <Alert variant="warning" title="Password change required" className="mb-lg">
          This account was created with a temporary password. Choose your own to continue.
        </Alert>
      )}

      {mutation.isError && (
        <Alert variant="error" title="Could not change your password" className="mb-lg">
          {describeFailure(mutation.error)}
        </Alert>
      )}

      <form
        onSubmit={handleSubmit((values) => mutation.mutate(values))}
        className="card flex flex-col gap-md p-lg"
        noValidate
      >
        <TextField
          label="Current password"
          type="password"
          leadingIcon="lock"
          autoComplete="current-password"
          error={errors.currentPassword?.message}
          {...register('currentPassword')}
        />
        <TextField
          label="New password"
          type="password"
          leadingIcon="lock_reset"
          autoComplete="new-password"
          hint="At least 8 characters, with an uppercase letter, a lowercase letter and a number."
          error={errors.newPassword?.message}
          {...register('newPassword')}
        />
        <TextField
          label="Confirm new password"
          type="password"
          leadingIcon="check_circle"
          autoComplete="new-password"
          error={errors.confirmPassword?.message}
          {...register('confirmPassword')}
        />

        <Alert variant="info">
          Changing your password signs you out on every device. You will sign in again afterwards.
        </Alert>

        <button type="submit" className="btn-primary mt-xs py-3" disabled={mutation.isPending}>
          {mutation.isPending ? (
            <>
              <Spinner className="h-4 w-4" />
              Updating
            </>
          ) : (
            <>
              Update password
              <Icon name="check" className="text-[18px]" />
            </>
          )}
        </button>
      </form>
    </div>
  );
};
