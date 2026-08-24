package com.ucucite.signvibe.ui.learn;

public class LearnModule {

    private final String id;
    private final String title;
    private final String description;
    private final String emojiOrGlyph;   // used for the 5 known modules; null for teacher-added ones
    private final String iconUrl;        // used for teacher-added modules; null for the 5 known ones
    private final int completedLessons;
    private final int totalLessons;

    public LearnModule(String id, String title, String description, String emojiOrGlyph,
                       String iconUrl, int completedLessons, int totalLessons) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.emojiOrGlyph = emojiOrGlyph;
        this.iconUrl = iconUrl;
        this.completedLessons = completedLessons;
        this.totalLessons = totalLessons;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getEmojiOrGlyph() { return emojiOrGlyph; }
    public String getIconUrl() { return iconUrl; }
    public int getCompletedLessons() { return completedLessons; }
    public int getTotalLessons() { return totalLessons; }

    /**
     * True when this module should render its icon as an uploaded image rather
     * than an emoji/glyph. Emoji takes priority — a module only uses its image
     * icon when it has no emoji assigned (i.e. it's a teacher-added module).
     */
    public boolean hasImageIcon() {
        boolean noEmoji = emojiOrGlyph == null || emojiOrGlyph.isEmpty();
        boolean hasUrl = iconUrl != null && !iconUrl.isEmpty();
        return noEmoji && hasUrl;
    }

    public int getProgressPercent() {
        if (totalLessons == 0) return 0;
        return (int) ((completedLessons / (float) totalLessons) * 100);
    }
}