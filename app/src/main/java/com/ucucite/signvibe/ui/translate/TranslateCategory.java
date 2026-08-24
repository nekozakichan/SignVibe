package com.ucucite.signvibe.ui.translate;

public class TranslateCategory {

    public static final int TYPE_ALPHABET = 0;
    public static final int TYPE_NUMBER = 1;
    public static final int TYPE_WORD = 2;

    private final int type;
    private final String title;
    private final String description;
    private final String emoji; // null for word (uses custom icon)

    public TranslateCategory(int type, String title, String description, String emoji) {
        this.type = type;
        this.title = title;
        this.description = description;
        this.emoji = emoji;
    }

    public int getType() { return type; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getEmoji() { return emoji; }
}