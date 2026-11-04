package com.company.attendance.service.exam;

import com.company.attendance.enums.ExamDifficulty;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One question as it came out of the workbook, before anything is written.
 *
 * <p>A parse result rather than an entity, so a file can be checked from end to
 * end and then discarded without a half-loaded question bank existing at any
 * point.
 *
 * @param options       canonical label to option text, in workbook order
 * @param correctLabels sorted, so a comparison never depends on the order typed
 */
public record ParsedQuestion(String setCode,
                             int rowNumber,
                             int questionNumber,
                             String topic,
                             ExamDifficulty difficulty,
                             String questionText,
                             Map<String, String> options,
                             List<String> correctLabels,
                             String explanation,
                             String source,
                             Integer marks) {

    public boolean isMultiChoice() {
        return correctLabels.size() > 1;
    }

    public String correctKeyAsText() {
        return String.join(",", correctLabels);
    }

    public String correctOptionText() {
        return correctLabels.stream()
                .map(options::get)
                .filter(Objects::nonNull)
                .reduce((a, b) -> a + " " + b)
                .orElse("");
    }

    public List<String> wrongOptionTexts() {
        return options.entrySet().stream()
                .filter(entry -> !correctLabels.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
    }
}
