import { masterApi } from '../../features/admin/masterApi';
import { useAuthStore } from '../../store/authStore';
import type { Department } from '../../types/master';
import { SimpleMasterPage } from './SimpleMasterPage';

export const DepartmentsPage = () => {
  const hasPermission = useAuthStore((state) => state.hasPermission);

  return (
    <SimpleMasterPage<Department>
      title="Departments"
      description="Department master. A department with active employees cannot be deactivated."
      entityLabel="department"
      queryKey="departments"
      canManage={hasPermission('DEPARTMENT_MANAGE')}
      fetchAll={masterApi.departments.list}
      create={masterApi.departments.create}
      update={masterApi.departments.update}
      deactivate={masterApi.departments.deactivate}
      extraColumns={[
        {
          header: 'Head',
          render: (record) => record.departmentHeadName ?? <span className="text-outline">—</span>,
        },
      ]}
    />
  );
};
