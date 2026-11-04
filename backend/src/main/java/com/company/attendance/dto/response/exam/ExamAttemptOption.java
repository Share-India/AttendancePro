package com.company.attendance.dto.response.exam;

/**
 * One option on a live paper: a label and some text, and nothing else.
 *
 * <p>The label is this candidate's own — their options were shuffled, so their
 * "B" is not necessarily the workbook's B. See {@link ExamAttemptQuestion} for
 * why there is no correctness flag here.
 */
public record ExamAttemptOption(String label, String text) {
}
