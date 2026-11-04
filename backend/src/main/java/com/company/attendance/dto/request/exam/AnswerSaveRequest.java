package com.company.attendance.dto.request.exam;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * One answer, as it is given.
 *
 * <p>{@code selectedLabels} are the labels <em>this candidate saw</em>, which
 * are a permutation of the workbook's. Translating them back to canonical
 * labels happens once, on the way in, using the layout stored on the attempt.
 *
 * <p>An empty or absent list clears the answer. There is no correctness flag
 * and no score: the client says what was chosen and nothing more.
 */
public record AnswerSaveRequest(

        @NotNull(message = "Which question is this an answer to?")
        Long questionId,

        List<String> selectedLabels) {
}
