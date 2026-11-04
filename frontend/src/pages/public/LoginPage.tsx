import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link } from 'react-router-dom';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { Spinner } from '../../components/common/Spinner';
import { TextField } from '../../components/form/TextField';
import { ROUTES } from '../../constants/routes';
import { loginSchema, type LoginFormValues } from '../../features/auth/authSchemas';
import { useAuth } from '../../features/auth/useAuth';
import { ApiError } from '../../types/api';

/**
 * Failures are explained in the terms the person can act on. "Invalid
 * credentials" is deliberately the same message for a wrong password and an
 * unknown account — the API does not reveal which, and neither does this.
 */
const describeFailure = (error: unknown): { title: string; message: string } => {
  if (!(error instanceof ApiError)) {
    return { title: 'Sign-in failed', message: 'Something went wrong. Please try again.' };
  }

  switch (error.errorCode) {
    case 'INVALID_CREDENTIALS':
      return {
        title: 'Check your details',
        message: 'That email or employee ID and password do not match. Try again.',
      };
    case 'ACCOUNT_LOCKED':
      return { title: 'Account locked', message: error.message };
    case 'ACCOUNT_INACTIVE':
      return {
        title: 'Account inactive',
        message: 'This account has been deactivated. Contact HR to restore access.',
      };
    case 'EMPLOYEE_INACTIVE':
      return {
        title: 'Employee record inactive',
        message: 'Your employee record is no longer active. Contact HR.',
      };
    case 'RATE_LIMIT_EXCEEDED':
      return { title: 'Too many attempts', message: 'Wait a moment before trying again.' };
    default:
      return { title: 'Sign-in failed', message: error.message };
  }
};

export const LoginPage = () => {
  const { login, isLoggingIn, loginError, resetLoginError } = useAuth();

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { usernameOrEmail: '', password: '', rememberMe: false },
  });

  const onSubmit = async (values: LoginFormValues) => {
    try {
      await login(values);
    } catch {
      // Surfaced through loginError; the mutation already captured it.
    }
  };

  const failure = loginError ? describeFailure(loginError) : null;

  return (
    <>
      {failure && (
        <Alert variant="error" title={failure.title} className="mb-lg">
          {failure.message}
        </Alert>
      )}

      <form onSubmit={handleSubmit(onSubmit)} className="flex flex-col gap-md" noValidate>
        <TextField
          label="Email or Employee ID"
          leadingIcon="person"
          placeholder="e.g. john.doe@company.com"
          autoComplete="username"
          autoFocus
          error={errors.usernameOrEmail?.message}
          {...register('usernameOrEmail', { onChange: () => resetLoginError() })}
        />

        <TextField
          label="Password"
          type="password"
          leadingIcon="lock"
          placeholder="Enter your password"
          autoComplete="current-password"
          error={errors.password?.message}
          {...register('password', { onChange: () => resetLoginError() })}
        />

        <div className="mt-sm flex items-center justify-between">
          <label className="group flex cursor-pointer items-center gap-2">
            <input
              type="checkbox"
              className="h-4 w-4 cursor-pointer rounded-sm border-outline-variant text-primary focus:ring-primary"
              {...register('rememberMe')}
            />
            <span className="text-body-sm text-on-surface-variant transition-colors group-hover:text-on-surface">
              Remember Me
            </span>
          </label>

          <Link
            to={ROUTES.forgotPassword}
            className="text-label-md text-primary transition-colors hover:text-primary-container"
          >
            Forgot Password?
          </Link>
        </div>

        <button type="submit" className="btn-primary mt-sm w-full py-3" disabled={isLoggingIn}>
          {isLoggingIn ? (
            <>
              <Spinner className="h-4 w-4" />
              Signing in
            </>
          ) : (
            <>
              Sign In
              <Icon name="login" className="text-[18px]" />
            </>
          )}
        </button>
      </form>
    </>
  );
};
