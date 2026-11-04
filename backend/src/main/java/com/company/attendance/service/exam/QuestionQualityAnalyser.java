package com.company.attendance.service.exam;

import com.company.attendance.dto.response.exam.ExamQualityReport;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Looks for the ways a question paper can be passed without knowing anything.
 *
 * <p>This is not polish. The first real workbook written for this system could
 * be passed at 92% by ticking B twenty times, and at 80% by always choosing the
 * longest option; wrong options contained an absolute word 59% of the time
 * against 7% for correct ones, so "eliminate the absolutes and pick the
 * cautious one" also beat it. It would have run perfectly, produced an
 * excellent pass rate and measured nothing at all.
 *
 * <p>Question quality fails silently, which is why it has to be looked for
 * deliberately rather than noticed. Everything here is a warning: HR can
 * publish regardless, and sometimes should. The point is that nobody publishes
 * twenty B answers without having been told.
 *
 * <p>The structural defence that works even when nobody reads this is shuffling
 * the options per candidate — see {@code Exam.shuffleOptions}.
 */
@Component
public class QuestionQualityAnalyser {

    /**
     * Words that make an option read as too strong to be true. A paper where
     * these cluster in the wrong options can be beaten by eliminating them
     * without understanding the subject.
     */
    private static final Pattern ABSOLUTE = Pattern.compile(
            "\\b(always|never|only|all|none|every|must|cannot|can't|guaranteed|entirely|"
                    + "completely|impossible|no\\s+other|without\\s+exception)\\b",
            Pattern.CASE_INSENSITIVE);

    /** An even spread over four options. */
    private static final double EVEN_SHARE = 0.25;
    /** How far from even an answer letter may drift before it is worth saying. */
    private static final double LETTER_TOLERANCE = 0.15;
    /** Above this, "pick the longest" starts to beat reading the question. */
    private static final double LONGEST_TOLERANCE = 0.40;
    /** A wrong-option absolute rate this far above the correct one is a tell. */
    private static final double ABSOLUTE_GAP = 0.25;
    /** An option this many times longer than its siblings stands out on the page. */
    private static final double LENGTH_RATIO = 3.0;

    public ExamQualityReport analyse(List<ParsedQuestion> questions) {
        List<ExamQualityReport.SetQuality> sets = new ArrayList<>();
        Map<String, List<ParsedQuestion>> bySet = new LinkedHashMap<>();
        for (ParsedQuestion question : questions) {
            bySet.computeIfAbsent(question.setCode(), key -> new ArrayList<>()).add(question);
        }
        bySet.forEach((setCode, inSet) -> sets.add(analyseSet(setCode, inSet)));

        List<String> duplicates = findDuplicates(questions);
        List<String> overall = new ArrayList<>();
        if (!duplicates.isEmpty()) {
            overall.add(duplicates.size() + " question(s) appear in more than one place. "
                    + "A candidate who meets the same question twice is being measured once.");
        }

        boolean clean = duplicates.isEmpty()
                && sets.stream().allMatch(set -> set.warnings().isEmpty());
        return new ExamQualityReport(sets, duplicates, overall, clean);
    }

    private ExamQualityReport.SetQuality analyseSet(String setCode, List<ParsedQuestion> questions) {
        int count = questions.size();
        List<String> warnings = new ArrayList<>();

        Map<String, Integer> spread = new TreeMap<>();
        int correctIsLongest = 0;
        int correctWithAbsolute = 0;
        int wrongWithAbsolute = 0;
        int wrongTotal = 0;
        int lopsided = 0;
        int shortOptionSets = 0;

        for (ParsedQuestion question : questions) {
            spread.merge(question.correctKeyAsText(), 1, Integer::sum);

            if (isLongestOption(question)) {
                correctIsLongest++;
            }
            if (ABSOLUTE.matcher(question.correctOptionText()).find()) {
                correctWithAbsolute++;
            }
            for (String wrong : question.wrongOptionTexts()) {
                wrongTotal++;
                if (ABSOLUTE.matcher(wrong).find()) {
                    wrongWithAbsolute++;
                }
            }
            if (hasLopsidedOptions(question)) {
                lopsided++;
            }
            if (question.options().size() < 4) {
                shortOptionSets++;
            }
        }

        double longestRate = count == 0 ? 0 : (double) correctIsLongest / count;
        double absCorrect = count == 0 ? 0 : (double) correctWithAbsolute / count;
        double absWrong = wrongTotal == 0 ? 0 : (double) wrongWithAbsolute / wrongTotal;

        warnings.addAll(letterWarnings(setCode, spread, count));

        if (longestRate > LONGEST_TOLERANCE) {
            warnings.add(String.format(Locale.ENGLISH,
                    "Set %s: the correct option is the longest one in %d of %d questions (%.0f%%). "
                            + "Always choosing the longest option scores %d of %d here without "
                            + "reading anything. Even out the option lengths.",
                    setCode, correctIsLongest, count, longestRate * 100, correctIsLongest, count));
        }
        if (absWrong - absCorrect > ABSOLUTE_GAP) {
            warnings.add(String.format(Locale.ENGLISH,
                    "Set %s: absolute words such as 'always' or 'never' appear in %.0f%% of wrong "
                            + "options but only %.0f%% of correct ones. Eliminating the absolutes "
                            + "beats knowing the subject.",
                    setCode, absWrong * 100, absCorrect * 100));
        }
        if (lopsided > 0) {
            warnings.add(String.format(Locale.ENGLISH,
                    "Set %s: %d question(s) have one option more than %.0f times the length of the "
                            + "others, which draws the eye before it is read.",
                    setCode, lopsided, LENGTH_RATIO));
        }
        if (shortOptionSets > 0) {
            warnings.add(String.format(Locale.ENGLISH,
                    "Set %s: %d question(s) offer fewer than four options, so a guess is worth "
                            + "more there than elsewhere.",
                    setCode, shortOptionSets));
        }

        return new ExamQualityReport.SetQuality(setCode, count, spread, correctIsLongest,
                longestRate, absCorrect, absWrong, lopsided, shortOptionSets, List.copyOf(warnings));
    }

    private List<String> letterWarnings(String setCode, Map<String, Integer> spread, int count) {
        List<String> warnings = new ArrayList<>();
        if (count == 0) {
            return warnings;
        }
        for (Map.Entry<String, Integer> entry : spread.entrySet()) {
            double share = (double) entry.getValue() / count;
            if (share > EVEN_SHARE + LETTER_TOLERANCE) {
                warnings.add(String.format(Locale.ENGLISH,
                        "Set %s: '%s' is the answer to %d of %d questions (%.0f%%). Ticking it "
                                + "every time scores %d of %d.",
                        setCode, entry.getKey(), entry.getValue(), count, share * 100,
                        entry.getValue(), count));
            }
        }
        return warnings;
    }

    private boolean isLongestOption(ParsedQuestion question) {
        int correctLength = question.correctOptionText().length();
        return question.wrongOptionTexts().stream()
                .allMatch(wrong -> wrong.length() < correctLength);
    }

    private boolean hasLopsidedOptions(ParsedQuestion question) {
        List<Integer> lengths = question.options().values().stream()
                .map(String::length).sorted().toList();
        if (lengths.size() < 2 || lengths.get(0) == 0) {
            return false;
        }
        return (double) lengths.get(lengths.size() - 1) / lengths.get(0) > LENGTH_RATIO;
    }

    /**
     * The same question twice, within a set or across them. Compared on letters
     * and digits alone so a changed comma does not hide it.
     */
    private List<String> findDuplicates(List<ParsedQuestion> questions) {
        Map<String, List<String>> byStem = new LinkedHashMap<>();
        for (ParsedQuestion question : questions) {
            String fingerprint = question.questionText()
                    .toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]", "");
            byStem.computeIfAbsent(fingerprint, key -> new ArrayList<>())
                    .add("Set " + question.setCode() + " Q" + question.questionNumber());
        }
        return byStem.values().stream()
                .filter(places -> places.size() > 1)
                .map(places -> String.join(" and ", places))
                .toList();
    }
}
