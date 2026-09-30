package com.ucucite.signvibe.ui.ai;

import android.net.Uri;

public class AiChatMessage {
    public enum Sender { USER, AI }

    public final Sender sender;
    public final String text;
    public final Uri imageUri;
    public final long timestamp;
    public final boolean loading;
    public final boolean error;

    public AiChatMessage(Sender sender, String text, Uri imageUri, long timestamp, boolean loading, boolean error) {
        this.sender = sender;
        this.text = text;
        this.imageUri = imageUri;
        this.timestamp = timestamp;
        this.loading = loading;
        this.error = error;
    }

    public static AiChatMessage user(String text, Uri imageUri) {
        return new AiChatMessage(Sender.USER, text, imageUri, System.currentTimeMillis(), false, false);
    }

    public static AiChatMessage ai(String text) {
        return new AiChatMessage(Sender.AI, text, null, System.currentTimeMillis(), false, false);
    }

    public static AiChatMessage loading() {
        return new AiChatMessage(Sender.AI, "Reading...", null, System.currentTimeMillis(), true, false);
    }

    public static AiChatMessage error(String text) {
        return new AiChatMessage(Sender.AI, text, null, System.currentTimeMillis(), false, true);
    }
}
