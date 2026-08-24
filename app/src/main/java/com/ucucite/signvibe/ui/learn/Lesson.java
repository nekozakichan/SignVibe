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
     * Matches Flutter's _shortLabel logic:
     * "Letter A" -> "A", "Number 1" -> "1", "Say Hello" -> "Hello"
     */
    public String getShortLabel() {
        String trimmed = title.trim();
        if (trimmed.isEmpty()) return title;
        String[] parts = trimmed.split(" ");
        return parts[parts.length - 1];
    }
}