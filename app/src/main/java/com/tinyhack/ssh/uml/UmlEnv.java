package com.tinyhack.ssh.uml;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * A named UML environment and the bridge to the {@code uml-env} script that
 * owns them. The app never edits environment files itself: every change goes
 * through the same script as the terminal command, so locking (a running
 * guest cannot be deleted) and validation stay in one place.
 */
public final class UmlEnv {
    public enum State { READY, RUNNING, CREATING, INCOMPLETE }

    public static final Pattern NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]{0,47}");
    public static final Pattern USER = Pattern.compile("[a-z][a-z0-9_-]{0,30}");
    public static final Pattern SIZE = Pattern.compile("([1-9][0-9]{0,5})([MG])");

    public final String name;
    public final State state;
    public final String type;
    public final String user;
    /** Saved memory, e.g. "512M". */
    public final String memory;
    public final boolean shareHome;
    /** Logical (maximum) disk size in bytes. */
    public final long diskBytes;
    /** Host storage the sparse disk actually uses, in bytes. */
    public final long usedBytes;

    UmlEnv(String name, State state, String type, String user, String memory,
           boolean shareHome, long diskBytes, long usedBytes) {
        this.name = name;
        this.state = state;
        this.type = type;
        this.user = user;
        this.memory = memory;
        this.shareHome = shareHome;
        this.diskBytes = diskBytes;
        this.usedBytes = usedBytes;
    }

    public String typeLabel() {
        return typeLabel(type);
    }

    public static String typeLabel(String type) {
        if ("debian".equals(type)) return "Debian";
        if ("ubuntu".equals(type)) return "Ubuntu";
        return type;
    }

    /** Memory in MiB, or 0 if the saved value is unreadable. */
    public int memoryMb() {
        return parseSizeMb(memory);
    }

    /** "4G" / "512M" → MiB; 0 when invalid. */
    public static int parseSizeMb(String value) {
        if (value == null) return 0;
        java.util.regex.Matcher m = SIZE.matcher(value.trim().toUpperCase(Locale.ROOT));
        if (!m.matches()) return 0;
        long mb = Long.parseLong(m.group(1));
        if ("G".equals(m.group(2))) mb *= 1024;
        return mb > Integer.MAX_VALUE ? 0 : (int) mb;
    }

    public File dir(Context ctx) {
        return new File(envsDir(ctx), name);
    }

    public static File envsDir(Context ctx) {
        return new File(ctx.getFilesDir(), "home/uml/envs");
    }

    static File script(Context ctx) {
        return new File(ctx.getFilesDir(), "usr/libexec/uml-env");
    }

    static File bash(Context ctx) {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libbash.so");
    }

    /** Full command line for running uml-env in a terminal session. */
    public static List<String> command(Context ctx, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(bash(ctx).getAbsolutePath());
        cmd.add(script(ctx).getAbsolutePath());
        Collections.addAll(cmd, args);
        return cmd;
    }

    /** Result of a short, non-interactive uml-env call. */
    public static final class Result {
        public final int exitCode;
        public final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        public boolean ok() {
            return exitCode == 0;
        }

        /** The script's own message, without its "uml-env: " prefix. */
        public String message() {
            String s = output.trim();
            return s.startsWith("uml-env: ") ? s.substring(9) : s;
        }
    }

    /** Runs uml-env with the same HOME/PATH a Local Shell gets. Blocking. */
    public static Result run(Context ctx, String... args) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command(ctx, args));
        String files = ctx.getFilesDir().getAbsolutePath();
        String prefix = files + "/usr";
        Map<String, String> env = pb.environment();
        env.put("HOME", files + "/home");
        env.put("PREFIX", prefix);
        env.put("PATH", prefix + "/bin:/system/bin:/system/xbin");
        File tmp = new File(prefix, "tmp");
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();
        env.put("TMPDIR", tmp.getAbsolutePath());
        env.put("LANG", "en_US.UTF-8");
        env.remove("UML_MEMORY");
        pb.directory(new File(files, "home"));
        pb.redirectErrorStream(true);
        pb.redirectInput(new File("/dev/null"));
        Process p = pb.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() < 1 << 20) out.write(buf, 0, n);
            }
        }
        try {
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("uml-env did not finish");
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
        return new Result(p.exitValue(), out.toString(StandardCharsets.UTF_8.name()));
    }

    /** Every environment folder, including failed or running creations. */
    public static List<UmlEnv> list(Context ctx) throws IOException {
        Result r = run(ctx, "status");
        if (!r.ok()) throw new IOException(r.message());
        return parseStatus(r.output);
    }

    static List<UmlEnv> parseStatus(String output) {
        List<UmlEnv> envs = new ArrayList<>();
        for (String line : output.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 8 || !NAME.matcher(f[0]).matches()) continue;
            State state;
            switch (f[1]) {
                case "ready": state = State.READY; break;
                case "running": state = State.RUNNING; break;
                case "creating": state = State.CREATING; break;
                default: state = State.INCOMPLETE; break;
            }
            envs.add(new UmlEnv(f[0], state, f[2], f[3], f[4], "on".equals(f[5]),
                    parseLong(f[6]), parseLong(f[7]) * 1024));
        }
        Collections.sort(envs, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return envs;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Last part of the console log, with terminal control sequences removed. */
    public String readLogTail(Context ctx, int maxBytes) {
        File log = new File(dir(ctx), "console.log");
        if (!log.isFile()) return "";
        try (RandomAccessFile f = new RandomAccessFile(log, "r")) {
            long start = Math.max(0, f.length() - maxBytes);
            f.seek(start);
            byte[] data = new byte[(int) (f.length() - start)];
            f.readFully(data);
            String text = new String(data, StandardCharsets.UTF_8);
            if (start > 0) {
                int nl = text.indexOf('\n');
                if (nl >= 0) text = text.substring(nl + 1);
            }
            return text.replaceAll("\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)?", "")
                    .replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", "")
                    .replaceAll("\u001B[()][0-9A-Za-z]", "")
                    .replace("\r\n", "\n").replace('\r', '\n');
        } catch (IOException e) {
            return "";
        }
    }
}
