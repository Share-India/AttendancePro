import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { ROUTES } from '../../constants/routes';
import { leaveApi } from '../../features/leave/leaveApi';
import { ApiError } from '../../types/api';
import type { LeaveBalance } from '../../types/leave';

/** Trims trailing zeros so 8.0 reads as 8 and 0.5 stays 0.5. */
const days = (value: number): string => Number(value).toString();

const BalanceCard = ({ balance }: { balance: LeaveBalance }) => {
  const entitled = Number(balance.entitledDays);
  const used = Number(balance.usedDays);
  const pending = Number(balance.pendingDays);
  const available = Number(balance.availableDays);

  const usedPercent = entitled > 0 ? Math.min(100, (used / entitled) * 100) : 0;
  const pendingPercent = entitled > 0 ? Math.min(100 - usedPercent, (pending / entitled) * 100) : 0;

  const exhausted = available <= 0;

  return (
    <article className="card flex flex-col p-lg">
      <header className="mb-md flex items-start justify-between gap-sm">
        <div className="min-w-0">
          <h2 className="text-headline-md text-on-surface">{balance.leaveTypeName}</h2>
          <p className="mt-unit font-mono text-body-sm text-on-surface-variant">
            {balance.leaveTypeCode}
            {!balance.paid && <span className="ml-2 text-warning">unpaid</span>}
          </p>
        </div>
        <div className="text-right">
          <p
            className={`text-display-lg leading-none tabular-nums ${
              exhausted ? 'text-outline' : 'text-primary'
            }`}
          >
            {days(available)}
          </p>
          <p className="text-body-sm uppercase text-on-surface-variant">remaining</p>
        </div>
      </header>

      {/* Used and reserved are shown as separate segments: an employee whose
          balance looks low deserves to see that some of it is only held. */}
      <div className="mb-sm h-2 w-full overflow-hidden rounded-full bg-surface-container-high">
        <div className="flex h-full">
          <div className="h-full bg-primary" style={{ width: `${usedPercent}%` }} />
          <div className="h-full bg-warning/60" style={{ width: `${pendingPercent}%` }} />
        </div>
      </div>

      <dl className="grid flex-1 grid-cols-3 gap-sm border-t border-outline-variant/60 pt-md">
        <div>
          <dt className="text-body-sm text-on-surface-variant">Entitled</dt>
          <dd className="text-body-lg tabular-nums text-on-surface">{days(entitled)}</dd>
        </div>
        <div>
          <dt className="text-body-sm text-on-surface-variant">Used</dt>
          <dd className="text-body-lg tabular-nums text-on-surface">{days(used)}</dd>
        </div>
        <div>
          <dt className="text-body-sm text-on-surface-variant">Awaiting</dt>
          <dd
            className={`text-body-lg tabular-nums ${pending > 0 ? 'text-warning' : 'text-on-surface'}`}
          >
            {days(pending)}
          </dd>
        </div>
      </dl>

      {pending > 0 && (
        <p className="mt-sm text-body-sm text-on-surface-variant">
          {days(pending)} day{pending === 1 ? '' : 's'} held by a request not yet decided.
        </p>
      )}
    </article>
  );
};

export const LeaveBalancePage = () => {
  const navigate = useNavigate();

  const { data, isLoading, error } = useQuery({
    queryKey: ['leave', 'balances'],
    queryFn: leaveApi.balances,
  });

  if (isLoading) {
    return (
      <div className="flex justify-center py-xl">
        <Spinner className="h-8 w-8 text-primary" />
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-[1280px]">
      <PageHeader
        title="Leave Balance"
        description="What you are entitled to, what you have taken, and what is held by requests still awaiting a decision."
        actions={
          <button type="button" className="btn-primary" onClick={() => navigate(ROUTES.applyLeave)}>
            <Icon name="post_add" className="text-[18px]" />
            Apply for leave
          </button>
        }
      />

      {error && (
        <Alert variant="error" title="Could not load your balances" className="mb-lg">
          {error instanceof ApiError ? error.message : 'Please try again in a moment.'}
        </Alert>
      )}

      <div className="grid gap-gutter md:grid-cols-2 xl:grid-cols-3">
        {(data ?? []).map((balance) => (
          <BalanceCard key={balance.leaveTypeId} balance={balance} />
        ))}
      </div>

      {data && data.length > 0 && (
        <p className="mt-lg text-body-sm text-outline">
          Balances are for leave year {data[0].leaveYear}. A mid-year joining date reduces the
          entitlement proportionally.
        </p>
      )}
    </div>
  );
};
