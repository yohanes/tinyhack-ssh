package com.tinyhack.ssh.files;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tinyhack.ssh.R;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class FileListAdapter extends RecyclerView.Adapter<FileListAdapter.Holder> {

    /** Snapshot of one directory entry, read on the worker thread. */
    public static final class Entry {
        public final File file;
        public final String name;
        public final boolean directory;
        public final boolean symlink;
        public final long size;
        public final long modified;
        public final String linkTarget;
        /** ls-style mode for remote entries; null for local files (read on demand). */
        public final String mode;

        /** Remote entry: {@code file} only carries the remote path. */
        public Entry(File file, boolean directory, boolean symlink, long size, long modified,
                     String linkTarget, String mode) {
            this.file = file;
            this.name = file.getName();
            this.directory = directory;
            this.symlink = symlink;
            this.size = size;
            this.modified = modified;
            this.linkTarget = linkTarget;
            this.mode = mode;
        }

        public Entry(File file) {
            this.file = file;
            this.name = file.getName();
            this.symlink = FileOps.isSymlink(file);
            this.directory = file.isDirectory(); // follows links: a link to a folder opens it
            this.size = directory ? 0 : file.length();
            this.modified = file.lastModified();
            String target = null;
            if (symlink) {
                try {
                    target = java.nio.file.Files.readSymbolicLink(file.toPath()).toString();
                } catch (Exception ignored) {}
            }
            this.linkTarget = target;
            this.mode = null;
        }
    }

    public interface Listener {
        void onEntryClick(Entry entry, int position);
        void onEntryLongClick(Entry entry, int position);
    }

    private final Listener listener;
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private final DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);

    public FileListAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setEntries(List<Entry> list) {
        entries.clear();
        entries.addAll(list);
        // Drop selections that no longer exist in this folder.
        Set<String> present = new java.util.HashSet<>();
        for (Entry e : list) present.add(e.file.getAbsolutePath());
        selected.retainAll(present);
        notifyDataSetChanged();
    }

    public List<Entry> getEntries() { return entries; }

    public boolean isSelected(Entry e) { return selected.contains(e.file.getAbsolutePath()); }

    public void toggle(int position) {
        if (position < 0 || position >= entries.size()) return;
        String key = entries.get(position).file.getAbsolutePath();
        if (!selected.remove(key)) selected.add(key);
        notifyItemChanged(position);
    }

    public void selectAll() {
        for (Entry e : entries) selected.add(e.file.getAbsolutePath());
        notifyDataSetChanged();
    }

    public void clearSelection() {
        if (selected.isEmpty()) return;
        selected.clear();
        notifyDataSetChanged();
    }

    public int getSelectedCount() { return selected.size(); }

    public List<Entry> getSelectedEntries() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (isSelected(e)) out.add(e);
        return out;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = entries.get(position);
        h.name.setText(e.name);
        h.icon.setImageResource(e.directory ? R.drawable.ic_folder : R.drawable.ic_file);
        int tint;
        if (e.symlink) tint = 0xFF4DD0C4;
        else if (e.directory) tint = 0xFF7DA9FF;
        else tint = 0xFFB0B0B0;
        h.icon.setColorFilter(tint);
        h.name.setAlpha(e.name.startsWith(".") ? 0.6f : 1f);

        StringBuilder details = new StringBuilder();
        if (e.symlink) {
            details.append("→ ").append(e.linkTarget != null ? e.linkTarget : "?");
        } else {
            details.append(e.directory ? "Folder" : FileOps.formatSize(e.size));
            if (e.modified > 0) details.append("  •  ").append(dateFormat.format(new Date(e.modified)));
        }
        h.details.setText(details);

        boolean sel = isSelected(e);
        h.check.setVisibility(sel ? View.VISIBLE : View.GONE);
        h.itemView.setBackgroundColor(sel ? 0xFF2E3B4E : 0x00000000);
        h.itemView.setActivated(sel);
        h.itemView.setContentDescription((e.directory ? "Folder " : "File ") + e.name + (sel ? ", selected" : ""));

        h.itemView.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) listener.onEntryClick(entries.get(pos), pos);
        });
        h.itemView.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) listener.onEntryLongClick(entries.get(pos), pos);
            return true;
        });
    }

    @Override
    public int getItemCount() { return entries.size(); }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final ImageView check;
        final TextView name;
        final TextView details;

        Holder(@NonNull View v) {
            super(v);
            icon = v.findViewById(R.id.file_icon);
            check = v.findViewById(R.id.file_check);
            name = v.findViewById(R.id.file_name);
            details = v.findViewById(R.id.file_details);
        }
    }
}
