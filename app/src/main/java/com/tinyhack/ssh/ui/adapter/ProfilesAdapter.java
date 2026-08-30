package com.tinyhack.ssh.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tinyhack.ssh.R;
import com.tinyhack.ssh.model.ConnectionProfile;

import java.util.ArrayList;
import java.util.List;

public class ProfilesAdapter extends RecyclerView.Adapter<ProfilesAdapter.ProfileViewHolder> {

    public interface OnProfileActionListener {
        void onProfileConnect(ConnectionProfile profile);
        void onProfileEdit(ConnectionProfile profile);
        void onProfileDelete(ConnectionProfile profile);
        void onProfileDuplicate(ConnectionProfile profile);
    }

    private final List<ConnectionProfile> profiles = new ArrayList<>();
    private final OnProfileActionListener listener;
    private final boolean compactMode; // for drawer

    public ProfilesAdapter(OnProfileActionListener listener, boolean compactMode) {
        this.listener = listener;
        this.compactMode = compactMode;
    }

    public ProfilesAdapter(OnProfileActionListener listener) {
        this(listener, false);
    }

    public void updateProfiles(List<ConnectionProfile> newProfiles) {
        profiles.clear();
        if (newProfiles != null) profiles.addAll(newProfiles);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ProfileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = compactMode ? R.layout.item_profile_drawer : R.layout.item_profile;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new ProfileViewHolder(v, compactMode);
    }

    @Override
    public void onBindViewHolder(@NonNull ProfileViewHolder holder, int position) {
        ConnectionProfile p = profiles.get(position);
        holder.name.setText(p.getName());
        holder.subtitle.setText(p.getDisplaySubtitle());
        holder.badge.setText(p.getTypeLabel());
        // Color dot
        if (holder.colorDot != null) {
            holder.colorDot.setBackgroundColor(p.getColor());
        }
        // Badge color
        if (p.getType() == ConnectionProfile.Type.SSH) {
            holder.badge.setBackgroundColor(0xFF2E3B4E);
            holder.badge.setTextColor(0xFF7DA9FF);
        } else if (p.getType() == ConnectionProfile.Type.MOSH) {
            holder.badge.setBackgroundColor(0xFF3B2E4E);
            holder.badge.setTextColor(0xFFB07DFF);
        } else {
            holder.badge.setBackgroundColor(0xFF2E4E3B);
            holder.badge.setTextColor(0xFF7DFF9A);
        }

        holder.connectBtn.setOnClickListener(v -> {
            if (listener != null) listener.onProfileConnect(p);
        });
        if (holder.editBtn != null) {
            holder.editBtn.setOnClickListener(v -> {
                if (listener != null) listener.onProfileEdit(p);
            });
        }
        if (holder.deleteBtn != null) {
            holder.deleteBtn.setOnClickListener(v -> {
                if (listener != null) listener.onProfileDelete(p);
            });
        }
        // Long press duplicate
        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onProfileDuplicate(p);
            return true;
        });
        if (!compactMode) {
            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onProfileConnect(p);
            });
        } else {
            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onProfileConnect(p);
            });
        }
    }

    @Override
    public int getItemCount() {
        return profiles.size();
    }

    static class ProfileViewHolder extends RecyclerView.ViewHolder {
        TextView name;
        TextView subtitle;
        TextView badge;
        View colorDot;
        ImageButton connectBtn;
        ImageButton editBtn;
        ImageButton deleteBtn;
        ProfileViewHolder(@NonNull View itemView, boolean compact) {
            super(itemView);
            name = itemView.findViewById(R.id.text_profile_name);
            subtitle = itemView.findViewById(R.id.text_profile_subtitle);
            badge = itemView.findViewById(R.id.text_profile_badge);
            colorDot = itemView.findViewById(R.id.view_color_dot);
            connectBtn = itemView.findViewById(R.id.btn_connect);
            // In compact mode edit/delete may be missing but we try
            editBtn = itemView.findViewById(R.id.btn_edit);
            deleteBtn = itemView.findViewById(R.id.btn_delete);
        }
    }
}
