package com.ucucite.signvibe.ui.learn;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.ucucite.signvibe.R;
import com.ucucite.signvibe.data.ProgressRepository;
import com.ucucite.signvibe.data.StudentProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LearnFragment extends Fragment {

    // Emoji/glyph for the 5 original modules, keyed by derived module ID.
    // Any module NOT in this map (i.e. teacher-added) falls back to its
    // uploaded icon_url image instead. This is the only thing that stays
    // local now — the module list itself comes from Firestore.
    private static final Map<String, String> KNOWN_MODULE_EMOJI = new HashMap<>();
    static {
        KNOWN_MODULE_EMOJI.put("alphabet", "abc");
        KNOWN_MODULE_EMOJI.put("numbers", "123");
        KNOWN_MODULE_EMOJI.put("common_words", "\uD83D\uDCAC");
        KNOWN_MODULE_EMOJI.put("greetings_&_expressions", "\uD83D\uDC4B");
        KNOWN_MODULE_EMOJI.put("colors", "\uD83C\uDFA8");
    }

    private RecyclerView recyclerModules;
    private LearnModuleAdapter adapter;
    private TextView txtLessonsCompleted;   // trophy banner — updated live from progress
    private ListenerRegistration modulesListener;
    private ListenerRegistration lessonsListener;
    private ListenerRegistration progressListener;

    // Latest snapshot of each data source; the module list is rebuilt whenever any changes.
    private List<ModuleDoc> lastModules = new ArrayList<>();
    private Map<String, Integer> lastLessonCounts = new HashMap<>();
    private Map<String, Set<String>> lastCompletedByModule = new HashMap<>();

    /** Minimal holder for a module document from Firestore. */
    private static class ModuleDoc {
        final String id;          // derived from name, matches lessons' module_id
        final String title;       // the module's display name
        final String description;
        final String iconUrl;     // may be null/empty

        ModuleDoc(String id, String title, String description, String iconUrl) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.iconUrl = iconUrl;
        }
    }

    /**
     * Derives a module's ID from its name the same way the web admin's lesson
     * upload does ("Common Words" -> "common_words"), so a module links to its
     * existing lessons and progress. Must stay in sync with that transform.
     */
    private static String deriveModuleId(String name) {
        if (name == null) return "";
        return name.toLowerCase().replaceAll("\\s", "_");
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_learn, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerModules = view.findViewById(R.id.recyclerModules);
        recyclerModules.setLayoutManager(new LinearLayoutManager(requireContext()));

        // Trophy banner. Null-safe: if the layout uses a different ID, this stays
        // null and updateLessonsCompletedBanner() simply no-ops instead of crashing.
        txtLessonsCompleted = view.findViewById(R.id.txtLessonsCompleted);

        // Start empty; fills in once Firestore responds.
        adapter = new LearnModuleAdapter(new ArrayList<>(), this::onModuleClick);
        recyclerModules.setAdapter(adapter);

        listenToModules();
        listenToLessons();
        listenToProgress();
        loadGreetingName(view);
    }

    private void loadGreetingName(@NonNull View rootView) {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return;

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .get()
                .addOnSuccessListener(doc -> {
                    if (!isAdded() || doc == null || !doc.exists()) return;
                    StudentProfile profile = StudentProfile.fromFirestore(doc);
                    TextView txtGreeting = rootView.findViewById(R.id.txtGreeting);
                    txtGreeting.setText(getString(R.string.learn_greeting_fmt, profile.getDisplayName()));
                });
        // On failure, the layout's default "Hello, Learner!" text stays as-is.
    }

    private void listenToModules() {
        // Match the web admin's semantics exactly: it shows every module whose
        // status isn't "archived" (ManageModules uses `status !== 'archived'`).
        // The 5 original modules predate the module form and may have no status
        // field at all, so a strict whereEqualTo("status","active") silently
        // drops them. Ordering by "order" and filtering status in code mirrors
        // the web and also avoids needing a composite index.
        modulesListener = FirebaseFirestore.getInstance()
                .collection("modules")
                .orderBy("order", Query.Direction.ASCENDING)
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null) {
                        android.util.Log.w("LearnFragment", "modules query failed", error);
                        return;
                    }
                    if (snapshots == null || !isAdded()) return;

                    List<ModuleDoc> modules = new ArrayList<>();
                    for (QueryDocumentSnapshot doc : snapshots) {
                        String status = doc.getString("status");
                        if ("archived".equals(status)) continue;  // hide archived, keep everything else

                        String name = doc.getString("name");
                        String description = doc.getString("description");
                        String iconUrl = doc.getString("icon_url");
                        modules.add(new ModuleDoc(
                                deriveModuleId(name),
                                name != null ? name : "",
                                description != null ? description : "",
                                iconUrl
                        ));
                    }

                    lastModules = modules;
                    refreshModules();
                });
    }

    private void listenToLessons() {
        lessonsListener = FirebaseFirestore.getInstance()
                .collection("lessons")
                .whereEqualTo("status", "published")
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null || snapshots == null || !isAdded()) return;

                    Map<String, Integer> lessonCounts = new HashMap<>();
                    for (QueryDocumentSnapshot doc : snapshots) {
                        String moduleId = doc.getString("module_id");
                        if (moduleId == null) moduleId = "";
                        lessonCounts.merge(moduleId, 1, Integer::sum);
                    }

                    lastLessonCounts = lessonCounts;
                    refreshModules();
                });
    }

    private void listenToProgress() {
        progressListener = ProgressRepository.listenToCompletedLessons(completedByModule -> {
            if (!isAdded()) return;
            lastCompletedByModule = completedByModule;
            refreshModules();
        });
    }

    private void refreshModules() {
        adapter = new LearnModuleAdapter(buildModules(), this::onModuleClick);
        recyclerModules.setAdapter(adapter);
        updateLessonsCompletedBanner();
    }

    /**
     * Updates the trophy banner to the TOTAL completed lessons across all modules.
     * Counts from the same live source the cards use (student_progress via
     * ProgressRepository), capped per module by that module's published lesson
     * count — so the banner always equals the sum of the per-module cards.
     * This replaces the stale users.lessons_completed field, which is never
     * written and therefore always shows 0.
     */
    private void updateLessonsCompletedBanner() {
        if (txtLessonsCompleted == null) return;

        int totalCompleted = 0;
        for (ModuleDoc m : lastModules) {
            int total = lastLessonCounts.getOrDefault(m.id, 0);
            Set<String> completedLessonIds = lastCompletedByModule.get(m.id);
            int completed = completedLessonIds != null ? completedLessonIds.size() : 0;
            totalCompleted += Math.min(completed, total);
        }

        String label = totalCompleted == 1 ? "lesson completed" : "lessons completed";
        txtLessonsCompleted.setText(totalCompleted + " " + label);
    }

    private List<LearnModule> buildModules() {
        List<LearnModule> list = new ArrayList<>();
        for (ModuleDoc m : lastModules) {
            int total = lastLessonCounts.getOrDefault(m.id, 0);

            Set<String> completedLessonIds = lastCompletedByModule.get(m.id);
            int completed = completedLessonIds != null ? completedLessonIds.size() : 0;
            // A lesson removed/unpublished shouldn't inflate the count past the total.
            completed = Math.min(completed, total);

            // Emoji for the 5 known modules; image icon (icon_url) for the rest.
            String emoji = KNOWN_MODULE_EMOJI.get(m.id);   // null for teacher-added modules

            list.add(new LearnModule(
                    m.id, m.title, m.description, emoji, m.iconUrl, completed, total));
        }
        return list;
    }

    private void onModuleClick(LearnModule module) {
        Intent intent = new Intent(requireContext(), LessonListActivity.class);
        intent.putExtra(LessonListActivity.EXTRA_MODULE_ID, module.getId());
        intent.putExtra(LessonListActivity.EXTRA_MODULE_TITLE, module.getTitle());
        // The lesson-list header shows the emoji glyph; teacher-added modules have
        // none, so pass an empty string and let that screen handle the fallback.
        intent.putExtra(LessonListActivity.EXTRA_MODULE_ICON,
                module.getEmojiOrGlyph() != null ? module.getEmojiOrGlyph() : "");
        startActivity(intent);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (modulesListener != null) {
            modulesListener.remove();
            modulesListener = null;
        }
        if (lessonsListener != null) {
            lessonsListener.remove();
            lessonsListener = null;
        }
        if (progressListener != null) {
            progressListener.remove();
            progressListener = null;
        }
    }
}