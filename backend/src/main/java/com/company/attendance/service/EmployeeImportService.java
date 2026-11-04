package com.company.attendance.service;

import com.company.attendance.audit.AuditLogService;
import com.company.attendance.dto.request.employee.EmployeeCreateRequest;
import com.company.attendance.dto.response.employee.BulkImportResultResponse;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.DepartmentRepository;
import com.company.attendance.repository.DesignationRepository;
import com.company.attendance.dto.request.master.ManagerAssignmentRequest;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.ShiftRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CSV import for onboarding a batch of employees.
 *
 * <p>Rows are imported one transaction at a time rather than all-or-nothing.
 * An operator uploading four hundred people needs to know which three rows were
 * bad and to keep the other 397, not to be told "the import failed".
 */
@Service
public class EmployeeImportService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeImportService.class);

    /** Guards against an accidental upload of a huge file blocking a request thread. */
    private static final int MAX_ROWS = 2000;

    private static final List<String> REQUIRED_HEADERS = List.of(
            "employee_code", "first_name", "last_name", "official_email", "joining_date",
            "office_code", "employment_type");

    private static final List<String> OPTIONAL_HEADERS = List.of(
            "middle_name", "personal_email", "mobile", "date_of_birth", "gender",
            "department_code", "designation_code", "manager_employee_code", "shift_code");

    private final EmployeeService employeeService;
    private final DepartmentRepository departmentRepository;
    private final DesignationRepository designationRepository;
    private final OfficeRepository officeRepository;
    private final EmployeeRepository employeeRepository;
    private final ShiftRepository shiftRepository;
    private final AuditLogService auditLogService;

    public EmployeeImportService(EmployeeService employeeService,
                                 DepartmentRepository departmentRepository,
                                 DesignationRepository designationRepository,
                                 OfficeRepository officeRepository,
                                 EmployeeRepository employeeRepository,
                                 ShiftRepository shiftRepository,
                                 AuditLogService auditLogService) {
        this.employeeService = employeeService;
        this.departmentRepository = departmentRepository;
        this.designationRepository = designationRepository;
        this.officeRepository = officeRepository;
        this.employeeRepository = employeeRepository;
        this.shiftRepository = shiftRepository;
        this.auditLogService = auditLogService;
    }

    public String csvTemplate() {
        List<String> headers = new ArrayList<>(REQUIRED_HEADERS);
        headers.addAll(OPTIONAL_HEADERS);

        // The sample row uses a day past the 12th so the day-first convention
        // is visible at a glance rather than needing to be looked up.
        return String.join(",", headers) + "\n"
                + "EMP1002,Asha,Rao,asha.rao@company.com,2024-04-25,HO,PERMANENT,"
                + ",asha@personal.com,+91 9876543210,1995-06-14,Female,ADMIN-DEPT,SYS-ADMIN,,GEN\n";
    }

    public BulkImportResultResponse importCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Choose a CSV file to upload");
        }

        List<BulkImportResultResponse.RowError> errors = new ArrayList<>();
        List<BulkImportResultResponse.RowError> warnings = new ArrayList<>();

        // Manager links cannot be made on the first pass: a file may well
        // list a manager below their own reports, and that forward reference
        // has to resolve. Collected here and applied once every row exists.
        List<PendingManagerLink> managerLinks = new ArrayList<>();

        // Collected as rows succeed. This is the only point at which the
        // generated passwords are readable, so dropping them here — as this
        // did — leaves every imported account unusable.
        List<BulkImportResultResponse.NewCredential> credentials = new ArrayList<>();

        int imported = 0;
        int rowNumber = 1;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The file is empty");
            }

            Map<String, Integer> columns = parseHeader(headerLine);

            String line;
            while ((line = reader.readLine()) != null) {
                rowNumber++;

                if (line.isBlank()) {
                    continue;
                }
                if (rowNumber - 1 > MAX_ROWS) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "This file has more than " + MAX_ROWS + " rows. Split it into smaller files.");
                }

                String[] values = splitCsvLine(line);
                String employeeCode = value(values, columns, "employee_code");

                try {
                    var created = employeeService.create(toCreateRequest(values, columns));
                    imported++;

                    credentials.add(new BulkImportResultResponse.NewCredential(
                            rowNumber,
                            created.employee().employeeCode(),
                            created.employee().fullName(),
                            created.temporaryPassword()));

                    String managerCode = value(values, columns, "manager_employee_code");
                    if (managerCode != null) {
                        managerLinks.add(new PendingManagerLink(rowNumber, employeeCode, managerCode));
                    }
                } catch (BusinessException e) {
                    // Expected, row-level problems: report and carry on.
                    errors.add(new BulkImportResultResponse.RowError(rowNumber, employeeCode, e.getMessage()));
                } catch (Exception e) {
                    log.error("Unexpected failure importing row {}", rowNumber, e);
                    errors.add(new BulkImportResultResponse.RowError(rowNumber, employeeCode,
                            "Could not import this row. Check the values and try again."));
                }
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Could not read the uploaded file");
        }

        warnings.addAll(applyManagerLinks(managerLinks));

        auditLogService.record(AuditAction.CREATE, "EmployeeImport", null,
                "Bulk import: " + imported + " imported, " + errors.size() + " failed, "
                        + warnings.size() + " partially linked");

        return new BulkImportResultResponse(
                rowNumber - 1, imported, errors.size(), errors, warnings, credentials);
    }

    // -----------------------------------------------------------------

    /** One row's reporting line, held until every employee in the file exists. */
    private record PendingManagerLink(int rowNumber, String employeeCode, String managerCode) {
    }

    /**
     * Links each imported employee to their manager.
     *
     * <p>Runs after the whole file, so a manager listed further down still
     * resolves. A link that cannot be made is a warning rather than an error:
     * the employee was created, and calling the row a failure would send an
     * operator hunting for a record that is already there.
     */
    private List<BulkImportResultResponse.RowError> applyManagerLinks(List<PendingManagerLink> links) {
        List<BulkImportResultResponse.RowError> warnings = new ArrayList<>();

        for (PendingManagerLink link : links) {
            try {
                var employee = employeeRepository.findByEmployeeCode(link.employeeCode().toUpperCase(Locale.ROOT));
                var manager = employeeRepository.findByEmployeeCode(link.managerCode().trim().toUpperCase(Locale.ROOT));

                if (employee.isEmpty()) {
                    continue;
                }
                if (manager.isEmpty()) {
                    warnings.add(new BulkImportResultResponse.RowError(link.rowNumber(),
                            link.employeeCode(),
                            "Imported, but no employee has code '" + link.managerCode()
                                    + "', so no reporting line was created."));
                    continue;
                }

                employeeService.assignManager(employee.get().getId(), new ManagerAssignmentRequest(
                        manager.get().getId(),
                        "PRIMARY",
                        employee.get().getJoiningDate(),
                        null));

            } catch (BusinessException e) {
                // A reporting cycle is the usual cause, and the message says so.
                warnings.add(new BulkImportResultResponse.RowError(link.rowNumber(),
                        link.employeeCode(),
                        "Imported, but the manager was not linked: " + e.getMessage()));
            } catch (Exception e) {
                log.error("Unexpected failure linking manager for row {}", link.rowNumber(), e);
                warnings.add(new BulkImportResultResponse.RowError(link.rowNumber(),
                        link.employeeCode(), "Imported, but the manager could not be linked."));
            }
        }
        return warnings;
    }

    private Map<String, Integer> parseHeader(String headerLine) {
        String[] headers = splitCsvLine(headerLine);
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            columns.put(headers[i].trim().toLowerCase(Locale.ROOT).replace(' ', '_'), i);
        }

        List<String> missing = REQUIRED_HEADERS.stream()
                .filter(header -> !columns.containsKey(header))
                .toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "The file is missing required column(s): " + String.join(", ", missing)
                            + ". Download the template to see the expected format.");
        }
        return columns;
    }

    private EmployeeCreateRequest toCreateRequest(String[] values, Map<String, Integer> columns) {
        String officeCode = required(values, columns, "office_code");
        Long officeId = officeRepository.findByCode(officeCode.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Unknown office code '" + officeCode + "'"))
                .getId();

        Long departmentId = null;
        String departmentCode = value(values, columns, "department_code");
        if (departmentCode != null && !departmentCode.isBlank()) {
            departmentId = departmentRepository.findByCode(departmentCode.toUpperCase(Locale.ROOT))
                    .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "Unknown department code '" + departmentCode + "'"))
                    .getId();
        }

        Long designationId = null;
        String shiftCode = value(values, columns, "shift_code");
        Long shiftId = shiftCode == null ? null
                : shiftRepository.findByCode(shiftCode.trim().toUpperCase(Locale.ROOT))
                        .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR,
                                "Unknown shift code '" + shiftCode + "'"))
                        .getId();

        String designationCode = value(values, columns, "designation_code");
        if (designationCode != null && !designationCode.isBlank()) {
            designationId = designationRepository.findByCode(designationCode.toUpperCase(Locale.ROOT))
                    .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "Unknown designation code '" + designationCode + "'"))
                    .getId();
        }

        return new EmployeeCreateRequest(
                required(values, columns, "employee_code"),
                required(values, columns, "first_name"),
                value(values, columns, "middle_name"),
                required(values, columns, "last_name"),
                required(values, columns, "official_email"),
                value(values, columns, "personal_email"),
                value(values, columns, "mobile"),
                null,
                parseDate(value(values, columns, "date_of_birth"), "date_of_birth"),
                value(values, columns, "gender"),
                null,
                parseDate(required(values, columns, "joining_date"), "joining_date"),
                null,
                departmentId,
                designationId,
                officeId,
                required(values, columns, "employment_type"),
                // The manager is deliberately left null here and linked once the
                // whole file has been read; see applyManagerLinks. The shift is a
                // plain master-data lookup, so it resolves on this pass.
                null,
                shiftId,
                Set.of("EMPLOYEE"),
                null,
                null);
    }

    /**
     * The date formats a spreadsheet actually produces.
     *
     * <p>ISO first, because it is unambiguous. The rest exist because Excel
     * stores a date cell as a number and rewrites it in the machine's locale
     * format every time the file is saved — so an operator who carefully types
     * {@code 2026-09-02} opens the file again and finds {@code 02-09-2026}.
     * Refusing that is technically correct and practically useless: it makes
     * the importer unusable by the very people it exists for.
     *
     * <p><strong>Day-first is assumed</strong> for the two-digit-first forms.
     * {@code 02-09-2026} is the 2nd of September, never the 9th of February.
     * The alternative is guessing, and a silently wrong date of birth is far
     * worse than a rejected row. Anyone on a month-first locale should use the
     * ISO form, which is why it is the one in the template.
     */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("d-M-yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"));

    private LocalDate parseDate(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();

        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(trimmed, format);
            } catch (DateTimeParseException tryTheNextOne) {
                // Expected while walking the list.
            }
        }

        throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                fieldName + " is not a date I recognise: '" + value + "'. "
                        + "Use yyyy-MM-dd (2003-07-15) or dd-MM-yyyy (15-07-2003). "
                        + "Day comes first in the second form.");
    }

    private String required(String[] values, Map<String, Integer> columns, String column) {
        String value = value(values, columns, column);
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, column + " is required");
        }
        return value;
    }

    private String value(String[] values, Map<String, Integer> columns, String column) {
        Integer index = columns.get(column);
        if (index == null || index >= values.length) {
            return null;
        }
        String value = values[index].trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * Minimal CSV split that honours double-quoted fields, so an address or a
     * name containing a comma does not shift every later column by one.
     */
    private String[] splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                // A doubled quote inside a quoted field is a literal quote.
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());

        return fields.toArray(String[]::new);
    }

    /** Exposed for the template endpoint's filename header. */
    public List<String> allHeaders() {
        List<String> headers = new ArrayList<>(REQUIRED_HEADERS);
        headers.addAll(OPTIONAL_HEADERS);
        return Arrays.asList(headers.toArray(String[]::new));
    }
}
