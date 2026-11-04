import { forwardRef, useId, useState, type InputHTMLAttributes } from 'react';
import { cn } from '../../utils/cn';
import { Icon } from '../common/Icon';

interface TextFieldProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string;
  error?: string;
  /** Material Symbols name rendered inside the field. */
  leadingIcon?: string;
  hint?: string;
}

/**
 * Label always above the field, never a floating label: a floating label
 * disappears once the field has content, which is exactly when someone
 * reviewing a filled form needs to read it.
 */
export const TextField = forwardRef<HTMLInputElement, TextFieldProps>(
  ({ label, error, leadingIcon, hint, type = 'text', className, id, ...props }, ref) => {
    const generatedId = useId();
    const fieldId = id ?? generatedId;
    const errorId = `${fieldId}-error`;
    const hintId = `${fieldId}-hint`;

    const isPassword = type === 'password';
    const [revealed, setRevealed] = useState(false);
    const inputType = isPassword && revealed ? 'text' : type;

    return (
      <div className="flex flex-col">
        <label htmlFor={fieldId} className="field-label">
          {label}
        </label>

        <div className="relative">
          {leadingIcon && (
            <span className="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3 text-outline">
              <Icon name={leadingIcon} className="text-[20px]" />
            </span>
          )}

          <input
            ref={ref}
            id={fieldId}
            type={inputType}
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? errorId : hint ? hintId : undefined}
            className={cn(
              'field-input',
              leadingIcon && 'pl-10',
              isPassword && 'pr-11',
              error && 'field-input-error',
              className,
            )}
            {...props}
          />

          {isPassword && (
            <button
              type="button"
              onClick={() => setRevealed((current) => !current)}
              className="absolute inset-y-0 right-0 flex items-center rounded-r-DEFAULT pr-3 text-outline transition-colors hover:text-on-surface"
              aria-label={revealed ? 'Hide password' : 'Show password'}
              tabIndex={-1}
            >
              <Icon name={revealed ? 'visibility_off' : 'visibility'} className="text-[20px]" />
            </button>
          )}
        </div>

        {error ? (
          <p id={errorId} className="field-error">
            <Icon name="error" className="text-[14px]" />
            {error}
          </p>
        ) : hint ? (
          <p id={hintId} className="mt-unit text-body-sm text-on-surface-variant">
            {hint}
          </p>
        ) : null}
      </div>
    );
  },
);

TextField.displayName = 'TextField';
