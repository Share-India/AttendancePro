package com.company.attendance.dto.response.employee;

import java.util.List;

/**
 * Import outcome. Rows are reported individually rather than failing the whole
 * file: an operator uploading 400 employees needs to know which three were bad,
 * not that "the import failed".
 */
public record BulkImportResultResponse(
        int totalRows,
        int imported,
        int failed,
        List<RowError> errors,
        /**
         * Rows that were imported but not fully wired up — typically a manager
         * code that matches nobody. Kept apart from {@code errors} because the
         * employee does exist: reporting it as a failure would send an operator
         * looking for a record that is already there.
         */
        List<RowError> warnings,

        /**
         * The one-time password generated for each imported employee.
         *
         * <p>Returned because this is the only moment it exists in readable
         * form — it is stored as a BCrypt hash and cannot be recovered
         * afterwards. Without this the importer created accounts nobody could
         * ever sign in to, which is what it did until this was added.
         */
        List<NewCredential> credentials
) {

    /** An account that was just created, and the password to hand over once. */
    public record NewCredential(int rowNumber, String employeeCode, String employeeName,
                                String temporaryPassword) {
    }

    public record RowError(int rowNumber, String employeeCode, String message) {
    }
}
