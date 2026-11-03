package com.tinyhack.ssh.files;

import android.system.Os;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local copy / move / delete used by the file manager. Runs on a worker
 * thread; symbolic links are never followed while recursing, so a link such
 * as ~/storage is copied, moved or deleted as a link and never its target.
 */
public final class FileOps {
    private FileOps() {}

    public enum Conflict { REPLACE, KEEP_BOTH, SKIP }

    public interface Progress {
        /** Called from the worker thread; implementations post to the UI themselves. */
        void onProgress(String currentName, long bytesDone, long bytesTotal, int itemsDone, int itemsTotal);
    }

    public static final class Result {
        public final List<String> errors = new ArrayList<>();
        public int done;
        public boolean cancelled;
    }

    private static final int BUFFER = 256 * 1024;

    // ---- queries ----

    public static boolean isSymlink(File f) {
        return Files.isSymbolicLink(f.toPath());
    }

    /** Recursive size and file count; does not follow symlinks. */
    public static long[] measure(List<File> roots, AtomicBoolean cancel) {
        long[] acc = new long[2];
        for (File f : roots) measureInto(f, acc, cancel);
        return acc;
    }

    private static void measureInto(File f, long[] acc, AtomicBoolean cancel) {
        if (cancel != null && cancel.get()) return;
        if (!isSymlink(f) && f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) measureInto(k, acc, cancel);
        } else {
            acc[0] += isSymlink(f) ? 0 : f.length();
            acc[1]++;
        }
    }

    /** True when {@code dir} is {@code src} or lies inside it. */
    public static boolean isInside(File dir, File src) {
        String d = dir.getAbsolutePath();
        String s = src.getAbsolutePath();
        return d.equals(s) || d.startsWith(s.endsWith("/") ? s : s + "/");
    }

    /** "name (1).ext", "name (2).ext", ... first one that does not exist. */
    public static File uniqueTarget(File dir, String name) {
        File candidate = new File(dir, name);
        if (!exists(candidate)) return candidate;
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; i < 10000; i++) {
            candidate = new File(dir, base + " (" + i + ")" + ext);
            if (!exists(candidate)) return candidate;
        }
        return new File(dir, base + " (" + System.currentTimeMillis() + ")" + ext);
    }

    /** exists() that is also true for a dangling symlink. */
    public static boolean exists(File f) {
        return Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    public static String validateName(String name) {
        if (name == null || name.trim().isEmpty()) return "Name cannot be empty";
        if (name.contains("/")) return "Name cannot contain '/'";
        if (name.equals(".") || name.equals("..")) return "Invalid name";
        if (name.contains("\0")) return "Invalid name";
        if (name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255) return "Name is too long";
        return null;
    }

    // ---- copy / move ----

    public static Result copy(List<File> sources, File destDir, Conflict conflict, boolean move,
                              Progress progress, AtomicBoolean cancel) {
        Result r = new Result();
        long[] total = measure(sources, cancel);
        long[] done = new long[]{0};
        int[] items = new int[]{0};
        int itemsTotal = (int) Math.min(Integer.MAX_VALUE, total[1]);
        for (File src : sources) {
            if (cancel.get()) { r.cancelled = true; break; }
            if (!isSymlink(src) && src.isDirectory() && isInside(destDir, src)) {
                r.errors.add(src.getName() + ": cannot " + (move ? "move" : "copy") + " a folder into itself");
                continue;
            }
            File target = new File(destDir, src.getName());
            boolean sameLocation = target.getAbsolutePath().equals(src.getAbsolutePath());
            if (move && sameLocation) continue;
            if (sameLocation || exists(target)) {
                if (sameLocation || conflict == Conflict.KEEP_BOTH) {
                    target = uniqueTarget(destDir, src.getName());
                } else if (conflict == Conflict.SKIP) {
                    continue;
                }
            }
            try {
                if (move && !exists(target) && src.renameTo(target)) {
                    // Same filesystem: rename is instant and atomic.
                    long[] m = measure(java.util.Collections.singletonList(target), null);
                    done[0] += m[0];
                    items[0] += (int) m[1];
                    progress.onProgress(src.getName(), done[0], total[0], items[0], itemsTotal);
                } else {
                    copyRecursive(src, target, r, done, items, total[0], itemsTotal, progress, cancel);
                    if (cancel.get()) { r.cancelled = true; break; }
                    if (move) {
                        Result del = new Result();
                        deleteRecursive(src, del, cancel);
                        r.errors.addAll(del.errors);
                    }
                }
                r.done++;
            } catch (InterruptedIOException e) {
                r.cancelled = true;
                break;
            } catch (IOException e) {
                r.errors.add(src.getName() + ": " + e.getMessage());
            }
        }
        return r;
    }

    private static void copyRecursive(File src, File dst, Result r, long[] done, int[] items,
                                      long bytesTotal, int itemsTotal, Progress progress,
                                      AtomicBoolean cancel) throws IOException {
        if (cancel.get()) throw new InterruptedIOException("cancelled");
        Path srcPath = src.toPath();
        if (Files.isSymbolicLink(srcPath)) {
            String target = Files.readSymbolicLink(srcPath).toString();
            try {
                if (exists(dst)) {
                    if (!dst.isDirectory() || isSymlink(dst)) Files.delete(dst.toPath());
                }
                Os.symlink(target, dst.getAbsolutePath());
            } catch (Exception e) {
                r.errors.add(src.getName() + ": cannot create symbolic link here (" + e.getMessage() + ")");
            }
            items[0]++;
            progress.onProgress(src.getName(), done[0], bytesTotal, items[0], itemsTotal);
            return;
        }
        if (src.isDirectory()) {
            if (exists(dst) && !dst.isDirectory()) {
                throw new IOException("a file named '" + dst.getName() + "' is in the way");
            }
            if (!dst.isDirectory() && !dst.mkdirs()) {
                throw new IOException("cannot create folder " + dst.getName());
            }
            File[] kids = src.listFiles();
            if (kids == null) {
                r.errors.add(src.getName() + ": cannot read folder");
                return;
            }
            for (File k : kids) {
                try {
                    copyRecursive(k, new File(dst, k.getName()), r, done, items, bytesTotal, itemsTotal, progress, cancel);
                } catch (InterruptedIOException e) {
                    throw e;
                } catch (IOException e) {
                    r.errors.add(k.getName() + ": " + e.getMessage());
                }
            }
            dst.setLastModified(src.lastModified());
            return;
        }
        if (dst.isDirectory() && !isSymlink(dst)) {
            throw new IOException("a folder named '" + dst.getName() + "' is in the way");
        }
        File tmp = new File(dst.getParentFile(), "." + dst.getName() + ".part-" + System.nanoTime());
        boolean ok = false;
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[BUFFER];
            int n;
            long lastReport = 0;
            while ((n = in.read(buf)) > 0) {
                if (cancel.get()) throw new InterruptedIOException("cancelled");
                out.write(buf, 0, n);
                done[0] += n;
                long now = System.nanoTime();
                if (now - lastReport > 100_000_000L) {
                    lastReport = now;
                    progress.onProgress(src.getName(), done[0], bytesTotal, items[0], itemsTotal);
                }
            }
            ok = true;
        } finally {
            if (!ok) tmp.delete();
        }
        if (exists(dst)) Files.delete(dst.toPath());
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            throw new IOException("cannot write " + dst.getName());
        }
        dst.setLastModified(src.lastModified());
        items[0]++;
        progress.onProgress(src.getName(), done[0], bytesTotal, items[0], itemsTotal);
    }

    // ---- delete ----

    public static Result delete(List<File> files, Progress progress, AtomicBoolean cancel) {
        Result r = new Result();
        int i = 0;
        for (File f : files) {
            if (cancel.get()) { r.cancelled = true; break; }
            progress.onProgress(f.getName(), 0, 0, i++, files.size());
            if (deleteRecursive(f, r, cancel)) r.done++;
        }
        return r;
    }

    private static boolean deleteRecursive(File f, Result r, AtomicBoolean cancel) {
        if (cancel.get()) return false;
        Path p = f.toPath();
        if (!Files.isSymbolicLink(p) && f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k, r, cancel);
        }
        if (cancel.get()) return false;
        try {
            Files.delete(p);
            return true;
        } catch (IOException e) {
            r.errors.add(f.getName() + ": " + describe(e));
            return false;
        }
    }

    private static String describe(IOException e) {
        if (e instanceof java.nio.file.DirectoryNotEmptyException) return "folder not empty";
        if (e instanceof java.nio.file.AccessDeniedException) return "permission denied";
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    // ---- formatting ----

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int u = -1;
        do { v /= 1024; u++; } while (v >= 1024 && u < units.length - 1);
        return String.format(Locale.ROOT, v >= 100 ? "%.0f %s" : "%.1f %s", v, units[u]);
    }

    /** ls-style permission string, e.g. "drwxr-x---". */
    public static String modeString(File f) {
        try {
            int mode = Os.lstat(f.getAbsolutePath()).st_mode;
            char type = android.system.OsConstants.S_ISDIR(mode) ? 'd'
                    : android.system.OsConstants.S_ISLNK(mode) ? 'l' : '-';
            StringBuilder sb = new StringBuilder().append(type);
            String rwx = "rwxrwxrwx";
            for (int i = 0; i < 9; i++) {
                sb.append((mode & (1 << (8 - i))) != 0 ? rwx.charAt(i) : '-');
            }
            return sb.toString();
        } catch (Exception e) {
            return "?";
        }
    }
}
