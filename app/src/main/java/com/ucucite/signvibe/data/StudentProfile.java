package com.ucucite.signvibe.data;

import androidx.annotation.NonNull;

import com.google.firebase.firestore.DocumentSnapshot;

public class StudentProfile {

    private final String fullName;
    private final String email;
    private final String gradeLevel;
    private final String section;
    private final int lessonsCompleted;
    private final int totalPoints;

    public StudentProfile(String fullName, String email, String gradeLevel, String section,
                          int lessonsCompleted, int totalPoints) {
        this.fullName = fullName;
        this.email = email;
        this.gradeLevel = gradeLevel;
        this.section = section;
        this.lessonsCompleted = lessonsCompleted;
        this.totalPoints = totalPoints;
    }

    @NonNull
    public static StudentProfile fromFirestore(@NonNull DocumentSnapshot doc) {
        String fullName = doc.getString("full_name");
        String email = doc.getString("email");
        String gradeLevel = doc.getString("grade_level");
        String section = doc.getString("section");
        Long lessonsCompleted = doc.getLong("lessons_completed");
        Long totalPoints = doc.getLong("total_points");

        return new StudentProfile(
                fullName != null ? fullName : "",
                email != null ? email : "",
                gradeLevel != null ? gradeLevel : "",
                section != null ? section : "",
                lessonsCompleted != null ? lessonsCompleted.intValue() : 0,
                totalPoints != null ? totalPoints.intValue() : 0
        );
    }

    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public String getGradeLevel() { return gradeLevel; }
    public String getSection() { return section; }
    public int getLessonsCompleted() { return lessonsCompleted; }
    public int getTotalPoints() { return totalPoints; }

    /** "naomi suzume" -> "Naomi Suzume" — display formatting only, doesn't touch stored data. */
    public String getDisplayName() {
        if (fullName.isEmpty()) return "Learner";
        StringBuilder sb = new StringBuilder();
        for (String word : fullName.trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.length() > 1 ? word.substring(1).toLowerCase() : "");
        }
        return sb.length() > 0 ? sb.toString() : "Learner";
    }
}