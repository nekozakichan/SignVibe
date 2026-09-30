package com.ucucite.signvibe.ui.ai;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ucucite.signvibe.R;

import java.util.ArrayList;
import java.util.List;

public class AiChatAdapter extends RecyclerView.Adapter<AiChatAdapter.MessageViewHolder> {
    private static final int TYPE_USER = 1;
    private static final int TYPE_AI = 2;

    private final List<AiChatMessage> messages = new ArrayList<>();

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).sender == AiChatMessage.Sender.USER ? TYPE_USER : TYPE_AI;
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == TYPE_USER ? R.layout.item_user_message : R.layout.item_ai_message;
        View view = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        AiChatMessage message = messages.get(position);
        holder.text.setText(message.text);
        if (holder.image != null) {
            if (message.imageUri != null) {
                holder.image.setVisibility(View.VISIBLE);
                holder.image.setImageURI(message.imageUri);
            } else {
                holder.image.setVisibility(View.GONE);
            }
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    public void add(AiChatMessage message) {
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }

    public void replaceLast(AiChatMessage message) {
        if (messages.isEmpty()) {
            add(message);
            return;
        }
        int index = messages.size() - 1;
        messages.set(index, message);
        notifyItemChanged(index);
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        final TextView text;
        final ImageView image;

        MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.txtMessage);
            image = itemView.findViewById(R.id.imgMessage);
        }
    }
}
