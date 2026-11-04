-- =====================================================================
-- V13 : Audit columns on leave_approvals
--
-- LeaveApproval extends BaseEntity, which maps created_by and updated_by on
-- every table it backs. V12 gave the approvals table only the timestamps, so
-- `ddl-auto: validate` refused to start.
--
-- A forward migration rather than an edit to V12: V12 has already been applied,
-- and changing an applied migration breaks Flyway's checksum everywhere it ran.
-- =====================================================================

ALTER TABLE leave_approvals
    ADD COLUMN created_by BIGINT UNSIGNED NULL AFTER updated_at,
    ADD COLUMN updated_by BIGINT UNSIGNED NULL AFTER created_by;
