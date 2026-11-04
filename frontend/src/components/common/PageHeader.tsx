import type { ReactNode } from 'react';

interface PageHeaderProps {
  title: string;
  description?: string;
  actions?: ReactNode;
}

export const PageHeader = ({ title, description, actions }: PageHeaderProps) => (
  <header className="mb-lg flex flex-col gap-md sm:flex-row sm:items-start sm:justify-between">
    <div className="min-w-0">
      <h1 className="text-headline-lg text-on-surface">{title}</h1>
      {description && <p className="mt-xs text-body-md text-on-surface-variant">{description}</p>}
    </div>
    {actions && <div className="flex shrink-0 flex-wrap gap-sm">{actions}</div>}
  </header>
);
