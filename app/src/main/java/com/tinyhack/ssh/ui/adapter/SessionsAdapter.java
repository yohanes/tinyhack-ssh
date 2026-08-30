package com.tinyhack.ssh.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tinyhack.ssh.R;
import com.tinyhack.ssh.session.TerminalSession;

import java.util.ArrayList;
import java.util.List;

public class SessionsAdapter extends RecyclerView.Adapter<SessionsAdapter.SessionViewHolder> {

    public interface OnSessionActionListener {
        void onSessionSelected(TerminalSession session, int position);
        void onSessionRename(TerminalSession session, int position);
        void onSessionClose(TerminalSession session, int position);
    }

    private final List<TerminalSession> sessions = new ArrayList<>();
    private int currentIndex = -1;
    private final OnSessionActionListener listener;

    public SessionsAdapter(OnSessionActionListener listener) {
        this.listener = listener;
    }

    public void updateSessions(List<TerminalSession> newSessions, int currentIdx) {
        sessions.clear();
        if (newSessions != null) sessions.addAll(newSessions);
        currentIndex = currentIdx;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public SessionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_session, parent, false);
        return new SessionViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull SessionViewHolder holder, int position) {
        TerminalSession session = sessions.get(position);
        boolean isCurrent = position == currentIndex;

        String title = session.getDisplayTitle();
        if (title == null || title.isEmpty()) title = "Session " + (position + 1);
        holder.title.setText(title);

        String subtitle;
        if (!session.isRunning()) {
            subtitle = "Closed • exit " + session.getExitCode();
        } else {
            // show running with id short
            String shortId = session.getId().substring(0, 6);
            subtitle = "Running • " + shortId;
            if (session.getProfileId() != null) {
                subtitle += " • profile";
            }
        }
        holder.subtitle.setText(subtitle);

        // Highlight current
        holder.itemView.setBackgroundColor(isCurrent ? 0xFF2A3A5A : 0x00000000);
        holder.indicator.setBackgroundColor(isCurrent ? 0xFF4D90FE : 0xFF3A3A3A);
        holder.title.setTextColor(isCurrent ? 0xFFFFFFFF : 0xFFE0E0E0);
        holder.closeBtn.setVisibility(View.VISIBLE);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onSessionSelected(session, holder.getBindingAdapterPosition());
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onSessionRename(session, holder.getBindingAdapterPosition());
            return true;
        });
        holder.closeBtn.setOnClickListener(v -> {
            if (listener != null) listener.onSessionClose(session, holder.getBindingAdapterPosition());
        });
    }

    @Override
    public int getItemCount() {
        return sessions.size();
    }

    static class SessionViewHolder extends RecyclerView.ViewHolder {
        TextView title;
        TextView subtitle;
        View indicator;
        ImageButton closeBtn;
        SessionViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.text_session_title);
            subtitle = itemView.findViewById(R.id.text_session_subtitle);
            indicator = itemView.findViewById(R.id.indicator);
            closeBtn = itemView.findViewById(R.id.btn_close_session);
        }
    }
}
