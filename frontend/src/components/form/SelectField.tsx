import { forwardRef, useId, type SelectHTMLAttributes } from 'react';
import { cn } from '../../utils/cn';
import { Icon } from '../common/Icon';

export interface SelectOption {
  value: string | number;
  label: string;
}

interface SelectFieldProps extends SelectHTMLAttributes<HTMLSelectElement> {
  label: string;
  options: SelectOption[];
  error?: string;
  hint?: string;
  placeholder?: string;
}

export const SelectField = forwardRef<HTMLSelectElement, SelectFieldProps>(
  ({ label, options, error, hint, placeholder, className, id, ...props }, ref) => {
    const generatedId = useId();
    const fieldId = id ?? generatedId;
    const errorId = `${fieldId}-error`;

    return (
      <div className="flex flex-col">
        <label htmlFor={fieldId} className="field-label">
          {label}
        </label>

        <div className="relative">
          <select
            ref={ref}
            id={fieldId}
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? errorId : undefined}
            className={cn('field-input appearance-none pr-10', error && 'field-input-error', className)}
            {...props}
          >
            {placeholder && <option value="">{placeholder}</option>}
            {options.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <span className="pointer-events-none absolute inset-y-0 right-0 flex items-center pr-3 text-outline">
            <Icon name="expand_more" className="text-[20px]" />
          </span>
        </div>

        {error ? (
          <p id={errorId} className="field-error">
            <Icon name="error" className="text-[14px]" />
            {error}
          </p>
        ) : hint ? (
          <p className="mt-unit text-body-sm text-on-surface-variant">{hint}</p>
        ) : null}
      </div>
    );
  },
);

SelectField.displayName = 'SelectField';
