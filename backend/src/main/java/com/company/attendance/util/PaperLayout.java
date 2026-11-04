package com.company.attendance.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The paper one person was dealt: their question order, and the order their
 * options were shown in.
 *
 * <p>Persisted on the attempt rather than regenerated, and that is not an
 * optimisation. Without it the submission cannot be scored, the candidate
 * cannot be shown the paper they actually sat, and a disputed result cannot be
 * settled — a seeded shuffle would only hold until somebody changed the seed or
 * the algorithm.
 *
 * <p>{@code optionOrder} maps a question to its canonical labels in displayed
 * order, so the candidate's "B" is {@code optionOrder.get(questionId).get(1)}.
 * Translating in that direction, once, on the way in, is what keeps the shuffle
 * out of scoring and out of the analytics.
 */
public record PaperLayout(List<Long> questionOrder, Map<Long, List<String>> optionOrder) {

    private static final TypeReference<List<Long>> QUESTION_ORDER = new TypeReference<>() { };
    private static final TypeReference<Map<Long, List<String>>> OPTION_ORDER = new TypeReference<>() { };

    /** The canonical label behind a label the candidate clicked, or null if none. */
    public String canonicalFor(Long questionId, String displayedLabel) {
        List<String> labels = optionOrder.get(questionId);
        if (labels == null || displayedLabel == null || displayedLabel.isBlank()) {
            return null;
        }
        int index = displayedLabel.trim().toUpperCase(Locale.ENGLISH).charAt(0) - 'A';
        return index >= 0 && index < labels.size() ? labels.get(index) : null;
    }

    /** The label this candidate saw against a canonical one, for showing their paper back. */
    public String displayedFor(Long questionId, String canonicalLabel) {
        List<String> labels = optionOrder.get(questionId);
        if (labels == null || canonicalLabel == null) {
            return null;
        }
        int index = labels.indexOf(canonicalLabel);
        return index < 0 ? null : String.valueOf((char) ('A' + index));
    }

    public List<String> canonicalFor(Long questionId, List<String> displayedLabels) {
        List<String> canonical = new ArrayList<>();
        if (displayedLabels == null) {
            return canonical;
        }
        for (String displayed : displayedLabels) {
            String label = canonicalFor(questionId, displayed);
            if (label != null && !canonical.contains(label)) {
                canonical.add(label);
            }
        }
        canonical.sort(String::compareTo);
        return canonical;
    }

    public String questionOrderJson(ObjectMapper mapper) {
        return write(mapper, questionOrder);
    }

    public String optionOrderJson(ObjectMapper mapper) {
        return write(mapper, optionOrder);
    }

    public static PaperLayout from(ObjectMapper mapper, String questionOrderJson, String optionOrderJson) {
        List<Long> questions = read(mapper, questionOrderJson, QUESTION_ORDER, new ArrayList<>());
        Map<Long, List<String>> options = read(mapper, optionOrderJson, OPTION_ORDER, new LinkedHashMap<>());
        return new PaperLayout(questions, options);
    }

    private static String write(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception failed) {
            throw new IllegalStateException("The paper layout could not be stored", failed);
        }
    }

    private static <T> T read(ObjectMapper mapper, String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception failed) {
            throw new IllegalStateException("The stored paper layout could not be read", failed);
        }
    }
}
