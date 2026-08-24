package com.ucucite.signvibe.ui.game;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.ucucite.signvibe.R;
import com.ucucite.signvibe.ui.home.HomeActivity;

public class GameFragment extends Fragment {

    private ActivityResultLauncher<Intent> flappyLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Registered here so it's ready before the fragment reaches STARTED.
        flappyLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != android.app.Activity.RESULT_OK
                            || result.getData() == null) return;
                    boolean openProfile = result.getData()
                            .getBooleanExtra(FlappySignActivity.EXTRA_OPEN_PROFILE, false);
                    if (openProfile && getActivity() instanceof HomeActivity) {
                        ((HomeActivity) getActivity()).goToProfile();
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_game, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        view.findViewById(R.id.cardEasy).setOnClickListener(v -> launchMemory(3, 2, "Easy"));
        view.findViewById(R.id.cardMedium).setOnClickListener(v -> launchMemory(6, 3, "Medium"));
        view.findViewById(R.id.cardHard).setOnClickListener(v -> launchMemory(8, 4, "Hard"));

        view.findViewById(R.id.cardFlappy).setOnClickListener(v ->
                flappyLauncher.launch(new Intent(requireContext(), FlappySignActivity.class)));
    }

    private void launchMemory(int pairs, int columns, String label) {
        Intent intent = new Intent(requireContext(), MemoryGameActivity.class);
        intent.putExtra(MemoryGameActivity.EXTRA_PAIRS, pairs);
        intent.putExtra(MemoryGameActivity.EXTRA_COLUMNS, columns);
        intent.putExtra(MemoryGameActivity.EXTRA_LABEL, label);
        startActivity(intent);
    }
}