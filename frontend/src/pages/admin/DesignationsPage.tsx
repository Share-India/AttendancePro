import { masterApi } from '../../features/admin/masterApi';
import { useAuthStore } from '../../store/authStore';
import type { Designation } from '../../types/master';
import { SimpleMasterPage } from './SimpleMasterPage';

export const DesignationsPage = () => {
  const hasPermission = useAuthStore((state) => state.hasPermission);

  return (
    <SimpleMasterPage<Designation>
      title="Designations"
      description="Job titles and their seniority level. Higher level means more senior."
      entityLabel="designation"
      queryKey="designations"
      canManage={hasPermission('DESIGNATION_MANAGE')}
      fetchAll={masterApi.designations.list}
      create={masterApi.designations.create}
      update={masterApi.designations.update}
      deactivate={masterApi.designations.deactivate}
      extraColumns={[{ header: 'Level', render: (record) => record.level }]}
      extraFields={(record) => (
        <div>
          <label htmlFor="designation-level" className="field-label">
            Level
          </label>
          <input
            id="designation-level"
            name="level"
            type="number"
            min={0}
            max={999}
            defaultValue={record?.level ?? 0}
            className="field-input"
          />
          <p className="mt-unit text-body-sm text-on-surface-variant">
            Used to order titles by seniority, not for access control.
          </p>
        </div>
      )}
      collectExtra={(form) => {
        const level = new FormData(form).get('level');
        return { level: level ? Number(level) : 0 };
      }}
    />
  );
};
