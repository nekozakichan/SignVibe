package com.ucucite.signvibe;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

/**
 * App-wide toast styled with the SignVibe icon.
 *
 * <p>Use {@code SignVibeToast.show(context, message)} in place of
 * {@code Toast.makeText(context, message, ...).show()} so every toast in the
 * app shows the SignVibe branding via {@code R.layout.custom_toast}.</p>
 */
public final class SignVibeToast {

    private SignVibeToast() {
        // No instances.
    }

    /** Shows a short SignVibe-branded toast. */
    public static void show(Context context, CharSequence message) {
        show(context, message, Toast.LENGTH_SHORT);
    }

    /**
     * Shows a SignVibe-branded toast.
     *
     * @param context  any context (uses the application context internally)
     * @param message  the text to display
     * @param duration {@link Toast#LENGTH_SHORT} or {@link Toast#LENGTH_LONG}
     */
    @SuppressWarnings("deprecation") // custom Toast views are still shown for foreground toasts
    public static void show(Context context, CharSequence message, int duration) {
        if (context == null) {
            return;
        }
        Toast toast = new Toast(context.getApplicationContext());
        View view = LayoutInflater.from(context).inflate(R.layout.custom_toast, null);
        TextView text = view.findViewById(R.id.toast_text);
        if (text != null) {
            text.setText(message);
        }
        toast.setView(view);
        toast.setDuration(duration);
        toast.show();
    }
}
