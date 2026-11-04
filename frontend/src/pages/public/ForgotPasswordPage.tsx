import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { Spinner } from '../../components/common/Spinner';
import { TextField } from '../../components/form/TextField';
import { ROUTES } from '../../constants/routes';
import { authApi } from '../../features/auth/authApi';
import { forgotPasswordSchema, type ForgotPasswordFormValues } from '../../features/auth/authSchemas';

export const ForgotPasswordPage = () => {
  const [submittedTo, setSubmittedTo] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ForgotPasswordFormValues>({
    resolver: zodResolver(forgotPasswordSchema),
    defaultValues: { email: '' },
  });

  const mutation = useMutation({
    mutationFn: (values: ForgotPasswordFormValues) => authApi.forgotPassword(values.email),
    onSuccess: (_, values) => setSubmittedTo(values.email),
  });

  // The API answers identically for known and unknown addresses, so the
  // confirmation screen must not imply the account exists.
  if (submittedTo) {
    return (
      <div className="animate-fade-in">
        <Alert variant="success" title="Check your inbox" className="mb-lg">
          If an account exists for <strong>{submittedTo}</strong>, we have sent a reset link. It
          expires in 30 minutes.
        </Alert>

        <p className="mb-lg text-body-md text-on-surface-variant">
          Nothing arrived? Check your spam folder, or{' '}
          <button
            type="button"
            onClick={() => setSubmittedTo(null)}
            className="text-primary underline underline-offset-2 hover:text-primary-container"
          >
            try a different address
          </button>
          .
        </p>

        <Link to={ROUTES.login} className="btn-secondary w-full py-3">
          <Icon name="arrow_back" className="text-[18px]" />
          Back to sign in
        </Link>
      </div>
    );
  }

  return (
    <>
      {mutation.isError && (
        <Alert variant="error" title="Could not send the link" className="mb-lg">
          Something went wrong on our side. Please try again in a moment.
        </Alert>
      )}

      <form
        onSubmit={handleSubmit((values) => mutation.mutate(values))}
        className="flex flex-col gap-md"
        noValidate
      >
        <TextField
          label="Email address"
          type="email"
          leadingIcon="mail"
          placeholder="e.g. john.doe@company.com"
          autoComplete="email"
          autoFocus
          hint="Use the work email address on your employee record."
          error={errors.email?.message}
          {...register('email')}
        />

        <button type="submit" className="btn-primary mt-sm w-full py-3" disabled={mutation.isPending}>
          {mutation.isPending ? (
            <>
              <Spinner className="h-4 w-4" />
              Sending
            </>
          ) : (
            <>
              Send reset link
              <Icon name="send" className="text-[18px]" />
            </>
          )}
        </button>

        <Link
          to={ROUTES.login}
          className="mt-xs inline-flex items-center justify-center gap-1 text-label-md text-primary hover:text-primary-container"
        >
          <Icon name="arrow_back" className="text-[16px]" />
          Back to sign in
        </Link>
      </form>
    </>
  );
};
