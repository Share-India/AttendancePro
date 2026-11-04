import { z } from 'zod';

/** These mirror the backend DTO constraints, so the same rule fails in both places. */
export const loginSchema = z.object({
  usernameOrEmail: z.string().min(1, 'Enter your email or employee ID').max(150),
  password: z.string().min(1, 'Enter your password').max(128),
  // No .default() here: it would make the schema's input and output types
  // differ, which the resolver's generics reject. The form supplies the default.
  rememberMe: z.boolean(),
});

export const forgotPasswordSchema = z.object({
  email: z.string().min(1, 'Enter your email address').email('Enter a valid email address').max(150),
});

const passwordRules = z
  .string()
  .min(8, 'Use at least 8 characters')
  .max(128, 'That password is too long')
  .regex(/[A-Z]/, 'Include at least one uppercase letter')
  .regex(/[a-z]/, 'Include at least one lowercase letter')
  .regex(/[0-9]/, 'Include at least one number');

export const resetPasswordSchema = z
  .object({
    newPassword: passwordRules,
    confirmPassword: z.string().min(1, 'Confirm your new password'),
  })
  .refine((values) => values.newPassword === values.confirmPassword, {
    message: 'Passwords do not match',
    path: ['confirmPassword'],
  });

export const changePasswordSchema = z
  .object({
    currentPassword: z.string().min(1, 'Enter your current password'),
    newPassword: passwordRules,
    confirmPassword: z.string().min(1, 'Confirm your new password'),
  })
  .refine((values) => values.newPassword === values.confirmPassword, {
    message: 'Passwords do not match',
    path: ['confirmPassword'],
  })
  .refine((values) => values.newPassword !== values.currentPassword, {
    message: 'New password must be different from your current one',
    path: ['newPassword'],
  });

export type LoginFormValues = z.infer<typeof loginSchema>;
export type ForgotPasswordFormValues = z.infer<typeof forgotPasswordSchema>;
export type ResetPasswordFormValues = z.infer<typeof resetPasswordSchema>;
export type ChangePasswordFormValues = z.infer<typeof changePasswordSchema>;
