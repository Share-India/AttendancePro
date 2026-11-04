package com.company.attendance.dto.response.exam;

import java.util.Map;

/**
 * What publishing did.
 *
 * <p>The per-set split is returned because it is the one thing worth checking
 * by eye: sixty people should come out 20/20/20, and anything else means the
 * dealing is wrong in a way no later screen would reveal.
 */
public record ExamPublishResponse(
        Long examId,
        int rosterSize,
        int candidateCount,
        int testerCount,
        Map<String, Integer> perSet,
        String message
) {
}
