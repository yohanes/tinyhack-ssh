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

public class SshKeysAdapter extends RecyclerView.Adapter<SshKeysAdapter.ViewHolder> {
    public interface OnKeyActionListener {
        void onCopyPublicKey(SshKeyInfo key);
        void onViewKey(SshKeyInfo key);
        void onDeleteKey(SshKeyInfo key);
    }

    private final List<SshKeyInfo> keys = new ArrayList<>();
    private final OnKeyActionListener listener;

    public SshKeysAdapter(OnKeyActionListener listener) {
        this.listener = listener;
    }

    public void setKeys(List<SshKeyInfo> newKeys) {
        keys.clear();
        if (newKeys != null) {
            keys.addAll(newKeys);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_ssh_key, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SshKeyInfo key = keys.get(position);
        holder.textName.setText(key.getName());
        holder.textType.setText(key.getType());
        holder.textFingerprint.setText(key.getFingerprint());
        holder.textComment.setText(key.getComment().isEmpty() ? "No comment" : key.getComment());

        holder.btnCopy.setOnClickListener(v -> {
            if (listener != null) listener.onCopyPublicKey(key);
        });
        holder.btnView.setOnClickListener(v -> {
            if (listener != null) listener.onViewKey(key);
        });
        holder.btnDelete.setOnClickListener(v -> {
            if (listener != null) listener.onDeleteKey(key);
        });
    }

    @Override
    public int getItemCount() {
        return keys.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textName, textType, textFingerprint, textComment;
        Button btnCopy, btnView, btnDelete;

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
