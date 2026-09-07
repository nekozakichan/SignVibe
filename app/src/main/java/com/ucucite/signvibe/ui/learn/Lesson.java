package com.ucucite.signvibe.ui.learn;

import androidx.annotation.NonNull;

import com.google.firebase.firestore.DocumentSnapshot;

public class Lesson {

    private final String id;
    private final String moduleId;
    private final String title;
    private final String description;
    private final String videoUrl;
    private final int order;
    private boolean completed; // overridden later from student progress data
    private boolean locked;    // true when the previous lesson isn't done yet

    public Lesson(String id, String moduleId, String title, String description,
                  String videoUrl, int order, boolean completed) {
        this.id = id;
        this.moduleId = moduleId;
        this.title = title;
        this.description = description;
        this.videoUrl = videoUrl;
        this.order = order;
        this.completed = completed;
    }

    @NonNull
    public static Lesson fromFirestore(@NonNull DocumentSnapshot doc) {
        String moduleId = doc.getString("module_id");
        String title = doc.getString("title");
        String description = doc.getString("description");
        String videoUrl = doc.getString("video_url");
        Long orderLong = doc.getLong("order");

        return new Lesson(
                doc.getId(),
                moduleId != null ? moduleId : "",
                title != null ? title : "",
                description != null ? description : "",
                videoUrl != null ? videoUrl : "",
                orderLong != null ? orderLong.intValue() : 0,
                false
        );
    }

    public String getId() { return id; }
    public String getModuleId() { return moduleId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getVideoUrl() { return videoUrl; }
    public int getOrder() { return order; }
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }
    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }

    /**
     * The label shown on the lesson tile, its detail chip, and spoken by TTS.
     *
     * Only the alphabet/number tracing lessons collapse to a single glyph
     * ("Letter A" -> "A", "Number 1" -> "1"), because those tiles show just the
     * sign to trace. Every other lesson keeps its FULL title, so phrases stay
     * whole ("Good Morning", "How are you?", "See you later") instead of being
     * cut down to the last word.
     */
    public String getShortLabel() {
        String trimmed = title.trim();
        if (trimmed.isEmpty()) return title;

        boolean isGlyphLesson =
                trimmed.regionMatches(true, 0, "Letter ", 0, 7)
                        || trimmed.regionMatches(true, 0, "Number ", 0, 7);
        if (isGlyphLesson) {
            String[] parts = trimmed.split("\\s+");
            return parts[parts.length - 1];
        }
        return trimmed;
    }
}