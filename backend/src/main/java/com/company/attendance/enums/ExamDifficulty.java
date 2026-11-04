package com.company.attendance.enums;

/**
 * Optional tag from the workbook, used for grouping in the analysis.
 *
 * <p>An unrecognised value imports as null rather than being guessed at: a
 * wrong tag is worse than no tag, because it is read as evidence.
 */
public enum ExamDifficulty {
    EASY,
    MODERATE,
    HARD
}
