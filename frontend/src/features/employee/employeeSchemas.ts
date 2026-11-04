import { z } from 'zod';

// No .transform() here: it would make the schema's input and output types
// differ, which the react-hook-form resolver generics reject. Empty strings
// are converted to null in the payload mappers below instead.
const optionalString = (max: number) => z.string().max(max).optional().or(z.literal(''));

const phone = z
  .string()
  .regex(/^$|^[+0-9 ()-]{6,20}$/, 'Enter a valid phone number')
  .optional()
  .or(z.literal(''));

/** Mirrors EmployeeCreateRequest so the same rule fails on both sides. */
export const employeeFormSchema = z.object({
  employeeCode: z
    .string()
    .min(1, 'Employee ID is required')
    .max(30)
    .regex(/^[A-Za-z0-9_-]+$/, 'Use letters, digits, hyphen or underscore'),
  firstName: z.string().min(1, 'First name is required').max(80),
  middleName: optionalString(80),
  lastName: z.string().min(1, 'Last name is required').max(80),
  officialEmail: z.string().min(1, 'Official email is required').email('Enter a valid email address').max(150),
  personalEmail: z.string().email('Enter a valid email address').max(150).optional().or(z.literal('')),
  mobile: phone,
  dateOfBirth: z.string().optional().or(z.literal('')),
  gender: optionalString(20),
  joiningDate: z.string().min(1, 'Joining date is required'),
  departmentId: z.string().optional().or(z.literal('')),
  designationId: z.string().optional().or(z.literal('')),
  officeId: z.string().min(1, 'Office is required'),
  employmentType: z.string().min(1, 'Employment type is required'),
  reportingManagerId: z.string().optional().or(z.literal('')),
  shiftId: z.string().optional().or(z.literal('')),
  emergencyContactName: optionalString(120),
  emergencyContactNumber: phone,
});

export type EmployeeFormValues = z.infer<typeof employeeFormSchema>;

/** What an employee may change about themselves. */
export const selfProfileSchema = z.object({
  personalEmail: z.string().email('Enter a valid email address').max(150).optional().or(z.literal('')),
  mobile: phone,
  alternateMobile: phone,
  bloodGroup: optionalString(10),
  emergencyContactName: optionalString(120),
  emergencyContactNumber: phone,
});

export type SelfProfileValues = z.infer<typeof selfProfileSchema>;

/** Empty strings become undefined; ids become numbers. */
export const toCreatePayload = (values: EmployeeFormValues): Record<string, unknown> => ({
  employeeCode: values.employeeCode.trim().toUpperCase(),
  firstName: values.firstName.trim(),
  middleName: values.middleName || null,
  lastName: values.lastName.trim(),
  officialEmail: values.officialEmail.trim().toLowerCase(),
  personalEmail: values.personalEmail || null,
  mobile: values.mobile || null,
  dateOfBirth: values.dateOfBirth || null,
  gender: values.gender || null,
  joiningDate: values.joiningDate,
  departmentId: values.departmentId ? Number(values.departmentId) : null,
  designationId: values.designationId ? Number(values.designationId) : null,
  officeId: Number(values.officeId),
  employmentType: values.employmentType,
  reportingManagerId: values.reportingManagerId ? Number(values.reportingManagerId) : null,
  shiftId: values.shiftId ? Number(values.shiftId) : null,
  emergencyContactName: values.emergencyContactName || null,
  emergencyContactNumber: values.emergencyContactNumber || null,
});

export const toUpdatePayload = (values: EmployeeFormValues): Record<string, unknown> => ({
  firstName: values.firstName.trim(),
  middleName: values.middleName || null,
  lastName: values.lastName.trim(),
  officialEmail: values.officialEmail.trim().toLowerCase(),
  personalEmail: values.personalEmail || null,
  mobile: values.mobile || null,
  dateOfBirth: values.dateOfBirth || null,
  gender: values.gender || null,
  joiningDate: values.joiningDate,
  departmentId: values.departmentId ? Number(values.departmentId) : null,
  designationId: values.designationId ? Number(values.designationId) : null,
  officeId: Number(values.officeId),
  employmentType: values.employmentType,
  emergencyContactName: values.emergencyContactName || null,
  emergencyContactNumber: values.emergencyContactNumber || null,
});
