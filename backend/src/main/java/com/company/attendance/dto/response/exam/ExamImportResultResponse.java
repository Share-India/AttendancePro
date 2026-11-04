package com.company.attendance.dto.response.exam;

import java.util.List;

/**
 * What an upload found, and whether anything was written.
 *
 * <p>One shape serves both the dry run and the real thing. A commit that finds
 * any problem writes nothing, so there is no state in which two of three sets
 * are loaded — an exam with two sets looks finished and deals a third of the
 * roster a paper that does not exist.
 *
 * @param committed whether the question bank was actually replaced
 * @param accepted  whether the file was good enough to commit
 */
public record ExamImportResultResponse(boolean committed,
                                       boolean accepted,
                                       int questionCount,
                                       int setCount,
                                       List<SetSummary> sets,
                                       List<ImportProblem> problems,
                                       ExamQualityReport quality,
                                       String fileName,
                                       String fileSha256,
                                       String message) {

    public record SetSummary(String setCode, int questionCount, int totalMarks) {
    }
}
