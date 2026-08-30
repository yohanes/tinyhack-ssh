#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <dirent.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <mutex>
#include <string>
#include <vector>
#include <poll.h>
#include <atomic>
#include <chrono>
#include <errno.h>

#include <ghostty/vt.h>

#define LOG_TAG "GhosttyJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static JavaVM* g_jvm = nullptr;
static jclass g_desktopNotifyHelperCls = nullptr;

struct KittyBitmapCache {
    uint32_t image_id = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint64_t generation = 0;
    jobject bitmap = nullptr;
};

struct NativeSession {
    int ptm_fd = -1;
    pid_t child_pid = -1;
    pthread_t read_thread = 0;
    GhosttyTerminal terminal = nullptr;
    GhosttyRenderState render_state = nullptr;
    GhosttyKeyEncoder key_encoder = nullptr;
    GhosttyRenderStateRowIterator row_it = nullptr;
    GhosttyRenderStateRowCells row_cells = nullptr;
    GhosttyKittyGraphicsPlacementIterator kitty_placement_it = nullptr;
    GhosttyKeyEvent key_event = nullptr;
    GhosttyMouseEncoder mouse_encoder = nullptr;
    GhosttyMouseEvent mouse_event = nullptr;
    jobject java_callback = nullptr;
    std::mutex session_mutex;
    std::atomic<bool> is_closed{false};
    uint16_t cols = 80;
    uint16_t rows = 24;
    uint32_t cell_width = 10;
    uint32_t cell_height = 20;
    std::vector<KittyBitmapCache> kitty_bitmap_cache;
    std::atomic<int64_t> last_pty_activity_ms{0};
};

static int64_t monotonic_millis() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

static JNIEnv* get_jni_env() {
    if (!g_jvm) return nullptr;
    JNIEnv* env = nullptr;
    jint res = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != 0) {
            return nullptr;
        }
    }
    return env;
}

// Background PTY reader thread in C++
static void* pty_read_loop(void* arg) {
    auto* session = static_cast<NativeSession*>(arg);
    uint8_t buffer[8192];
    LOGI("pty_read_loop started for ptm_fd=%d", session->ptm_fd);

    while (!session->is_closed.load() && session->ptm_fd >= 0) {
        if (session->ptm_fd < 0 || session->is_closed.load()) break;
        struct pollfd pfd;
        pfd.fd = session->ptm_fd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        int ret = poll(&pfd, 1, 200);
        if (session->is_closed.load() || session->ptm_fd < 0) break;
        if (ret < 0) {
            if (errno == EINTR) continue;
            LOGI("pty_read_loop poll error errno=%d, exiting", errno);
            break;
        }
        if (ret == 0) {
            continue;
        }
        if (!(pfd.revents & POLLIN)) {
            if (pfd.revents & (POLLHUP | POLLERR | POLLNVAL)) {
                LOGI("pty_read_loop poll hup/err (revents=%d), exiting", pfd.revents);
                break;
            }
            continue;
        }
        ssize_t bytes_read = read(session->ptm_fd, buffer, sizeof(buffer));
        if (bytes_read <= 0) {
            LOGI("pty_read_loop read <= 0 (errno=%d), exiting", errno);
            break;
        }
        session->last_pty_activity_ms.store(monotonic_millis());

        LOGD("pty_read_loop: read %zd bytes: '%.*s'", bytes_read, (int)(bytes_read > 64 ? 64 : bytes_read), buffer);
        {
            std::lock_guard<std::mutex> lock(session->session_mutex);
            if (session->terminal && !session->is_closed.load()) {
                ghostty_terminal_vt_write(session->terminal, buffer, bytes_read);
            }
        }

        JNIEnv* env = get_jni_env();
        if (env && session->java_callback) {
            jclass cls = env->GetObjectClass(session->java_callback);
            jmethodID mid = env->GetMethodID(cls, "onDataAvailable", "()V");
            if (mid) {
                env->CallVoidMethod(session->java_callback, mid);
            }
            env->DeleteLocalRef(cls);
        }
    }
    return nullptr;
}

// Ghostty terminal callbacks
static void on_write_pty(GhosttyTerminal terminal, void* userdata, const uint8_t* data, size_t len) {
    (void)terminal;
    auto* session = static_cast<NativeSession*>(userdata);
    if (!session || session->is_closed.load() || session->ptm_fd < 0 || !data || len == 0) return;
    write(session->ptm_fd, data, len);
}

static void on_title_changed(GhosttyTerminal terminal, void* userdata) {
    auto* session = static_cast<NativeSession*>(userdata);
    if (!session || !session->java_callback) return;
    JNIEnv* env = get_jni_env();
    if (!env) return;

    GhosttyString title;
    title.ptr = nullptr;
    title.len = 0;
    ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_TITLE, &title);
    std::string title_str(title.ptr ? (const char*)title.ptr : "", title.len);

    jstring jtitle = env->NewStringUTF(title_str.c_str());
    jclass cls = env->GetObjectClass(session->java_callback);
    jmethodID mid = env->GetMethodID(cls, "onTitleChanged", "(Ljava/lang/String;)V");
    if (mid) {
        env->CallVoidMethod(session->java_callback, mid, jtitle);
    }
    env->DeleteLocalRef(jtitle);
    env->DeleteLocalRef(cls);
}

static void on_bell(GhosttyTerminal terminal, void* userdata) {
    (void)terminal;
    auto* session = static_cast<NativeSession*>(userdata);
    if (!session || !session->java_callback) return;
    JNIEnv* env = get_jni_env();
    if (!env) return;

    jclass cls = env->GetObjectClass(session->java_callback);
    jmethodID mid = env->GetMethodID(cls, "onBell", "()V");
    if (mid) {
        env->CallVoidMethod(session->java_callback, mid);
    }
    env->DeleteLocalRef(cls);
}

static void on_clipboard_write(GhosttyTerminal terminal, void* userdata, const GhosttyClipboardWrite* write_req) {
    (void)terminal;
    auto* session = static_cast<NativeSession*>(userdata);
    if (!session || !write_req) return;

    if (write_req->contents && write_req->contents_len > 0 && session->java_callback) {
        JNIEnv* env = get_jni_env();
        if (env) {
            const auto& content = write_req->contents[0];
            std::string text((const char*)content.data.ptr, content.data.len);
            jstring jtext = env->NewStringUTF(text.c_str());
            jclass cls = env->GetObjectClass(session->java_callback);
            jmethodID mid = env->GetMethodID(cls, "onClipboardWrite", "(Ljava/lang/String;)V");
            if (mid) {
                env->CallVoidMethod(session->java_callback, mid, jtext);
            }
            env->DeleteLocalRef(jtext);
            env->DeleteLocalRef(cls);
        }
    }

    if (write_req->reply) {
        GhosttyClipboardWriteReply reply = GHOSTTY_INIT_SIZED(GhosttyClipboardWriteReply);
        reply.result = GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS;
        write_req->reply(write_req, &reply);
    }
}

static void on_desktop_notification(GhosttyTerminal terminal, void* userdata, const GhosttyTerminalDesktopNotification* notification) {
    (void)terminal;
    (void)userdata;
    if (!notification) return;
    std::string title;
    std::string body;
    if (notification->title.ptr && notification->title.len > 0) {
        title.assign((const char*)notification->title.ptr, notification->title.len);
    }
    if (notification->body.ptr && notification->body.len > 0) {
        body.assign((const char*)notification->body.ptr, notification->body.len);
    }
    LOGI("Desktop notification: title='%s' body='%s'", title.c_str(), body.c_str());
    JNIEnv* env = get_jni_env();
    if (!env) return;
    jclass helperCls = g_desktopNotifyHelperCls ? (jclass)env->NewLocalRef(g_desktopNotifyHelperCls) : nullptr;
    bool needDeleteHelper = helperCls != nullptr;
    if (!helperCls) {
        helperCls = env->FindClass("com/tinyhack/ssh/util/DesktopNotificationHelper");
        if (!helperCls) {
            env->ExceptionClear();
            return;
        }
        needDeleteHelper = true;
    }
    jmethodID mid = env->GetStaticMethodID(helperCls, "show", "(Ljava/lang/String;Ljava/lang/String;)V");
    if (!mid) {
        env->ExceptionClear();
        if (needDeleteHelper) env->DeleteLocalRef(helperCls);
        return;
    }
    jstring jTitle = env->NewStringUTF(title.c_str());
    jstring jBody = env->NewStringUTF(body.c_str());
    env->CallStaticVoidMethod(helperCls, mid, jTitle, jBody);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    if (jTitle) env->DeleteLocalRef(jTitle);
    if (jBody) env->DeleteLocalRef(jBody);
    if (needDeleteHelper) env->DeleteLocalRef(helperCls);
}

static int throw_exception(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls) {
        env->ThrowNew(cls, msg);
        env->DeleteLocalRef(cls);
    }
    return -1;
}

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_jvm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK && env) {
        jclass local = env->FindClass("com/tinyhack/ssh/util/DesktopNotificationHelper");
        if (local) {
            g_desktopNotifyHelperCls = (jclass)env->NewGlobalRef(local);
            env->DeleteLocalRef(local);
            LOGI("Cached DesktopNotificationHelper class");
        } else {
            env->ExceptionClear();
            LOGI("Failed to cache DesktopNotificationHelper class in JNI_OnLoad");
        }
    }
    LOGI("Ghostty JNI loaded successfully");
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeCreateSession(
    JNIEnv* env, jclass clazz,
    jstring jcmd, jstring jcwd,
    jobjectArray jargv, jobjectArray jenvp,
    jint rows, jint cols,
    jint cell_width, jint cell_height,
    jobject jcallback)
{
    (void)clazz;
    auto* session = new NativeSession();
    session->rows = static_cast<uint16_t>(rows > 0 ? rows : 24);
    session->cols = static_cast<uint16_t>(cols > 0 ? cols : 80);
    session->cell_width = static_cast<uint32_t>(cell_width > 0 ? cell_width : 10);
    session->cell_height = static_cast<uint32_t>(cell_height > 0 ? cell_height : 20);

    if (jcallback) {
        session->java_callback = env->NewGlobalRef(jcallback);
    }

    // Initialize Ghostty terminal
    GhosttyResult res = ghostty_terminal_new(nullptr, &session->terminal, session->cols, session->rows);
    if (res != GHOSTTY_SUCCESS) {
        LOGE("Failed to create Ghostty terminal: %d", res);
        delete session;
        throw_exception(env, "Failed to create Ghostty terminal");
        return 0;
    }

    // The VT library keeps Kitty graphics disabled until the embedder gives
    // it an explicit storage budget. terminal-browser first sends a tiny
    // direct-RGB probe and then streams full-screen RGBA frames, so reserve
    // enough for several frames while still keeping memory use bounded.
    uint64_t kitty_image_storage_limit = 128ULL * 1024ULL * 1024ULL;
    ghostty_terminal_set(
        session->terminal, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT,
        &kitty_image_storage_limit);

    // Configure terminal effects & callbacks
    ghostty_terminal_set(session->terminal, GHOSTTY_TERMINAL_OPT_USERDATA, session);
    GhosttyTerminalWritePtyFn write_fn = on_write_pty;
    ghostty_terminal_set(session->terminal, GHOSTTY_TERMINAL_OPT_WRITE_PTY, reinterpret_cast<const void*>(write_fn));
    GhosttyTerminalTitleChangedFn title_fn = on_title_changed;
    ghostty_terminal_set(session->terminal, GHOSTTY_TERMINAL_OPT_TITLE_CHANGED, reinterpret_cast<const void*>(title_fn));
    GhosttyTerminalBellFn bell_fn = on_bell;
    ghostty_terminal_set(session->terminal, GHOSTTY_TERMINAL_OPT_BELL, reinterpret_cast<const void*>(bell_fn));
    GhosttyTerminalClipboardWriteFn clip_fn = on_clipboard_write;
    ghostty_terminal_set(session->terminal, GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE, reinterpret_cast<const void*>(clip_fn));
    GhosttyTerminalDesktopNotificationFn notify_fn = on_desktop_notification;
    ghostty_terminal_set(session->terminal, GHOSTTY_TERMINAL_OPT_DESKTOP_NOTIFICATION, reinterpret_cast<const void*>(notify_fn));

    // Initialize Render State
    ghostty_render_state_new(nullptr, &session->render_state);
    ghostty_render_state_row_iterator_new(nullptr, &session->row_it);
    ghostty_render_state_row_cells_new(nullptr, &session->row_cells);

    // Initialize Key Encoder
    ghostty_key_encoder_new(nullptr, &session->key_encoder);
    ghostty_key_event_new(nullptr, &session->key_event);

    // Initialize Mouse Encoder
    ghostty_mouse_encoder_new(nullptr, &session->mouse_encoder);
    ghostty_mouse_event_new(nullptr, &session->mouse_event);

    // Create PTY master
    session->ptm_fd = open("/dev/ptmx", O_RDWR | O_CLOEXEC | O_NOCTTY);
    if (session->ptm_fd < 0) {
        LOGE("Failed to open /dev/ptmx");
        delete session;
        throw_exception(env, "Cannot open /dev/ptmx");
        return 0;
    }

    char devname[64];
    if (grantpt(session->ptm_fd) || unlockpt(session->ptm_fd) || ptsname_r(session->ptm_fd, devname, sizeof(devname)) != 0) {
        LOGE("Failed to grantpt/unlockpt/ptsname_r");
        close(session->ptm_fd);
        delete session;
        throw_exception(env, "Cannot setup pty permissions");
        return 0;
    }

    // Configure termios
    struct termios tios;
    tcgetattr(session->ptm_fd, &tios);
    tios.c_iflag |= IUTF8;
    tios.c_iflag &= ~(IXON | IXOFF);
    tcsetattr(session->ptm_fd, TCSANOW, &tios);

    // Set initial winsize
    struct winsize sz;
    memset(&sz, 0, sizeof(sz));
    sz.ws_row = session->rows;
    sz.ws_col = session->cols;
    sz.ws_xpixel = session->cols * cell_width;
    sz.ws_ypixel = session->rows * cell_height;
    ioctl(session->ptm_fd, TIOCSWINSZ, &sz);

    // Prepare command and cwd strings
    std::string default_shell = "/system/bin/sh";
    const char* bash_path = "/data/data/com.tinyhack.ssh/files/usr/bin/bash";
    bool is_default_bash = false;
    if (!jcmd && access(bash_path, X_OK) == 0) {
        default_shell = bash_path;
        is_default_bash = true;
    }
    const char* cmd_str = jcmd ? env->GetStringUTFChars(jcmd, nullptr) : default_shell.c_str();
    const char* cwd_str = jcwd ? env->GetStringUTFChars(jcwd, nullptr) : "/data/data/com.tinyhack.ssh/files/home";

    // Prepare argv
    std::vector<char*> argv_list;
    if (jargv) {
        jsize argc = env->GetArrayLength(jargv);
        for (jsize i = 0; i < argc; ++i) {
            jstring js = (jstring)env->GetObjectArrayElement(jargv, i);
            if (js) {
                const char* s = env->GetStringUTFChars(js, nullptr);
                argv_list.push_back(strdup(s));
                env->ReleaseStringUTFChars(js, s);
                env->DeleteLocalRef(js);
            }
        }
    }
    if (argv_list.empty()) {
        if (is_default_bash) {
            argv_list.push_back(strdup("bash"));
            argv_list.push_back(strdup("-l"));
        } else {
            argv_list.push_back(strdup(cmd_str));
        }
    }
    argv_list.push_back(nullptr);

    // Prepare envp
    std::vector<char*> envp_list;
    if (jenvp) {
        jsize envc = env->GetArrayLength(jenvp);
        for (jsize i = 0; i < envc; ++i) {
            jstring js = (jstring)env->GetObjectArrayElement(jenvp, i);
            if (js) {
                const char* s = env->GetStringUTFChars(js, nullptr);
                envp_list.push_back(strdup(s));
                env->ReleaseStringUTFChars(js, s);
                env->DeleteLocalRef(js);
            }
        }
    }
    // Default environment variables — use xterm-kitty for widest chafa compatibility
    // (older chafa on remotes does not know xterm-ghostty; kitty is long-supported).
    // We also advertise ghostty via TERM_PROGRAM/GHOSTTY_BIN_DIR for newer chafa
    // and keep TERMINFO pointing at our bundled xterm-ghostty + xterm-kitty entries.
    envp_list.push_back(strdup("TERM=xterm-kitty"));
    envp_list.push_back(strdup("TERM_PROGRAM=ghostty"));
    envp_list.push_back(strdup("GHOSTTY_BIN_DIR=/data/data/com.tinyhack.ssh/files/usr/bin"));
    envp_list.push_back(strdup("TERMINFO=/data/data/com.tinyhack.ssh/files/usr/share/terminfo"));
    envp_list.push_back(strdup("COLORTERM=truecolor"));
    envp_list.push_back(strdup("LANG=en_US.UTF-8"));
    envp_list.push_back(strdup("SHELL=/data/data/com.tinyhack.ssh/files/usr/bin/bash"));
    envp_list.push_back(strdup("PATH=/data/data/com.tinyhack.ssh/files/usr/bin:/system/bin:/system/xbin"));
    envp_list.push_back(strdup("HOME=/data/data/com.tinyhack.ssh/files/home"));
    envp_list.push_back(strdup("PREFIX=/data/data/com.tinyhack.ssh/files/usr"));
    envp_list.push_back(strdup("TMPDIR=/data/data/com.tinyhack.ssh/files/usr/tmp"));
    envp_list.push_back(nullptr);

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork() failed");
        close(session->ptm_fd);
        delete session;
        throw_exception(env, "fork failed");
        return 0;
    } else if (pid == 0) {
        // In Child Process
        sigset_t signals_to_unblock;
        sigfillset(&signals_to_unblock);
        sigprocmask(SIG_UNBLOCK, &signals_to_unblock, nullptr);

        close(session->ptm_fd);
        setsid();

        int pts = open(devname, O_RDWR);
        if (pts < 0) {
            exit(1);
        }

        dup2(pts, 0);
        dup2(pts, 1);
        dup2(pts, 2);

        // Close other open fds
        DIR* self_dir = opendir("/proc/self/fd");
        if (self_dir != nullptr) {
            int self_dir_fd = dirfd(self_dir);
            struct dirent* entry;
            while ((entry = readdir(self_dir)) != nullptr) {
                int fd = atoi(entry->d_name);
                if (fd > 2 && fd != self_dir_fd) {
                    close(fd);
                }
            }
            closedir(self_dir);
        }

        if (cwd_str && chdir(cwd_str) != 0) {
            chdir("/data/data/com.tinyhack.ssh/files/home");
        }

        clearenv();
        for (char* e : envp_list) {
            if (e) putenv(e);
        }

        execve(cmd_str, argv_list.data(), environ);
        // Fallback to /system/bin/sh if execve failed
        execl("/system/bin/sh", "/system/bin/sh", nullptr);
        exit(1);
    }

    // In Parent Process
    session->child_pid = pid;

    // Free temporary string lists
    for (char* s : argv_list) free(s);
    for (char* s : envp_list) free(s);
    if (jcmd) env->ReleaseStringUTFChars(jcmd, cmd_str);
    if (jcwd) env->ReleaseStringUTFChars(jcwd, cwd_str);

    // Start background reader thread in C++
    pthread_create(&session->read_thread, nullptr, pty_read_loop, session);

    LOGI("Spawned terminal process pid=%d ptm_fd=%d", session->child_pid, session->ptm_fd);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jint JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeGetPtmFd(
    JNIEnv* env, jclass clazz, jlong sessionPtr)
{
    (void)env; (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    return session ? session->ptm_fd : -1;
}

JNIEXPORT jint JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeGetChildPid(
    JNIEnv* env, jclass clazz, jlong sessionPtr)
{
    (void)env; (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    return session ? session->child_pid : -1;
}

JNIEXPORT void JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeWritePty(
    JNIEnv* env, jclass clazz, jlong sessionPtr,
    jbyteArray jdata, jint offset, jint length)
{
    (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session || session->is_closed.load() || session->ptm_fd < 0 || !jdata || length <= 0) return;

    jbyte* bytes = env->GetByteArrayElements(jdata, nullptr);
    if (bytes) {
        write(session->ptm_fd, bytes + offset, length);
        env->ReleaseByteArrayElements(jdata, bytes, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeFeedVt(
    JNIEnv* env, jclass clazz, jlong sessionPtr,
    jbyteArray jdata, jint offset, jint length)
{
    (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session || session->is_closed.load() || !session->terminal || !jdata || length <= 0) return;

    jbyte* bytes = env->GetByteArrayElements(jdata, nullptr);
    if (bytes) {
        LOGD("nativeFeedVt: feeding %d bytes: '%.*s'", length, (int)(length > 64 ? 64 : length), bytes + offset);
        std::lock_guard<std::mutex> lock(session->session_mutex);
        ghostty_terminal_vt_write(session->terminal, reinterpret_cast<const uint8_t*>(bytes + offset), length);
        env->ReleaseByteArrayElements(jdata, bytes, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeResize(
    JNIEnv* env, jclass clazz, jlong sessionPtr,
    jint rows, jint cols, jint cell_width, jint cell_height)
{
    (void)env; (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session || session->is_closed.load()) return;

    std::lock_guard<std::mutex> lock(session->session_mutex);
    session->rows = static_cast<uint16_t>(rows > 0 ? rows : 24);
    session->cols = static_cast<uint16_t>(cols > 0 ? cols : 80);
    session->cell_width = static_cast<uint32_t>(cell_width > 0 ? cell_width : 10);
    session->cell_height = static_cast<uint32_t>(cell_height > 0 ? cell_height : 20);

    if (session->ptm_fd >= 0) {
        struct winsize sz;
        memset(&sz, 0, sizeof(sz));
        sz.ws_row = session->rows;
        sz.ws_col = session->cols;
        sz.ws_xpixel = session->cols * cell_width;
        sz.ws_ypixel = session->rows * cell_height;
        ioctl(session->ptm_fd, TIOCSWINSZ, &sz);
    }

    if (session->terminal) {
        ghostty_terminal_resize(session->terminal, session->cols, session->rows, cell_width, cell_height);
    }
}

JNIEXPORT void JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeScroll(
    JNIEnv* env, jclass clazz, jlong sessionPtr,
    jint type, jint deltaOrRow)
{
    (void)env; (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session || session->is_closed.load() || !session->terminal) return;

    std::lock_guard<std::mutex> lock(session->session_mutex);
    GhosttyTerminalScrollViewport vp;
    memset(&vp, 0, sizeof(vp));
    switch (type) {
        case 0: // TOP
            vp.tag = GHOSTTY_SCROLL_VIEWPORT_TOP;
            break;
        case 1: // BOTTOM
            vp.tag = GHOSTTY_SCROLL_VIEWPORT_BOTTOM;
            break;
        case 2: // DELTA
            vp.tag = GHOSTTY_SCROLL_VIEWPORT_DELTA;
            vp.value.delta = deltaOrRow;
            break;
        case 3: // ROW
            vp.tag = GHOSTTY_SCROLL_VIEWPORT_ROW;
            vp.value.row = static_cast<size_t>(deltaOrRow >= 0 ? deltaOrRow : 0);
            break;
        default:
            vp.tag = GHOSTTY_SCROLL_VIEWPORT_BOTTOM;
            break;
    }
    ghostty_terminal_scroll_viewport(session->terminal, vp);
}

JNIEXPORT void JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeWriteKey(
    JNIEnv* env, jclass clazz, jlong sessionPtr,
    jint ghosttyKey, jint action, jint mods, jstring jtext)
{
    (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session || session->is_closed.load() || session->ptm_fd < 0) return;

    const char* utf8_text = jtext ? env->GetStringUTFChars(jtext, nullptr) : nullptr;
    size_t utf8_len = utf8_text ? strlen(utf8_text) : 0;

    LOGD("nativeWriteKey: key=%d action=%d mods=%d text='%s'", ghosttyKey, action, mods, utf8_text ? utf8_text : "");

    std::lock_guard<std::mutex> lock(session->session_mutex);
    if (ghosttyKey == GHOSTTY_KEY_BACKSPACE && mods == 0) {
        char del = 0x7f;
        write(session->ptm_fd, &del, 1);
        if (utf8_text) env->ReleaseStringUTFChars(jtext, utf8_text);
        return;
    }

    // Direct legacy write for CTRL/ALT + single character. The vendored key
    // encoder is text-passthrough and would either emit the plain letter or
    // a kitty CSI-u sequence, neither of which legacy programs understand.
    // Terminals send Ctrl+<char> as the C0 control byte (ESC-prefixed when
    // ALT is also held), so write that straight to the pty.
    if (utf8_text && utf8_len == 1 &&
        ((mods & (GHOSTTY_MODS_CTRL | GHOSTTY_MODS_ALT)) != 0)) {
        bool alt = (mods & GHOSTTY_MODS_ALT) != 0;
        bool ctrl = (mods & GHOSTTY_MODS_CTRL) != 0;
        int code = -1;
        char c = utf8_text[0];
        if (ctrl) {
            if (c >= '@' && c <= '_') code = c - '@';
            else if (c >= 'a' && c <= 'z') code = c - 'a' + 1;
            else if (c == ' ') code = 0;
            else if (c == '?') code = 127;
            else if (c == '/') code = 31;
        }
        if (code >= 0 || (alt && !ctrl)) {
            if (alt) {
                char esc = 0x1b;
                write(session->ptm_fd, &esc, 1);
            }
            char out = (code >= 0) ? (char) code : c;
            write(session->ptm_fd, &out, 1);
            if (utf8_text) env->ReleaseStringUTFChars(jtext, utf8_text);
            return;
        }
        // Unmapped ctrl char (e.g. digit): fall through to encoder as-is
    }

    if (session->key_encoder && session->key_event && session->terminal) {
        ghostty_key_encoder_setopt_from_terminal(session->key_encoder, session->terminal);

        ghostty_key_event_set_key(session->key_event, static_cast<GhosttyKey>(ghosttyKey));
        ghostty_key_event_set_action(session->key_event, static_cast<GhosttyKeyAction>(action));
        ghostty_key_event_set_mods(session->key_event, static_cast<GhosttyMods>(mods));
        ghostty_key_event_set_utf8(session->key_event, utf8_text, utf8_len);

        char buf[128];
        size_t written = 0;
        GhosttyResult res = ghostty_key_encoder_encode(session->key_encoder, session->key_event, buf, sizeof(buf), &written);

        if (res == GHOSTTY_SUCCESS && written > 0) {
            write(session->ptm_fd, buf, written);
        } else if (utf8_text && utf8_len > 0) {
            // Direct text fallback
            write(session->ptm_fd, utf8_text, utf8_len);
        }
    } else if (utf8_text && utf8_len > 0) {
        write(session->ptm_fd, utf8_text, utf8_len);
    }

    if (utf8_text) {
        env->ReleaseStringUTFChars(jtext, utf8_text);
    }
}

JNIEXPORT void JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeWritePaste(
    JNIEnv* env, jclass clazz, jlong sessionPtr, jstring jtext)
{
    (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session || session->is_closed.load() || session->ptm_fd < 0 || !jtext) return;

    const char* text = env->GetStringUTFChars(jtext, nullptr);
    if (!text) return;
    size_t len = strlen(text);

    std::lock_guard<std::mutex> lock(session->session_mutex);
    // Write text directly to pty
    write(session->ptm_fd, text, len);

    env->ReleaseStringUTFChars(jtext, text);
}

static jobject create_argb_bitmap(JNIEnv* env, uint32_t width, uint32_t height) {
    jclass bitmap_cls = env->FindClass("android/graphics/Bitmap");
    jclass config_cls = env->FindClass("android/graphics/Bitmap$Config");
    if (!bitmap_cls || !config_cls) return nullptr;

    jfieldID argb_field = env->GetStaticFieldID(
        config_cls, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jmethodID create_method = env->GetStaticMethodID(
        bitmap_cls, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if (!argb_field || !create_method) {
        env->DeleteLocalRef(config_cls);
        env->DeleteLocalRef(bitmap_cls);
        return nullptr;
    }

    jobject config = env->GetStaticObjectField(config_cls, argb_field);
    jobject bitmap = env->CallStaticObjectMethod(
        bitmap_cls, create_method, static_cast<jint>(width),
        static_cast<jint>(height), config);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        bitmap = nullptr;
    }
    if (config) env->DeleteLocalRef(config);
    env->DeleteLocalRef(config_cls);
    env->DeleteLocalRef(bitmap_cls);
    return bitmap;
}

static bool copy_kitty_pixels_to_bitmap(
    JNIEnv* env, jobject bitmap, const uint8_t* source, size_t source_len,
    uint32_t width, uint32_t height, GhosttyKittyImageFormat format)
{
    if (!bitmap || !source || width == 0 || height == 0) return false;

    size_t source_bpp = 0;
    switch (format) {
        case GHOSTTY_KITTY_IMAGE_FORMAT_RGBA: source_bpp = 4; break;
        case GHOSTTY_KITTY_IMAGE_FORMAT_RGB: source_bpp = 3; break;
        case GHOSTTY_KITTY_IMAGE_FORMAT_GRAY_ALPHA: source_bpp = 2; break;
        case GHOSTTY_KITTY_IMAGE_FORMAT_GRAY: source_bpp = 1; break;
        default: return false;
    }
    const size_t source_stride = static_cast<size_t>(width) * source_bpp;
    if (height > 0 && source_stride > source_len / height) return false;

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        info.width != width || info.height != height) {
        return false;
    }

    void* raw_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &raw_pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return false;
    }

    auto* destination = static_cast<uint8_t*>(raw_pixels);
    for (uint32_t y = 0; y < height; y++) {
        const uint8_t* src = source + static_cast<size_t>(y) * source_stride;
        uint8_t* dst = destination + static_cast<size_t>(y) * info.stride;
        if (format == GHOSTTY_KITTY_IMAGE_FORMAT_RGBA) {
            memcpy(dst, src, static_cast<size_t>(width) * 4);
            continue;
        }
        for (uint32_t x = 0; x < width; x++) {
            if (format == GHOSTTY_KITTY_IMAGE_FORMAT_RGB) {
                dst[x * 4] = src[x * 3];
                dst[x * 4 + 1] = src[x * 3 + 1];
                dst[x * 4 + 2] = src[x * 3 + 2];
                dst[x * 4 + 3] = 0xFF;
            } else if (format == GHOSTTY_KITTY_IMAGE_FORMAT_GRAY_ALPHA) {
                dst[x * 4] = dst[x * 4 + 1] = dst[x * 4 + 2] = src[x * 2];
                dst[x * 4 + 3] = src[x * 2 + 1];
            } else {
                dst[x * 4] = dst[x * 4 + 1] = dst[x * 4 + 2] = src[x];
                dst[x * 4 + 3] = 0xFF;
            }
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}

static KittyBitmapCache* bitmap_cache_for(
    JNIEnv* env, NativeSession* session, uint32_t image_id,
    uint32_t width, uint32_t height)
{
    KittyBitmapCache* cache = nullptr;
    for (auto& candidate : session->kitty_bitmap_cache) {
        if (candidate.image_id == image_id) {
            cache = &candidate;
            break;
        }
    }
    if (!cache) {
        // The Java frame supports at most 16 simultaneous placements. Keep the
        // native bitmap cache to the same bound so changing image IDs cannot
        // grow memory without limit, while preserving bitmaps across streamed
        // retransmissions of the same ID (the common full-screen UI case).
        if (session->kitty_bitmap_cache.size() >= 16) {
            if (session->kitty_bitmap_cache.front().bitmap) {
                env->DeleteGlobalRef(session->kitty_bitmap_cache.front().bitmap);
            }
            session->kitty_bitmap_cache.erase(session->kitty_bitmap_cache.begin());
        }
        session->kitty_bitmap_cache.emplace_back();
        cache = &session->kitty_bitmap_cache.back();
        cache->image_id = image_id;
    }
    if (cache->bitmap && (cache->width != width || cache->height != height)) {
        env->DeleteGlobalRef(cache->bitmap);
        cache->bitmap = nullptr;
        cache->generation = 0;
    }
    if (!cache->bitmap) {
        jobject local_bitmap = create_argb_bitmap(env, width, height);
        if (!local_bitmap) return nullptr;
        cache->bitmap = env->NewGlobalRef(local_bitmap);
        env->DeleteLocalRef(local_bitmap);
        if (!cache->bitmap) return nullptr;
        cache->width = width;
        cache->height = height;
    }
    return cache;
}

static void update_kitty_graphics(JNIEnv* env, NativeSession* session, jobject jFrame) {
    jclass frame_cls = env->GetObjectClass(jFrame);
    jfieldID count_field = env->GetFieldID(frame_cls, "kittyPlacementCount", "I");

    GhosttyKittyGraphics graphics = nullptr;
    GhosttyResult terminal_graphics_result = ghostty_terminal_get(
        session->terminal, GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS, &graphics);
    if (terminal_graphics_result != GHOSTTY_SUCCESS || !graphics) {
        env->DeleteLocalRef(frame_cls);
        return;
    }
    if (!session->kitty_placement_it &&
        ghostty_kitty_graphics_placement_iterator_new(
            nullptr, &session->kitty_placement_it) != GHOSTTY_SUCCESS) {
        env->DeleteLocalRef(frame_cls);
        return;
    }
    GhosttyResult iterator_result = ghostty_kitty_graphics_get(
            graphics, GHOSTTY_KITTY_GRAPHICS_DATA_PLACEMENT_ITERATOR,
            &session->kitty_placement_it);
    if (iterator_result != GHOSTTY_SUCCESS) {
        env->DeleteLocalRef(frame_cls);
        return;
    }

    jobjectArray bitmaps = static_cast<jobjectArray>(env->GetObjectField(
        jFrame, env->GetFieldID(frame_cls, "kittyBitmaps", "[Landroid/graphics/Bitmap;")));
    const jsize capacity = bitmaps ? env->GetArrayLength(bitmaps) : 0;
    if (capacity <= 0) {
        if (bitmaps) env->DeleteLocalRef(bitmaps);
        env->DeleteLocalRef(frame_cls);
        return;
    }

    const char* names[] = {
        "kittyImageIds", "kittyDstLeft", "kittyDstTop", "kittyDstWidth",
        "kittyDstHeight", "kittySrcLeft", "kittySrcTop", "kittySrcWidth",
        "kittySrcHeight", "kittyZ"
    };
    jint values[10][16]{};
    jobject placement_bitmaps[16]{};
    bool incomplete_frame = false;
    int count = 0;
    while (count < capacity && count < 16 &&
           ghostty_kitty_graphics_placement_next(session->kitty_placement_it)) {
        uint32_t image_id = 0, x_offset = 0, y_offset = 0;
        int32_t z = 0;
        ghostty_kitty_graphics_placement_get(
            session->kitty_placement_it, GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_IMAGE_ID,
            &image_id);
        ghostty_kitty_graphics_placement_get(
            session->kitty_placement_it, GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_X_OFFSET,
            &x_offset);
        ghostty_kitty_graphics_placement_get(
            session->kitty_placement_it, GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_Y_OFFSET,
            &y_offset);
        ghostty_kitty_graphics_placement_get(
            session->kitty_placement_it, GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_Z, &z);

        GhosttyKittyGraphicsImage image = ghostty_kitty_graphics_image(graphics, image_id);
        if (!image) continue;
        GhosttyKittyGraphicsPlacementRenderInfo render_info =
            GHOSTTY_INIT_SIZED(GhosttyKittyGraphicsPlacementRenderInfo);
        GhosttyResult render_result = ghostty_kitty_graphics_placement_render_info(
                session->kitty_placement_it, image, session->terminal,
                &render_info);
        if (render_result != GHOSTTY_SUCCESS || !render_info.viewport_visible) {
            continue;
        }

        uint32_t width = 0, height = 0;
        uint64_t generation = 0;
        size_t data_len = 0;
        const uint8_t* data = nullptr;
        GhosttyKittyImageFormat format = GHOSTTY_KITTY_IMAGE_FORMAT_RGBA;
        ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_WIDTH, &width);
        ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_HEIGHT, &height);
        ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_FORMAT, &format);
        ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_DATA_LEN, &data_len);
        ghostty_kitty_graphics_image_get(image, GHOSTTY_KITTY_IMAGE_DATA_GENERATION, &generation);
        if (ghostty_kitty_graphics_image_get(
                image, GHOSTTY_KITTY_IMAGE_DATA_DATA_PTR, &data) != GHOSTTY_SUCCESS ||
            !data || width == 0 || height == 0) {
            // A streamed image is visible in placement metadata before its
            // final chunk has arrived. Keep drawing the previous complete
            // frame instead of flashing to black between every update.
            incomplete_frame = true;
            continue;
        }

        KittyBitmapCache* cache = bitmap_cache_for(env, session, image_id, width, height);
        if (!cache) {
            incomplete_frame = true;
            continue;
        }
        if (cache->generation != generation) {
            if (!copy_kitty_pixels_to_bitmap(
                    env, cache->bitmap, data, data_len, width, height, format)) {
                incomplete_frame = true;
                continue;
            }
            cache->generation = generation;
        }

        placement_bitmaps[count] = cache->bitmap;
        values[0][count] = static_cast<jint>(image_id);
        values[1][count] = render_info.viewport_col * static_cast<int32_t>(session->cell_width) +
                           static_cast<int32_t>(x_offset);
        values[2][count] = render_info.viewport_row * static_cast<int32_t>(session->cell_height) +
                           static_cast<int32_t>(y_offset);
        values[3][count] = static_cast<jint>(render_info.pixel_width);
        values[4][count] = static_cast<jint>(render_info.pixel_height);
        values[5][count] = static_cast<jint>(render_info.source_x);
        values[6][count] = static_cast<jint>(render_info.source_y);
        values[7][count] = static_cast<jint>(render_info.source_width);
        values[8][count] = static_cast<jint>(render_info.source_height);
        values[9][count] = static_cast<jint>(z);
        count++;
    }

    if (incomplete_frame) {
        env->DeleteLocalRef(bitmaps);
        env->DeleteLocalRef(frame_cls);
        return;
    }

    // Some producers remove the old placement before streaming the next
    // multi-megabyte frame. Retain the last complete bitmap while PTY bytes
    // are actively arriving, then allow a real delete/exit to clear shortly
    // after the stream goes quiet.
    jint previous_count = env->GetIntField(jFrame, count_field);
    if (count == 0 && previous_count > 0 &&
        monotonic_millis() - session->last_pty_activity_ms.load() < 1500) {
        env->DeleteLocalRef(bitmaps);
        env->DeleteLocalRef(frame_cls);
        return;
    }

    for (int i = 0; i < count; i++) {
        env->SetObjectArrayElement(bitmaps, i, placement_bitmaps[i]);
    }
    for (int i = count; i < capacity; i++) {
        env->SetObjectArrayElement(bitmaps, i, nullptr);
    }

    for (int i = 0; i < 10; i++) {
        jintArray array = static_cast<jintArray>(env->GetObjectField(
            jFrame, env->GetFieldID(frame_cls, names[i], "[I")));
        if (array) {
            if (count > 0) env->SetIntArrayRegion(array, 0, count, values[i]);
            env->DeleteLocalRef(array);
        }
    }
    env->SetIntField(jFrame, count_field, count);
    env->DeleteLocalRef(bitmaps);
    env->DeleteLocalRef(frame_cls);
}

JNIEXPORT jboolean JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeUpdateRender(
    JNIEnv* env, jclass clazz, jlong sessionPtr,
    jobject jFrame, jcharArray jChars, jintArray jFg, jintArray jBg, jintArray jStyle,
    jintArray jUnderlineStyle, jintArray jUnderlineColor, jintArray jCellSemantic, jintArray jRowSemantic, jbooleanArray jDirty)
{
    (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session || session->is_closed.load() || !session->terminal || !session->render_state) {
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(session->session_mutex);
    // Synchronized Output (DEC 2026): suspend rendering while mode is active
    {
        GhosttyTerminalModeConfig cfg = {};
        cfg.mode = GHOSTTY_MODE_SYNC_OUTPUT;
        GhosttyResult modeRes = ghostty_terminal_get(session->terminal, GHOSTTY_TERMINAL_DATA_MODE, &cfg);
        if (modeRes == GHOSTTY_SUCCESS && cfg.value) {
            return JNI_FALSE;
        }
    }
    GhosttyResult update_res = ghostty_render_state_update(session->render_state, session->terminal);
    if (update_res != GHOSTTY_SUCCESS) {
        return JNI_FALSE;
    }

    GhosttyRenderStateDirty dirty_state = GHOSTTY_RENDER_STATE_DIRTY_FALSE;
    ghostty_render_state_get(session->render_state, GHOSTTY_RENDER_STATE_DATA_DIRTY, &dirty_state);

    uint16_t cols = 0, rows = 0;
    ghostty_render_state_get(session->render_state, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
    ghostty_render_state_get(session->render_state, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);

    bool cursor_has_val = false;
    uint16_t cx = 0, cy = 0;
    ghostty_render_state_get(session->render_state, GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_HAS_VALUE, &cursor_has_val);
    if (cursor_has_val) {
        ghostty_render_state_get(session->render_state, GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_X, &cx);
        ghostty_render_state_get(session->render_state, GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_Y, &cy);
    }

    GhosttyRenderStateCursorVisualStyle cstyle = GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK;
    ghostty_render_state_get(session->render_state, GHOSTTY_RENDER_STATE_DATA_CURSOR_VISUAL_STYLE, &cstyle);

    bool cvisible = true, cblink = false;
    ghostty_render_state_get(session->render_state, GHOSTTY_RENDER_STATE_DATA_CURSOR_VISIBLE, &cvisible);
    ghostty_render_state_get(session->render_state, GHOSTTY_RENDER_STATE_DATA_CURSOR_BLINKING, &cblink);

    GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
    ghostty_render_state_get(session->render_state, GHOSTTY_RENDER_STATE_DATA_COLORS, &colors);

    int default_bg = 0xFF000000 | (colors.background.r << 16) | (colors.background.g << 8) | colors.background.b;
    int default_fg = 0xFF000000 | (colors.foreground.r << 16) | (colors.foreground.g << 8) | colors.foreground.b;

    if (default_fg == default_bg || (colors.foreground.r == 0 && colors.foreground.g == 0 && colors.foreground.b == 0)) {
        default_fg = 0xFFE0E0E0;
        default_bg = 0xFF181818;
    }
    int cursor_color = colors.cursor_has_value ? (0xFF000000 | (colors.cursor.r << 16) | (colors.cursor.g << 8) | colors.cursor.b) : default_fg;

    // Update fields in jFrame
    jclass frameCls = env->GetObjectClass(jFrame);
    env->SetIntField(jFrame, env->GetFieldID(frameCls, "cols", "I"), cols);
    env->SetIntField(jFrame, env->GetFieldID(frameCls, "rows", "I"), rows);
    env->SetIntField(jFrame, env->GetFieldID(frameCls, "cursorX", "I"), cx);
    env->SetIntField(jFrame, env->GetFieldID(frameCls, "cursorY", "I"), cy);
    env->SetIntField(jFrame, env->GetFieldID(frameCls, "cursorStyle", "I"), static_cast<int>(cstyle));
    env->SetBooleanField(jFrame, env->GetFieldID(frameCls, "cursorVisible", "Z"), cvisible);
    env->SetBooleanField(jFrame, env->GetFieldID(frameCls, "cursorBlinking", "Z"), cblink);
    env->SetIntField(jFrame, env->GetFieldID(frameCls, "defaultBgColor", "I"), default_bg);
    env->SetIntField(jFrame, env->GetFieldID(frameCls, "defaultFgColor", "I"), default_fg);
    env->SetIntField(jFrame, env->GetFieldID(frameCls, "cursorColor", "I"), cursor_color);
    env->SetBooleanField(jFrame, env->GetFieldID(frameCls, "hasCursorColor", "Z"), colors.cursor_has_value);
    env->SetBooleanField(jFrame, env->GetFieldID(frameCls, "isDirty", "Z"), dirty_state != GHOSTTY_RENDER_STATE_DIRTY_FALSE);

    // Expose terminal interaction state so Java can react to alt-screen exits
    // (the kitty keyboard spec requires resetting keyboard flags then; some
    // TUIs forget to pop them, leaving the terminal in CSI-u mode).
    GhosttyTerminalScreen scr = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
    ghostty_terminal_get(session->terminal, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &scr);
    uint8_t kitty_flags = 0;
    ghostty_terminal_get(session->terminal, GHOSTTY_TERMINAL_DATA_KITTY_KEYBOARD_FLAGS, &kitty_flags);
    env->SetBooleanField(jFrame, env->GetFieldID(frameCls, "altScreenActive", "Z"),
                         scr == GHOSTTY_TERMINAL_SCREEN_ALTERNATE);
    env->SetIntField(jFrame, env->GetFieldID(frameCls, "kittyKeyboardFlags", "I"), kitty_flags);

    // Viewport scrollbar state: lets the Java side detect viewport movement
    // (pin the view while a text selection is active) and position handles.
    GhosttyTerminalScrollbar scrollbar = {};
    ghostty_terminal_get(session->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &scrollbar);
    env->SetLongField(jFrame, env->GetFieldID(frameCls, "scrollTotal", "J"), (jlong)scrollbar.total);
    env->SetLongField(jFrame, env->GetFieldID(frameCls, "scrollOffset", "J"), (jlong)scrollbar.offset);
    env->SetLongField(jFrame, env->GetFieldID(frameCls, "scrollLen", "J"), (jlong)scrollbar.len);

    // Populate palette array
    jfieldID palField = env->GetFieldID(frameCls, "palette", "[I");
    jintArray jPal = (jintArray)env->GetObjectField(jFrame, palField);
    if (jPal) {
        jint palBuf[256];
        for (int i = 0; i < 256; i++) {
            palBuf[i] = 0xFF000000 | (colors.palette[i].r << 16) | (colors.palette[i].g << 8) | colors.palette[i].b;
        }
        env->SetIntArrayRegion(jPal, 0, 256, palBuf);
        env->DeleteLocalRef(jPal);
    }
    env->DeleteLocalRef(frameCls);

    // Populate cell arrays
    if (jChars && jFg && jBg && jStyle && jDirty) {
        jchar* charsPtr = (jchar*)env->GetPrimitiveArrayCritical(jChars, nullptr);
        jint* fgPtr = (jint*)env->GetPrimitiveArrayCritical(jFg, nullptr);
        jint* bgPtr = (jint*)env->GetPrimitiveArrayCritical(jBg, nullptr);
        jint* stylePtr = (jint*)env->GetPrimitiveArrayCritical(jStyle, nullptr);
        jint* ulStylePtr = jUnderlineStyle ? (jint*)env->GetPrimitiveArrayCritical(jUnderlineStyle, nullptr) : nullptr;
        jint* ulColorPtr = jUnderlineColor ? (jint*)env->GetPrimitiveArrayCritical(jUnderlineColor, nullptr) : nullptr;
        jint* cellSemPtr = jCellSemantic ? (jint*)env->GetPrimitiveArrayCritical(jCellSemantic, nullptr) : nullptr;
        jint* rowSemPtr = jRowSemantic ? (jint*)env->GetPrimitiveArrayCritical(jRowSemantic, nullptr) : nullptr;
        jboolean* dirtyPtr = (jboolean*)env->GetPrimitiveArrayCritical(jDirty, nullptr);

        if (charsPtr && fgPtr && bgPtr && stylePtr && dirtyPtr) {
            ghostty_render_state_get(session->render_state, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &session->row_it);

            uint16_t r = 0;
            while (ghostty_render_state_row_iterator_next(session->row_it) && r < rows) {
                bool row_dirty = true;
                ghostty_render_state_row_get(session->row_it, GHOSTTY_RENDER_STATE_ROW_DATA_DIRTY, &row_dirty);
                dirtyPtr[r] = row_dirty ? JNI_TRUE : JNI_FALSE;
                if (rowSemPtr) {
                    GhosttyRow rawRow = 0;
                    GhosttyRowSemanticPrompt rowPrompt = GHOSTTY_ROW_SEMANTIC_NONE;
                    if (ghostty_render_state_row_get(session->row_it, GHOSTTY_RENDER_STATE_ROW_DATA_RAW, &rawRow) == GHOSTTY_SUCCESS) {
                        ghostty_row_get(rawRow, GHOSTTY_ROW_DATA_SEMANTIC_PROMPT, &rowPrompt);
                    }
                    rowSemPtr[r] = (jint)rowPrompt;
                }

                ghostty_render_state_row_get(session->row_it, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &session->row_cells);

                uint16_t c = 0;
                while (ghostty_render_state_row_cells_next(session->row_cells) && c < cols) {
                    size_t idx = static_cast<size_t>(r) * cols + c;

                    // Codepoint
                    uint32_t graphemes_len = 0;
                    ghostty_render_state_row_cells_get(session->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN, &graphemes_len);
                    uint32_t cp = ' ';
                    if (graphemes_len > 0) {
                        uint32_t buf[4];
                        if (ghostty_render_state_row_cells_get(session->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF, buf) == GHOSTTY_SUCCESS) {
                            cp = buf[0];
                        }
                    }
                    charsPtr[idx] = (cp <= 0xFFFF) ? static_cast<jchar>(cp) : static_cast<jchar>('?');
                    if (cp != ' ' && cp != 0 && r < 5 && c < 20) {
                        LOGD("Cell (%d,%d): cp=%u char='%c' fg=0x%08X bg=0x%08X", r, c, cp, (char)cp, (unsigned int)fgPtr[idx], (unsigned int)bgPtr[idx]);
                    }

                    // FG / BG
                    GhosttyColorRgb fg_rgb, bg_rgb;
                    bool has_fg = (ghostty_render_state_row_cells_get(session->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, &fg_rgb) == GHOSTTY_SUCCESS);
                    bool has_bg = (ghostty_render_state_row_cells_get(session->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, &bg_rgb) == GHOSTTY_SUCCESS);

                    fgPtr[idx] = has_fg ? (0xFF000000 | (fg_rgb.r << 16) | (fg_rgb.g << 8) | fg_rgb.b) : default_fg;
                    bgPtr[idx] = has_bg ? (0xFF000000 | (bg_rgb.r << 16) | (bg_rgb.g << 8) | bg_rgb.b) : default_bg;

                    // Style
                    int flags = 0;
                    int ulStyle = 0;
                    int ulColor = 0;
                    bool has_style = false;
                    ghostty_render_state_row_cells_get(session->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_HAS_STYLING, &has_style);
                    if (has_style) {
                        GhosttyStyle st = GHOSTTY_INIT_SIZED(GhosttyStyle);
                        ghostty_render_state_row_cells_get(session->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &st);
                        if (st.bold) flags |= (1 << 0);
                        if (st.italic) flags |= (1 << 1);
                        if (st.underline != 0) flags |= (1 << 2);
                        if (st.strikethrough) flags |= (1 << 3);
                        if (st.faint) flags |= (1 << 4);
                        if (st.inverse) flags |= (1 << 5);
                        if (st.blink) flags |= (1 << 6);
                        if (st.invisible) flags |= (1 << 7);
                        ulStyle = st.underline;
                        if (st.underline_color.tag == GHOSTTY_STYLE_COLOR_PALETTE) {
                            GhosttyColorPaletteIndex pIdx = st.underline_color.value.palette;
                            GhosttyColorRgb rgb = colors.palette[pIdx];
                            ulColor = 0xFF000000 | (rgb.r << 16) | (rgb.g << 8) | rgb.b;
                        } else if (st.underline_color.tag == GHOSTTY_STYLE_COLOR_RGB) {
                            GhosttyColorRgb rgb = st.underline_color.value.rgb;
                            ulColor = 0xFF000000 | (rgb.r << 16) | (rgb.g << 8) | rgb.b;
                        }
                    }
                    bool selected = false;
                    ghostty_render_state_row_cells_get(session->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_SELECTED, &selected);
                    if (selected) flags |= (1 << 8);

                    // OSC 8 hyperlink: expose as style bit 9 so Java can underline and handle taps
                    // and semantic content (OSC 133) for prompt navigation / copy output
                    GhosttyCell rawCell = 0;
                    bool hasRawCell = (ghostty_render_state_row_cells_get(session->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &rawCell) == GHOSTTY_SUCCESS);
                    bool hasHyperlink = false;
                    if (hasRawCell) {
                        ghostty_cell_get(rawCell, GHOSTTY_CELL_DATA_HAS_HYPERLINK, &hasHyperlink);
                        if (hasHyperlink) flags |= (1 << 9);
                    }
                    if (cellSemPtr) {
                        GhosttyCellSemanticContent sem = GHOSTTY_CELL_SEMANTIC_OUTPUT;
                        if (hasRawCell) {
                            ghostty_cell_get(rawCell, GHOSTTY_CELL_DATA_SEMANTIC_CONTENT, &sem);
                        }
                        cellSemPtr[idx] = (jint)sem;
                    }

                    stylePtr[idx] = flags;
                    if (ulStylePtr) ulStylePtr[idx] = ulStyle;
                    if (ulColorPtr) ulColorPtr[idx] = ulColor;
                    c++;
                }

                // Fill remainder of row if any
                for (; c < cols; c++) {
                    size_t idx = static_cast<size_t>(r) * cols + c;
                    charsPtr[idx] = ' ';
                    fgPtr[idx] = default_fg;
                    bgPtr[idx] = default_bg;
                    stylePtr[idx] = 0;
                    if (ulStylePtr) ulStylePtr[idx] = 0;
                    if (ulColorPtr) ulColorPtr[idx] = 0;
                    if (cellSemPtr) cellSemPtr[idx] = GHOSTTY_CELL_SEMANTIC_OUTPUT;
                }
                r++;
            }

            // Fill remainder of screen if any
            for (; r < rows; r++) {
                dirtyPtr[r] = JNI_FALSE;
                if (rowSemPtr) rowSemPtr[r] = GHOSTTY_ROW_SEMANTIC_NONE;
                for (uint16_t c = 0; c < cols; c++) {
                    size_t idx = static_cast<size_t>(r) * cols + c;
                    charsPtr[idx] = ' ';
                    fgPtr[idx] = default_fg;
                    bgPtr[idx] = default_bg;
                    stylePtr[idx] = 0;
                    if (ulStylePtr) ulStylePtr[idx] = 0;
                    if (ulColorPtr) ulColorPtr[idx] = 0;
                    if (cellSemPtr) cellSemPtr[idx] = GHOSTTY_CELL_SEMANTIC_OUTPUT;
                }
            }
        }

        if (dirtyPtr) env->ReleasePrimitiveArrayCritical(jDirty, dirtyPtr, 0);
        if (rowSemPtr) env->ReleasePrimitiveArrayCritical(jRowSemantic, rowSemPtr, 0);
        if (cellSemPtr) env->ReleasePrimitiveArrayCritical(jCellSemantic, cellSemPtr, 0);
        if (ulColorPtr) env->ReleasePrimitiveArrayCritical(jUnderlineColor, ulColorPtr, 0);
        if (ulStylePtr) env->ReleasePrimitiveArrayCritical(jUnderlineStyle, ulStylePtr, 0);
        if (stylePtr) env->ReleasePrimitiveArrayCritical(jStyle, stylePtr, 0);
        if (bgPtr) env->ReleasePrimitiveArrayCritical(jBg, bgPtr, 0);
        if (fgPtr) env->ReleasePrimitiveArrayCritical(jFg, fgPtr, 0);
        if (charsPtr) env->ReleasePrimitiveArrayCritical(jChars, charsPtr, 0);
    }

    // Kitty graphics are terminal state rather than text cells, so export
    // their bitmaps and placement geometry separately for the Canvas renderer.
    update_kitty_graphics(env, session, jFrame);

    // Mark render state clean
    ghostty_render_state_clean(session->render_state);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeClose(
    JNIEnv* env, jclass clazz, jlong sessionPtr)
{
    (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session) return;

    {
        std::lock_guard<std::mutex> lock(session->session_mutex);
        if (session->is_closed.load()) return;
        session->is_closed.store(true);

        if (session->ptm_fd >= 0) {
            close(session->ptm_fd);
            session->ptm_fd = -1;
        }

        if (session->child_pid > 0) {
            kill(session->child_pid, SIGHUP);
        }

        if (session->read_thread) {
            pthread_join(session->read_thread, nullptr);
            session->read_thread = 0;
        }

        if (session->mouse_event) ghostty_mouse_event_free(session->mouse_event);
        if (session->mouse_encoder) ghostty_mouse_encoder_free(session->mouse_encoder);
        if (session->key_event) ghostty_key_event_free(session->key_event);
        if (session->key_encoder) ghostty_key_encoder_free(session->key_encoder);
        if (session->row_cells) ghostty_render_state_row_cells_free(session->row_cells);
        if (session->row_it) ghostty_render_state_row_iterator_free(session->row_it);
        if (session->kitty_placement_it) {
            ghostty_kitty_graphics_placement_iterator_free(session->kitty_placement_it);
            session->kitty_placement_it = nullptr;
        }
        for (auto& cache : session->kitty_bitmap_cache) {
            if (cache.bitmap) env->DeleteGlobalRef(cache.bitmap);
        }
        session->kitty_bitmap_cache.clear();
        if (session->render_state) ghostty_render_state_free(session->render_state);
        if (session->terminal) ghostty_terminal_free(session->terminal);

        if (session->java_callback) {
            env->DeleteGlobalRef(session->java_callback);
            session->java_callback = nullptr;
        }
    }

    delete session;
    LOGI("Terminal session closed and destroyed");
}

JNIEXPORT jint JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeWaitChild(
    JNIEnv* env, jclass clazz, jlong sessionPtr)
{
    (void)env; (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session || session->child_pid <= 0) return -1;

    int status = 0;
    waitpid(session->child_pid, &status, 0);
    return status;
}

JNIEXPORT jstring JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeFormatTerminal(
    JNIEnv* env, jclass clazz, jlong sessionPtr, jint format)
{
    (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session || session->is_closed.load() || !session->terminal) {
        return env->NewStringUTF("");
    }

    std::lock_guard<std::mutex> lock(session->session_mutex);
    GhosttyFormatterTerminalOptions opts = GHOSTTY_INIT_SIZED(GhosttyFormatterTerminalOptions);
    opts.emit = static_cast<GhosttyFormatterFormat>(format);
    opts.trim = true;
    opts.unwrap = false;

    GhosttyFormatter fmt = nullptr;
    GhosttyResult res = ghostty_formatter_terminal_new(nullptr, &fmt, session->terminal, opts);
    if (res != GHOSTTY_SUCCESS || !fmt) {
        return env->NewStringUTF("");
    }

    uint8_t* out_ptr = nullptr;
    size_t out_len = 0;
    res = ghostty_formatter_format_alloc(fmt, nullptr, &out_ptr, &out_len);
    ghostty_formatter_free(fmt);

    if (res != GHOSTTY_SUCCESS || !out_ptr) {
        return env->NewStringUTF("");
    }

    std::string text(reinterpret_cast<const char*>(out_ptr), out_len);
    ghostty_free(nullptr, out_ptr, out_len);

    return env->NewStringUTF(text.c_str());
}

JNIEXPORT void JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeSendMouseEvent(
    JNIEnv* env, jclass clazz, jlong sessionPtr,
    jint action, jint button, jint mods,
    jfloat x, jfloat y,
    jint cellWidth, jint cellHeight,
    jint screenWidth, jint screenHeight)
{
    (void)env; (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session || session->is_closed.load() || !session->terminal || session->ptm_fd < 0) return;

    std::lock_guard<std::mutex> lock(session->session_mutex);
    if (!session->mouse_encoder || !session->mouse_event) {
        if (!session->mouse_encoder) ghostty_mouse_encoder_new(nullptr, &session->mouse_encoder);
        if (!session->mouse_event) ghostty_mouse_event_new(nullptr, &session->mouse_event);
        if (!session->mouse_encoder || !session->mouse_event) return;
    }

    ghostty_mouse_encoder_setopt_from_terminal(session->mouse_encoder, session->terminal);

    GhosttyMouseEncoderSize sz = GHOSTTY_INIT_SIZED(GhosttyMouseEncoderSize);
    sz.screen_width = screenWidth > 0 ? (uint32_t)screenWidth : (uint32_t)session->cols * (cellWidth > 0 ? cellWidth : 10);
    sz.screen_height = screenHeight > 0 ? (uint32_t)screenHeight : (uint32_t)session->rows * (cellHeight > 0 ? cellHeight : 20);
    sz.cell_width = cellWidth > 0 ? (uint32_t)cellWidth : 10;
    sz.cell_height = cellHeight > 0 ? (uint32_t)cellHeight : 20;
    sz.padding_top = 0;
    sz.padding_bottom = 0;
    sz.padding_left = 0;
    sz.padding_right = 0;
    ghostty_mouse_encoder_setopt(session->mouse_encoder, GHOSTTY_MOUSE_ENCODER_OPT_SIZE, &sz);

    GhosttyMouseAction gAction = GHOSTTY_MOUSE_ACTION_PRESS;
    if (action == 1) gAction = GHOSTTY_MOUSE_ACTION_RELEASE;
    else if (action == 2) gAction = GHOSTTY_MOUSE_ACTION_MOTION;
    ghostty_mouse_event_set_action(session->mouse_event, gAction);

    if (button == 0) {
        ghostty_mouse_event_clear_button(session->mouse_event);
    } else {
        GhosttyMouseButton gButton = GHOSTTY_MOUSE_BUTTON_LEFT;
        switch (button) {
            case 1: gButton = GHOSTTY_MOUSE_BUTTON_LEFT; break;
            case 2: gButton = GHOSTTY_MOUSE_BUTTON_RIGHT; break;
            case 3: gButton = GHOSTTY_MOUSE_BUTTON_MIDDLE; break;
            case 4: gButton = GHOSTTY_MOUSE_BUTTON_FOUR; break;
            case 5: gButton = GHOSTTY_MOUSE_BUTTON_FIVE; break;
            case 6: gButton = GHOSTTY_MOUSE_BUTTON_SIX; break;
            case 7: gButton = GHOSTTY_MOUSE_BUTTON_SEVEN; break;
            case 8: gButton = GHOSTTY_MOUSE_BUTTON_EIGHT; break;
            default: gButton = GHOSTTY_MOUSE_BUTTON_LEFT; break;
        }
        ghostty_mouse_event_set_button(session->mouse_event, gButton);
    }
    ghostty_mouse_event_set_mods(session->mouse_event, static_cast<GhosttyMods>(mods));
    GhosttyMousePosition pos;
    pos.x = x;
    pos.y = y;
    ghostty_mouse_event_set_position(session->mouse_event, pos);

    char buf[256];
    size_t written = 0;
    GhosttyResult res = ghostty_mouse_encoder_encode(session->mouse_encoder, session->mouse_event, buf, sizeof(buf), &written);
    if (res == GHOSTTY_SUCCESS && written > 0) {
        write(session->ptm_fd, buf, written);
    }
}

JNIEXPORT void JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeSetSelection(
    JNIEnv* env, jclass clazz, jlong sessionPtr,
    jint startCol, jint startRow, jint endCol, jint endRow, jboolean isRectangle)
{
    (void)env; (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session || session->is_closed.load() || !session->terminal) return;

    std::lock_guard<std::mutex> lock(session->session_mutex);

    // Clamp cols/rows to valid range
    auto clampCoord = [&](int col, int row) -> GhosttyPoint {
        GhosttyPoint p;
        p.tag = GHOSTTY_POINT_TAG_VIEWPORT;
        p.value.coordinate.x = (uint16_t)(col < 0 ? 0 : col);
        p.value.coordinate.y = (uint32_t)(row < 0 ? 0 : row);
        return p;
    };

    GhosttyPoint sp = clampCoord(startCol, startRow);
    GhosttyPoint ep = clampCoord(endCol, endRow);

    GhosttyGridRef sRef = GHOSTTY_INIT_SIZED(GhosttyGridRef);
    GhosttyGridRef eRef = GHOSTTY_INIT_SIZED(GhosttyGridRef);

    GhosttyResult r1 = ghostty_terminal_grid_ref(session->terminal, sp, &sRef);
    GhosttyResult r2 = ghostty_terminal_grid_ref(session->terminal, ep, &eRef);
    if (r1 != GHOSTTY_SUCCESS || r2 != GHOSTTY_SUCCESS) {
        // Try active tag as fallback
        sp.tag = GHOSTTY_POINT_TAG_ACTIVE;
        ep.tag = GHOSTTY_POINT_TAG_ACTIVE;
        r1 = ghostty_terminal_grid_ref(session->terminal, sp, &sRef);
        r2 = ghostty_terminal_grid_ref(session->terminal, ep, &eRef);
        if (r1 != GHOSTTY_SUCCESS || r2 != GHOSTTY_SUCCESS) return;
    }

    GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
    sel.start = sRef;
    sel.end = eRef;
    sel.rectangle = isRectangle ? true : false;

    ghostty_terminal_set(session->terminal, GHOSTTY_TERMINAL_OPT_SELECTION, &sel);
}

JNIEXPORT void JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeClearSelection(
    JNIEnv* env, jclass clazz, jlong sessionPtr)
{
    (void)env; (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session || session->is_closed.load() || !session->terminal) return;
    std::lock_guard<std::mutex> lock(session->session_mutex);
    ghostty_terminal_set(session->terminal, GHOSTTY_TERMINAL_OPT_SELECTION, nullptr);
}

// Long-press selection: select the word under the given viewport cell and
// install it as the terminal's active (text-anchored, tracked) selection.
// Returns the bounds in viewport coordinates as
// {startCol, startRow, endCol, endRow} (ordered), or nullptr when the cell
// cannot be resolved.
JNIEXPORT jintArray JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeSelectWord(
    JNIEnv* env, jclass clazz, jlong sessionPtr, jint col, jint row)
{
    (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session || session->is_closed.load() || !session->terminal) return nullptr;

    std::lock_guard<std::mutex> lock(session->session_mutex);

    GhosttyPoint p = {};
    p.tag = GHOSTTY_POINT_TAG_VIEWPORT;
    p.value.coordinate.x = static_cast<uint16_t>(col < 0 ? 0 : col);
    p.value.coordinate.y = static_cast<uint32_t>(row < 0 ? 0 : row);

    GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
    if (ghostty_terminal_grid_ref(session->terminal, p, &ref) != GHOSTTY_SUCCESS) return nullptr;

    GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
    GhosttyTerminalSelectWordOptions opts = GHOSTTY_INIT_SIZED(GhosttyTerminalSelectWordOptions);
    opts.ref = ref;
    if (ghostty_terminal_select_word(session->terminal, &opts, &sel) != GHOSTTY_SUCCESS) {
        // Whitespace / nothing selectable: anchor an empty selection on the cell
        sel.start = ref;
        sel.end = ref;
        sel.rectangle = false;
    }

    // Normalize to forward order so the returned bounds are top-left/bottom-right
    GhosttySelection ordered = GHOSTTY_INIT_SIZED(GhosttySelection);
    if (ghostty_terminal_selection_ordered(session->terminal, &sel,
                                           GHOSTTY_SELECTION_ORDER_FORWARD, &ordered) == GHOSTTY_SUCCESS) {
        sel = ordered;
    }

    GhosttyPointCoordinate c1 = {}, c2 = {};
    if (ghostty_terminal_point_from_grid_ref(session->terminal, &sel.start,
                                             GHOSTTY_POINT_TAG_VIEWPORT, &c1) != GHOSTTY_SUCCESS ||
        ghostty_terminal_point_from_grid_ref(session->terminal, &sel.end,
                                             GHOSTTY_POINT_TAG_VIEWPORT, &c2) != GHOSTTY_SUCCESS) {
        // Should not happen for an in-viewport word; fall back to the press cell
        c1.x = p.value.coordinate.x;
        c1.y = p.value.coordinate.y;
        c2 = c1;
        sel.start = ref;
        sel.end = ref;
    }

    ghostty_terminal_set(session->terminal, GHOSTTY_TERMINAL_OPT_SELECTION, &sel);

    jint out[4] = {(jint)c1.x, (jint)c1.y, (jint)c2.x, (jint)c2.y};
    jintArray arr = env->NewIntArray(4);
    if (!arr) return nullptr;
    env->SetIntArrayRegion(arr, 0, 4, out);
    return arr;
}

// Read the active selection's endpoints as ordered viewport coordinates.
// Returns {startCol, startRow, endCol, endRow, visFlags}; visFlags bit0/bit1
// say whether the start/end endpoint is inside the viewport (rows are clamped
// to the viewport when not). Returns nullptr when there is no active selection.
JNIEXPORT jintArray JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeGetSelectionViewport(
    JNIEnv* env, jclass clazz, jlong sessionPtr)
{
    (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session || session->is_closed.load() || !session->terminal) return nullptr;

    std::lock_guard<std::mutex> lock(session->session_mutex);

    GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
    if (ghostty_terminal_get(session->terminal, GHOSTTY_TERMINAL_DATA_SELECTION, &sel) != GHOSTTY_SUCCESS) {
        return nullptr;
    }

    GhosttySelection ordered = GHOSTTY_INIT_SIZED(GhosttySelection);
    if (ghostty_terminal_selection_ordered(session->terminal, &sel,
                                           GHOSTTY_SELECTION_ORDER_FORWARD, &ordered) == GHOSTTY_SUCCESS) {
        sel = ordered;
    }

    GhosttyTerminalScrollbar bar = {};
    ghostty_terminal_get(session->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &bar);

    const GhosttyGridRef* refs[2] = {&sel.start, &sel.end};
    jint out[5] = {0, 0, 0, 0, 0};
    for (int k = 0; k < 2; k++) {
        GhosttyPointCoordinate c = {};
        if (ghostty_terminal_point_from_grid_ref(session->terminal, refs[k],
                                                 GHOSTTY_POINT_TAG_SCREEN, &c) != GHOSTTY_SUCCESS) {
            return nullptr;
        }
        int64_t vp_y = (int64_t)c.y - (int64_t)bar.offset;
        int rows = session->rows > 0 ? session->rows : 1;
        bool visible = vp_y >= 0 && vp_y < rows;
        out[k * 2] = (jint)c.x;
        out[k * 2 + 1] = (jint)(visible ? vp_y : (vp_y < 0 ? 0 : rows - 1));
        if (visible) out[4] |= (1 << k);
    }

    jintArray arr = env->NewIntArray(5);
    if (!arr) return nullptr;
    env->SetIntArrayRegion(arr, 0, 5, out);
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeHasSelection(
    JNIEnv* env, jclass clazz, jlong sessionPtr)
{
    (void)env; (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session || session->is_closed.load() || !session->terminal) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(session->session_mutex);
    GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
    GhosttyResult res = ghostty_terminal_get(session->terminal, GHOSTTY_TERMINAL_DATA_SELECTION, &sel);
    return res == GHOSTTY_SUCCESS ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeGetSelectionText(
    JNIEnv* env, jclass clazz, jlong sessionPtr)
{
    (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session || session->is_closed.load() || !session->terminal) {
        return env->NewStringUTF("");
    }
    std::lock_guard<std::mutex> lock(session->session_mutex);
    GhosttyTerminalSelectionFormatOptions opts = GHOSTTY_INIT_SIZED(GhosttyTerminalSelectionFormatOptions);
    opts.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
    opts.unwrap = true;
    opts.trim = true;
    opts.selection = nullptr;
    uint8_t* out_ptr = nullptr;
    size_t out_len = 0;
    GhosttyResult res = ghostty_terminal_selection_format_alloc(session->terminal, nullptr, opts, &out_ptr, &out_len);
    if (res != GHOSTTY_SUCCESS || !out_ptr) {
        return env->NewStringUTF("");
    }
    // Ensure null-terminated for NewStringUTF: construct via byte array to handle utf8
    std::string text(reinterpret_cast<char*>(out_ptr), out_len);
    ghostty_free(nullptr, out_ptr, out_len);
    // Use NewStringUTF expects modified UTF-8; but we have raw utf8. For simplicity use byte array -> string
    // Create jstring via NewStringUTF; text should be valid utf8 ascii
    jstring jstr = env->NewStringUTF(text.c_str());
    if (!jstr) {
        // Fallback via bytes
        jbyteArray arr = env->NewByteArray(out_len);
        env->SetByteArrayRegion(arr, 0, out_len, reinterpret_cast<jbyte*>(text.data()));
        // Convert via String constructor UTF-8
        jclass strCls = env->FindClass("java/lang/String");
        jmethodID ctor = env->GetMethodID(strCls, "<init>", "([BLjava/lang/String;)V");
        jstring charset = env->NewStringUTF("UTF-8");
        jstr = (jstring)env->NewObject(strCls, ctor, arr, charset);
        env->DeleteLocalRef(arr);
        env->DeleteLocalRef(charset);
        env->DeleteLocalRef(strCls);
    }
    return jstr;
}

JNIEXPORT jstring JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeGetHyperlinkUri(
    JNIEnv* env, jclass clazz, jlong sessionPtr, jint col, jint row)
{
    (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session || session->is_closed.load() || !session->terminal) return nullptr;

    std::lock_guard<std::mutex> lock(session->session_mutex);

    GhosttyPoint p = {};
    p.tag = GHOSTTY_POINT_TAG_VIEWPORT;
    p.value.coordinate.x = static_cast<uint16_t>(col < 0 ? 0 : col);
    p.value.coordinate.y = static_cast<uint32_t>(row < 0 ? 0 : row);

    GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
    GhosttyResult res = ghostty_terminal_grid_ref(session->terminal, p, &ref);
    if (res != GHOSTTY_SUCCESS) return nullptr;

    size_t out_len = 0;
    res = ghostty_grid_ref_hyperlink_uri(&ref, nullptr, 0, &out_len);
    if (res == GHOSTTY_SUCCESS && out_len == 0) {
        return nullptr; // no hyperlink at this cell
    }
    if (res != GHOSTTY_OUT_OF_SPACE || out_len == 0) {
        return nullptr;
    }

    // out_len is required bytes; allocate and fetch
    std::vector<uint8_t> buf(out_len + 1);
    size_t actual_len = 0;
    res = ghostty_grid_ref_hyperlink_uri(&ref, buf.data(), out_len, &actual_len);
    if (res != GHOSTTY_SUCCESS || actual_len == 0) return nullptr;

    // Ensure valid UTF-8 string; copy to std::string
    std::string url(reinterpret_cast<char*>(buf.data()), actual_len);
    // Basic sanity: must look like a URI
    if (url.empty()) return nullptr;
    return env->NewStringUTF(url.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeIsSyncOutputActive(
    JNIEnv* env, jclass clazz, jlong sessionPtr)
{
    (void)env; (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session || session->is_closed.load() || !session->terminal) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(session->session_mutex);
    GhosttyTerminalModeConfig cfg = {};
    cfg.mode = GHOSTTY_MODE_SYNC_OUTPUT;
    GhosttyResult res = ghostty_terminal_get(session->terminal, GHOSTTY_TERMINAL_DATA_MODE, &cfg);
    if (res != GHOSTTY_SUCCESS) return JNI_FALSE;
    return cfg.value ? JNI_TRUE : JNI_FALSE;
}

/**
 * Terminal interaction-state bitfield for input routing:
 *   bit0 = alternate screen active, bit1 = mouse tracking enabled.
 * The Java side uses this to decide whether touch scrolling should move the
 * scrollback viewport, send arrow keys (fullscreen TUIs), or wheel events
 * (mouse-reporting apps).
 */
JNIEXPORT jint JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeGetScreenState(
    JNIEnv* env, jclass clazz, jlong sessionPtr)
{
    (void)env; (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session || session->is_closed.load() || !session->terminal) return 0;
    std::lock_guard<std::mutex> lock(session->session_mutex);

    jint state = 0;
    GhosttyTerminalScreen screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
    if (ghostty_terminal_get(session->terminal, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &screen) == GHOSTTY_SUCCESS
            && screen == GHOSTTY_TERMINAL_SCREEN_ALTERNATE) {
        state |= 1;
    }
    bool mouse = false;
    if (ghostty_terminal_get(session->terminal, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, &mouse) == GHOSTTY_SUCCESS
            && mouse) {
        state |= 2;
    }
    return state;
}

static std::vector<uint64_t> collectPromptRows(GhosttyTerminal term) {
    uint64_t total = 0;
    if (ghostty_terminal_get(term, GHOSTTY_TERMINAL_DATA_TOTAL_ROWS, &total) != GHOSTTY_SUCCESS || total == 0) {
        return {};
    }
    // If in alt screen, no scrollback prompts are meaningful
    GhosttyTerminalScreen screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
    ghostty_terminal_get(term, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &screen);
    if (screen == GHOSTTY_TERMINAL_SCREEN_ALTERNATE) return {};

    std::vector<uint64_t> prompts;
    prompts.reserve(64);
    for (uint64_t y = 0; y < total; ++y) {
        GhosttyPoint p = {};
        p.tag = GHOSTTY_POINT_TAG_SCREEN;
        p.value.coordinate.x = 0;
        p.value.coordinate.y = (uint32_t)y;
        GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
        if (ghostty_terminal_grid_ref(term, p, &ref) != GHOSTTY_SUCCESS) continue;
        GhosttyRow row;
        if (ghostty_grid_ref_row(&ref, &row) != GHOSTTY_SUCCESS) continue;
        GhosttyRowSemanticPrompt sem = GHOSTTY_ROW_SEMANTIC_NONE;
        if (ghostty_row_get(row, GHOSTTY_ROW_DATA_SEMANTIC_PROMPT, &sem) != GHOSTTY_SUCCESS) continue;
        if (sem == GHOSTTY_ROW_SEMANTIC_PROMPT) prompts.push_back(y);
    }
    LOGI("collectPromptRows total=%llu prompts=%zu", (unsigned long long)total, prompts.size());
    for (size_t i=0;i<prompts.size() && i<10; ++i) LOGI(" prompt[%zu]=%llu", i, (unsigned long long)prompts[i]);
    return prompts;
}

JNIEXPORT jboolean JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeScrollToPreviousPrompt(
    JNIEnv* env, jclass clazz, jlong sessionPtr)
{
    (void)env; (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session || session->is_closed.load() || !session->terminal) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(session->session_mutex);
    auto prompts = collectPromptRows(session->terminal);
    if (prompts.empty()) return JNI_FALSE;
    GhosttyTerminalScrollbar sb = {};
    if (ghostty_terminal_get(session->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &sb) != GHOSTTY_SUCCESS) return JNI_FALSE;
    uint64_t curTop = sb.offset;
    LOGI("scrollPrev curTop=%llu total=%llu len=%llu prompts=%zu", (unsigned long long)curTop, (unsigned long long)sb.total, (unsigned long long)sb.len, prompts.size());
    uint64_t target = 0;
    bool found = false;
    for (int i = (int)prompts.size() - 1; i >= 0; --i) {
        if (prompts[i] < curTop) { target = prompts[i]; found = true; break; }
    }
    if (!found) {
        // At bottom: jump to previous prompt before last
        if (prompts.size() >= 2) { target = prompts[prompts.size() - 2]; found = true; }
        else if (curTop > 0) { target = prompts[0]; found = true; }
    }
    if (!found) return JNI_FALSE;
    LOGI("scrollPrev target=%llu", (unsigned long long)target);
    GhosttyTerminalScrollViewport vp = {};
    vp.tag = GHOSTTY_SCROLL_VIEWPORT_ROW;
    vp.value.row = (size_t)target;
    ghostty_terminal_scroll_viewport(session->terminal, vp);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeScrollToNextPrompt(
    JNIEnv* env, jclass clazz, jlong sessionPtr)
{
    (void)env; (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session || session->is_closed.load() || !session->terminal) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(session->session_mutex);
    auto prompts = collectPromptRows(session->terminal);
    if (prompts.empty()) return JNI_FALSE;
    GhosttyTerminalScrollbar sb = {};
    if (ghostty_terminal_get(session->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &sb) != GHOSTTY_SUCCESS) return JNI_FALSE;
    uint64_t curTop = sb.offset;
    LOGI("scrollNext curTop=%llu total=%llu len=%llu prompts=%zu", (unsigned long long)curTop, (unsigned long long)sb.total, (unsigned long long)sb.len, prompts.size());
    uint64_t target = 0;
    bool found = false;
    for (size_t i = 0; i < prompts.size(); ++i) {
        if (prompts[i] > curTop) { target = prompts[i]; found = true; break; }
    }
    if (!found) return JNI_FALSE;
    LOGI("scrollNext target=%llu", (unsigned long long)target);
    GhosttyTerminalScrollViewport vp = {};
    vp.tag = GHOSTTY_SCROLL_VIEWPORT_ROW;
    vp.value.row = (size_t)target;
    ghostty_terminal_scroll_viewport(session->terminal, vp);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeGetLastCommandOutput(
    JNIEnv* env, jclass clazz, jlong sessionPtr)
{
    (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session || session->is_closed.load() || !session->terminal) return env->NewStringUTF("");
    std::lock_guard<std::mutex> lock(session->session_mutex);
    auto prompts = collectPromptRows(session->terminal);
    if (prompts.size() < 2) return env->NewStringUTF("");
    uint64_t startPrompt = prompts[prompts.size() - 2];
    uint64_t endPrompt = prompts[prompts.size() - 1];
    // Expand start prompt block to include continuation rows
    uint64_t total = 0;
    ghostty_terminal_get(session->terminal, GHOSTTY_TERMINAL_DATA_TOTAL_ROWS, &total);
    // Find end of start prompt block (include CONTINUATION)
    uint64_t promptEnd = startPrompt;
    for (uint64_t y = startPrompt + 1; y < total && y < endPrompt; ++y) {
        GhosttyPoint p = {};
        p.tag = GHOSTTY_POINT_TAG_SCREEN;
        p.value.coordinate.y = (uint32_t)y;
        GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
        if (ghostty_terminal_grid_ref(session->terminal, p, &ref) != GHOSTTY_SUCCESS) break;
        GhosttyRow row;
        if (ghostty_grid_ref_row(&ref, &row) != GHOSTTY_SUCCESS) break;
        GhosttyRowSemanticPrompt sem = GHOSTTY_ROW_SEMANTIC_NONE;
        ghostty_row_get(row, GHOSTTY_ROW_DATA_SEMANTIC_PROMPT, &sem);
        if (sem == GHOSTTY_ROW_SEMANTIC_PROMPT_CONTINUATION) promptEnd = y;
        else break;
    }
    uint64_t outStart = promptEnd + 1;
    uint64_t outEnd = endPrompt > 0 ? endPrompt - 1 : 0;
    if (outStart > outEnd || outStart >= total) return env->NewStringUTF("");

    uint16_t cols = session->cols;
    std::string result;
    result.reserve(4096);
    for (uint64_t y = outStart; y <= outEnd && y < total; ++y) {
        GhosttyPoint pRow = {};
        pRow.tag = GHOSTTY_POINT_TAG_SCREEN;
        pRow.value.coordinate.y = (uint32_t)y;
        GhosttyGridRef refRow = GHOSTTY_INIT_SIZED(GhosttyGridRef);
        if (ghostty_terminal_grid_ref(session->terminal, pRow, &refRow) != GHOSTTY_SUCCESS) continue;
        GhosttyRow row;
        if (ghostty_grid_ref_row(&refRow, &row) != GHOSTTY_SUCCESS) continue;
        // Skip prompt rows (should not happen in range, but be safe)
        GhosttyRowSemanticPrompt rowSem = GHOSTTY_ROW_SEMANTIC_NONE;
        ghostty_row_get(row, GHOSTTY_ROW_DATA_SEMANTIC_PROMPT, &rowSem);
        if (rowSem != GHOSTTY_ROW_SEMANTIC_NONE) continue;

        std::string line;
        line.reserve(cols * 2);
        for (uint16_t x = 0; x < cols; ++x) {
            GhosttyPoint p = {};
            p.tag = GHOSTTY_POINT_TAG_SCREEN;
            p.value.coordinate.x = x;
            p.value.coordinate.y = (uint32_t)y;
            GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
            if (ghostty_terminal_grid_ref(session->terminal, p, &ref) != GHOSTTY_SUCCESS) continue;
            GhosttyCell cell;
            if (ghostty_grid_ref_cell(&ref, &cell) != GHOSTTY_SUCCESS) continue;
            GhosttyCellSemanticContent sem = GHOSTTY_CELL_SEMANTIC_OUTPUT;
            ghostty_cell_get(cell, GHOSTTY_CELL_DATA_SEMANTIC_CONTENT, &sem);
            // Only collect OUTPUT cells for pure output copy
            if (sem != GHOSTTY_CELL_SEMANTIC_OUTPUT) {
                // If line has no output cells at all, we will skip entire line later
                // For now, treat non-output as space to keep alignment, but we need to know if line is purely output
                // To avoid gaps, we collect all cells but will trim: simplest is to collect the whole line's graphemes and later the caller can trim.
                // For now, collect cell's text regardless, but ideally only output.
                // Let's collect only if the row is considered output row (has any OUTPUT). Check first: we need to know if row has output.
                // Instead, we collect all graphemes for rows that have at least one OUTPUT cell.
                continue;
            }
            size_t len = 0;
            ghostty_grid_ref_graphemes(&ref, nullptr, 0, &len);
            if (len == 0) {
                line.push_back(' ');
                continue;
            }
            uint32_t buf[4] = {0};
            size_t outLen = 0;
            if (ghostty_grid_ref_graphemes(&ref, buf, 4, &outLen) == GHOSTTY_SUCCESS && outLen > 0) {
                // Encode buf[0] as UTF-8 (simple for ASCII)
                uint32_t cp = buf[0];
                if (cp < 0x80) line.push_back((char)cp);
                else if (cp < 0x800) { line.push_back((char)(0xC0 | (cp >> 6))); line.push_back((char)(0x80 | (cp & 0x3F))); }
                else if (cp < 0x10000) { line.push_back((char)(0xE0 | (cp >> 12))); line.push_back((char)(0x80 | ((cp >> 6) & 0x3F))); line.push_back((char)(0x80 | (cp & 0x3F))); }
                else { line.push_back('?'); }
                // Append combining marks as separate? For now ignore extra len>1
                for (size_t k = 1; k < outLen; ++k) {
                    uint32_t c2 = buf[k];
                    if (c2 < 0x80) line.push_back((char)c2);
                }
            } else {
                line.push_back(' ');
            }
        }
        // If line had no OUTPUT cells, it would be all spaces - skip? But we already filtered per cell, so line may be empty.
        // Trim trailing spaces
        size_t end = line.find_last_not_of(' ');
        if (end != std::string::npos) line = line.substr(0, end + 1);
        else line.clear();
        if (!line.empty()) {
            if (!result.empty()) {
                // Check if previous row was wrapped: if row is wrap continuation, don't add newline
                bool isWrapContinuation = false;
                GhosttyRow prevRowCheck;
                // We already have row, check WRAP_CONTINUATION
                bool isCont = false;
                ghostty_row_get(row, GHOSTTY_ROW_DATA_WRAP_CONTINUATION, &isCont);
                if (!isCont) result.push_back('\n');
            }
            result += line;
        }
    }
    // Fallback: if we collected nothing (maybe semantic not set for output), try formatting the range as plain text via selection
    if (result.empty() && outStart <= outEnd) {
        GhosttyPoint pStart = {};
        pStart.tag = GHOSTTY_POINT_TAG_SCREEN;
        pStart.value.coordinate.x = 0;
        pStart.value.coordinate.y = (uint32_t)outStart;
        GhosttyPoint pEnd = {};
        pEnd.tag = GHOSTTY_POINT_TAG_SCREEN;
        pEnd.value.coordinate.x = (uint16_t)(cols > 0 ? cols - 1 : 0);
        pEnd.value.coordinate.y = (uint32_t)outEnd;
        GhosttyGridRef sRef = GHOSTTY_INIT_SIZED(GhosttyGridRef);
        GhosttyGridRef eRef = GHOSTTY_INIT_SIZED(GhosttyGridRef);
        if (ghostty_terminal_grid_ref(session->terminal, pStart, &sRef) == GHOSTTY_SUCCESS &&
            ghostty_terminal_grid_ref(session->terminal, pEnd, &eRef) == GHOSTTY_SUCCESS) {
            GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
            sel.start = sRef;
            sel.end = eRef;
            sel.rectangle = false;
            ghostty_terminal_set(session->terminal, GHOSTTY_TERMINAL_OPT_SELECTION, &sel);
            GhosttyTerminalSelectionFormatOptions opts = GHOSTTY_INIT_SIZED(GhosttyTerminalSelectionFormatOptions);
            opts.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
            opts.unwrap = true;
            opts.trim = true;
            uint8_t* outPtr = nullptr;
            size_t outLen = 0;
            if (ghostty_terminal_selection_format_alloc(session->terminal, nullptr, opts, &outPtr, &outLen) == GHOSTTY_SUCCESS && outPtr) {
                result.assign((char*)outPtr, outLen);
                ghostty_free(nullptr, outPtr, outLen);
            }
            ghostty_terminal_set(session->terminal, GHOSTTY_TERMINAL_OPT_SELECTION, nullptr);
        }
    }
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jintArray JNICALL
Java_com_tinyhack_ssh_terminal_NativeBridge_nativeGetPromptRows(
    JNIEnv* env, jclass clazz, jlong sessionPtr)
{
    (void)clazz;
    auto* session = reinterpret_cast<NativeSession*>(sessionPtr);
    if (!session || session->is_closed.load() || !session->terminal) return env->NewIntArray(0);
    std::lock_guard<std::mutex> lock(session->session_mutex);
    auto prompts = collectPromptRows(session->terminal);
    jintArray arr = env->NewIntArray(prompts.size());
    if (arr && !prompts.empty()) {
        std::vector<jint> jprompts;
        jprompts.reserve(prompts.size());
        for (auto v : prompts) jprompts.push_back((jint)v);
        env->SetIntArrayRegion(arr, 0, prompts.size(), jprompts.data());
    }
    return arr;
}

} // extern "C"
