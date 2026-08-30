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
import java.util.List;

public class AvailableKeysAdapter extends RecyclerView.Adapter<AvailableKeysAdapter.ViewHolder> {
    public interface OnAddKeyListener {
        void onAddKey(SshKeyInfo key);
    }

    private final List<SshKeyInfo> keys = new ArrayList<>();
    private final OnAddKeyListener listener;

    public AvailableKeysAdapter(OnAddKeyListener listener) {
        this.listener = listener;
    }

    public void setKeys(List<SshKeyInfo> newKeys) {
        keys.clear();
        if (newKeys != null) keys.addAll(newKeys);
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

        // Repurpose buttons: copy becomes Add, view becomes disabled, delete hidden?
        holder.btnCopy.setText("Add to Agent");
        holder.btnCopy.setOnClickListener(v -> {
            if (listener != null) listener.onAddKey(key);
        });
        holder.btnView.setVisibility(View.GONE);
        holder.btnDelete.setVisibility(View.GONE);
        // Change type badge color as before
        holder.textType.setBackgroundColor(0xFF2E3B4E);
        holder.textType.setTextColor(0xFF7DA9FF);
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
