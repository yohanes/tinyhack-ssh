package com.tinyhack.ssh.ssh;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tinyhack.ssh.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AvailableKeysAdapter extends RecyclerView.Adapter<AvailableKeysAdapter.ViewHolder> {
    public interface OnAddKeyListener {
        void onAddKey(SshKeyInfo key);
    }

    private final List<SshKeyInfo> keys = new ArrayList<>();
    private final Set<String> loadedFingerprints = new HashSet<>();
    private final OnAddKeyListener listener;

    public AvailableKeysAdapter(OnAddKeyListener listener) {
        this.listener = listener;
    }

    public void setKeys(List<SshKeyInfo> newKeys, Set<String> loadedFps) {
        keys.clear();
        loadedFingerprints.clear();
        if (newKeys != null) keys.addAll(newKeys);
        if (loadedFps != null) loadedFingerprints.addAll(loadedFps);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_ssh_key, parent, false);
        // We'll repurpose item_ssh_key but hide some buttons and show Add logic?
        // Instead we need a custom layout; for now we reuse and adjust
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SshKeyInfo key = keys.get(position);
        holder.textName.setText(key.getName());
        holder.textType.setText(key.getType());
        holder.textFingerprint.setText(key.getFingerprint());
        holder.textComment.setText(key.getComment() != null ? key.getComment() : "");

        boolean inAgent = loadedFingerprints.contains(key.getFingerprint());
        holder.btnCopy.setText(inAgent ? "In Agent" : "Add to Agent");
        holder.btnCopy.setEnabled(!inAgent);
        holder.btnCopy.setOnClickListener(v -> {
            if (listener != null && !inAgent) listener.onAddKey(key);
        });
        holder.btnView.setVisibility(View.GONE);
        holder.btnDelete.setVisibility(View.GONE);
        holder.textType.setBackgroundColor(inAgent ? 0xFF2E4E3B : 0xFF2E3B4E);
        holder.textType.setTextColor(inAgent ? 0xFF7DFF9A : 0xFF7DA9FF);
    }

    @Override
    public int getItemCount() {
        return keys.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textName;
        TextView textType;
        TextView textFingerprint;
        TextView textComment;
        Button btnCopy;
        Button btnView;
        Button btnDelete;
        ViewHolder(@NonNull View itemView) {
            super(itemView);
            textName = itemView.findViewById(R.id.text_key_name);
            textType = itemView.findViewById(R.id.text_key_type);
            textFingerprint = itemView.findViewById(R.id.text_fingerprint);
            textComment = itemView.findViewById(R.id.text_comment);
            btnCopy = itemView.findViewById(R.id.btn_copy_public);
            btnView = itemView.findViewById(R.id.btn_view);
            btnDelete = itemView.findViewById(R.id.btn_delete);
        }
    }
}
