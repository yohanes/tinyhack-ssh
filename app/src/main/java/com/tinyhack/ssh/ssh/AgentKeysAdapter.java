package com.tinyhack.ssh.ssh;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tinyhack.ssh.R;

import java.util.ArrayList;
import java.util.List;

public class AgentKeysAdapter extends RecyclerView.Adapter<AgentKeysAdapter.ViewHolder> {
    private final List<SshAgentManager.AgentKeyInfo> keys = new ArrayList<>();

    public void setKeys(List<SshAgentManager.AgentKeyInfo> newKeys) {
        keys.clear();
        if (newKeys != null) keys.addAll(newKeys);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_agent_key, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SshAgentManager.AgentKeyInfo k = keys.get(position);
        holder.textFingerprint.setText(k.fingerprint != null ? k.fingerprint : k.rawLine);
        String subtitle = k.type + " • " + k.bits + " bits";
        if (k.comment != null && !k.comment.isEmpty()) subtitle += " • " + k.comment;
        holder.textSubtitle.setText(subtitle);
        holder.textType.setText(k.type);
        // Color badge based on type
        if ("ED25519".equalsIgnoreCase(k.type)) {
            holder.textType.setBackgroundColor(0xFF2E3B4E);
            holder.textType.setTextColor(0xFF7DA9FF);
        } else if ("RSA".equalsIgnoreCase(k.type)) {
            holder.textType.setBackgroundColor(0xFF3B2E4E);
            holder.textType.setTextColor(0xFFB07DFF);
        } else {
            holder.textType.setBackgroundColor(0xFF2E4E3B);
            holder.textType.setTextColor(0xFF7DFF9A);
        }
    }

    @Override
    public int getItemCount() {
        return keys.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textFingerprint;
        TextView textSubtitle;
        TextView textType;
        ViewHolder(@NonNull View itemView) {
            super(itemView);
            textFingerprint = itemView.findViewById(R.id.text_agent_fingerprint);
            textSubtitle = itemView.findViewById(R.id.text_agent_subtitle);
            textType = itemView.findViewById(R.id.text_agent_type);
        }
    }
}
