import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { Spinner } from '../../components/common/Spinner';
import { TextField } from '../../components/form/TextField';
import { ROUTES } from '../../constants/routes';
import { authApi } from '../../features/auth/authApi';
import { resetPasswordSchema, type ResetPasswordFormValues } from '../../features/auth/authSchemas';
import { ApiError } from '../../types/api';

const describeFailure = (error: unknown): string => {
  if (!(error instanceof ApiError)) {
    return 'Something went wrong. Please request a new link.';
  }
  switch (error.errorCode) {
    case 'RESET_TOKEN_EXPIRED':
      return 'This link has expired. Request a new one and use it within 30 minutes.';
    case 'RESET_TOKEN_INVALID':
      return 'This link is not valid, or it has already been used. Request a new one.';
    case 'PASSWORD_REUSED':
      return 'Choose a password you have not used before.';
    default:
      return error.message;
  }
};

export const ResetPasswordPage = () => {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const token = searchParams.get('token');

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ResetPasswordFormValues>({
    resolver: zodResolver(resetPasswordSchema),
    defaultValues: { newPassword: '', confirmPassword: '' },
  });

  const mutation = useMutation({
    mutationFn: (values: ResetPasswordFormValues) =>
      authApi.resetPassword({
        token: token ?? '',
        newPassword: values.newPassword,
        confirmPassword: values.confirmPassword,
      }),
    onSuccess: () => navigate(ROUTES.login, { replace: true }),
  });

  if (!token) {
    return (
      <>
        <Alert variant="error" title="Link is incomplete" className="mb-lg">
          This reset link is missing its token. Open the link from your email exactly as it was sent,
          or request a new one.
        </Alert>
        <Link to={ROUTES.forgotPassword} className="btn-primary w-full py-3">
          Request a new link
        </Link>
      </>
    );
  }

  return (
    <>
      {mutation.isError && (
        <Alert variant="error" title="Could not reset your password" className="mb-lg">
          {describeFailure(mutation.error)}
        </Alert>
      )}

      <form
        onSubmit={handleSubmit((values) => mutation.mutate(values))}
        className="flex flex-col gap-md"
        noValidate
      >
        <TextField
          label="New password"
          type="password"
          leadingIcon="lock"
          placeholder="Enter a new password"
          autoComplete="new-password"
          autoFocus
          hint="At least 8 characters, with an uppercase letter, a lowercase letter and a number."
          error={errors.newPassword?.message}
          {...register('newPassword')}
        />

        <TextField
          label="Confirm new password"
          type="password"
          leadingIcon="lock_reset"
          placeholder="Re-enter the new password"
          autoComplete="new-password"
          error={errors.confirmPassword?.message}
          {...register('confirmPassword')}
        />

        <Alert variant="info" className="mt-xs">
          Resetting your password signs you out everywhere. You will need to sign in again on each
          device.
        </Alert>

        <button type="submit" className="btn-primary mt-sm w-full py-3" disabled={mutation.isPending}>
          {mutation.isPending ? (
            <>
              <Spinner className="h-4 w-4" />
              Updating
            </>
          ) : (
            <>
              Set new password
              <Icon name="check" className="text-[18px]" />
            </>
          )}
        </button>
      </form>
    </>
  );
};
