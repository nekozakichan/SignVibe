package com.ucucite.signvibe.ui.quiz;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.firestore.DocumentSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How many items a student answers in one quiz, decided by their grade level
 * so younger learners get a shorter sitting.
 *
 * THE SOURCE OF TRUTH IS FIRESTORE: settings/quiz_limits
 *
 *   { items_by_grade: { "1": 5, "2": 10, "3": 15, "4": 18, "5": 20, "6": 20 },
 *     default_items: 20 }
 *
 * Teachers edit that document from the SignVibe web admin (Manage Modules →
 * Manage Quiz → Edit), and this app reads it — so the rule lives in exactly one
 * place and changing it needs no app release. The table below is a last-resort
 * fallback for a first run with no network; it is not the authority.
 *
 * Load with {@link QuizLimitsRepository}, not by constructing this directly.
 */
public final class QuizLimits {

    private final Map<Integer, Integer> itemsByGrade;
    private final int defaultItems;
    private final boolean fromSettings;

    private QuizLimits(@NonNull Map<Integer, Integer> itemsByGrade,
                       int defaultItems,
                       boolean fromSettings) {
        this.itemsByGrade = itemsByGrade;
        this.defaultItems = defaultItems;
        this.fromSettings = fromSettings;
    }

    /** Offline fallback. Mirrors FALLBACK_ITEMS_BY_GRADE in the web admin. */
    @NonNull
    public static QuizLimits defaults() {
        Map<Integer, Integer> table = new HashMap<>();
        table.put(1, 5);
        table.put(2, 10);
        table.put(3, 15);
        table.put(4, 18);
        table.put(5, 20);
        table.put(6, 20);
        return new QuizLimits(table, 20, false);
    }

    /**
     * Build from settings/quiz_limits. Any grade the document doesn't cover, or
     * covers with a nonsensical value, keeps its built-in number rather than
     * breaking the quiz.
     */
    @NonNull
    static QuizLimits fromSnapshot(@Nullable DocumentSnapshot doc) {
        QuizLimits fallback = defaults();
        if (doc == null || !doc.exists()) return fallback;

        Map<Integer, Integer> table = new HashMap<>(fallback.itemsByGrade);

        Object raw = doc.get("items_by_grade");
        if (raw instanceof Map) {
            Map<?, ?> stored = (Map<?, ?>) raw;
            for (Map.Entry<?, ?> entry : stored.entrySet()) {
                Integer grade = asPositiveInt(entry.getKey());
                Integer items = asPositiveInt(entry.getValue());
                if (grade != null && items != null) table.put(grade, items);
            }
        }

        Integer storedDefault = asPositiveInt(doc.get("default_items"));
        int defaultItems = storedDefault != null ? storedDefault : fallback.defaultItems;

        return new QuizLimits(table, defaultItems, true);
    }

    @Nullable
    private static Integer asPositiveInt(@Nullable Object value) {
        if (value == null) return null;

        int parsed;
        if (value instanceof Number) {
            parsed = ((Number) value).intValue();
        } else {
            Integer digits = digitsOf(value.toString());
            if (digits == null) return null;
            parsed = digits;
        }
        return parsed > 0 ? parsed : null;
    }

    @Nullable
    private static Integer digitsOf(@Nullable String text) {
        if (text == null) return null;

        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c)) digits.append(c);
        }
        if (digits.length() == 0) return null;

        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** True when these came from Firestore rather than the built-in fallback. */
    public boolean isFromSettings() {
        return fromSettings;
    }

    /**
     * Items for this grade. Firestore stores grade_level as a string, and it has
     * been seen as "3", "Grade 3" and "grade_3" — all resolve to 3.
     */
    public int itemsForGrade(@Nullable String gradeLevel) {
        Integer grade = digitsOf(gradeLevel);
        if (grade == null) return defaultItems;

        Integer items = itemsByGrade.get(grade);
        return items != null ? items : defaultItems;
    }

    /**
     * Cut a question pool down to this grade's quiz length.
     *
     * Which items appear is random, so two students in the same grade don't sit
     * the identical paper, but the chosen items stay in their original order —
     * the teacher's sequence is preserved. A pool smaller than the grade's limit
     * is returned whole: the quiz is simply shorter, never padded or repeated.
     */
    @NonNull
    public <T> List<T> limitForGrade(@NonNull List<T> items, @Nullable String gradeLevel) {
        int limit = itemsForGrade(gradeLevel);
        if (items.size() <= limit) return new ArrayList<>(items);

        List<Integer> positions = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) positions.add(i);
        Collections.shuffle(positions);

        List<Integer> chosen = new ArrayList<>(positions.subList(0, limit));
        Collections.sort(chosen);

        List<T> result = new ArrayList<>(limit);
        for (int position : chosen) result.add(items.get(position));
        return result;
    }
}
