/*
 * mosh-launcher.cc — native replacement for mosh.pl for Android.
 *
 * Android (targetSdk 29+) SELinux policy forbids execve() of scripts in the
 * app data directory, so the upstream perl wrapper cannot be used. This
 * launcher replicates its core flow:
 *
 *   1. ssh -n [user@]host -- mosh-server new -c [-l LOCALE] [-p UDP_PORTS]
 *      && sleep HOLD   (when a local ssh-agent is present: keeping the ssh
 *      session alive preserves the remote agent-forwarding socket, which
 *      sshd would otherwise delete the moment the bootstrap ssh exits)
 *   2. parse "MOSH CONNECT <port> <key>", "MOSH IP <addr>" and
 *      "MOSH SSH_CONNECTION <srcip> <srcport> <dstip> <dstport>" from stdout
 *   3. fork + exec mosh-client with MOSH_KEY; wait for it, then terminate
 *      the held ssh connection
 *
 * ssh prompts (passwords, host key confirmation) still reach the user because
 * ssh writes them to /dev/tty while only its stdout is piped to us.
 *
 * Build: aarch64-linux-android34-clang++ -O2 -fPIE -pie -static-libstdc++
 * Installed as libmosh.so (symlinked to files/usr/bin/mosh); mosh-client is
 * resolved as the sibling libmosh-client.so in nativeLibraryDir.
 */
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cerrno>
#include <csignal>
#include <string>
#include <vector>
#include <unistd.h>
#include <sys/wait.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netdb.h>
#include <arpa/inet.h>

namespace {

const char* VERSION = "mosh 1.4.0 (tinyhack-ssh launcher)";

/* ssh child that must be torn down on error/exit (agent-forwarding hold) */
pid_t g_ssh_pid = -1;

[[noreturn]] void die(const std::string& msg) {
    if (g_ssh_pid > 0) {
        kill(g_ssh_pid, SIGKILL);
        waitpid(g_ssh_pid, nullptr, 0);
        g_ssh_pid = -1;
    }
    fprintf(stderr, "mosh: %s\n", msg.c_str());
    exit(1);
}

std::vector<std::string> splitSpaces(const std::string& s) {
    std::vector<std::string> out;
    size_t i = 0;
    while (i < s.size()) {
        while (i < s.size() && s[i] == ' ') i++;
        size_t start = i;
        while (i < s.size() && s[i] != ' ') i++;
        if (i > start) out.push_back(s.substr(start, i - start));
    }
    return out;
}

std::string defaultLocale() {
    const char* candidates[] = {"LC_ALL", "LC_CTYPE", "LANG"};
    for (const char* name : candidates) {
        const char* v = getenv(name);
        if (v && *v) {
            std::string s = v;
            /* mosh-server needs a UTF-8 locale on the remote side */
            for (char& c : s) {
                if (c >= 'A' && c <= 'Z') c += 32;
            }
            if (s.find("utf-8") != std::string::npos ||
                s.find("utf8") != std::string::npos) {
                return v;
            }
        }
    }
    return "en_US.UTF-8";
}

std::string selfDir() {
    char buf[4096];
    ssize_t n = readlink("/proc/self/exe", buf, sizeof(buf) - 1);
    if (n <= 0) return ".";
    buf[n] = '\0';
    std::string path = buf;
    size_t slash = path.rfind('/');
    return slash == std::string::npos ? "." : path.substr(0, slash);
}

/*
 * mosh-client resolves its target with AI_NUMERICHOST (network.cc), i.e. it
 * only accepts numeric addresses. Upstream mosh.pl resolves the hostname in
 * the wrapper and hands over a numeric string — we do the same here.
 */
std::string resolveNumeric(const std::string& host) {
    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_DGRAM;
    struct addrinfo* res = nullptr;
    int rc = getaddrinfo(host.c_str(), nullptr, &hints, &res);
    if (rc != 0 || res == nullptr) {
        die(std::string("could not find address for ") + host +
            (rc == 0 ? "" : std::string(": ") + gai_strerror(rc)));
    }
    char buf[INET6_ADDRSTRLEN];
    const void* src = nullptr;
    if (res->ai_family == AF_INET) {
        src = &reinterpret_cast<sockaddr_in*>(res->ai_addr)->sin_addr;
    } else {
        src = &reinterpret_cast<sockaddr_in6*>(res->ai_addr)->sin6_addr;
    }
    const char* s = inet_ntop(res->ai_family, src, buf, sizeof(buf));
    freeaddrinfo(res);
    if (s == nullptr) die("could not use address for " + host);
    return s;
}

void printUsage() {
    fprintf(stderr,
        "Usage: mosh [options] [--] [user@]host\n"
        "Options:\n"
        "  --port=PORT[:PORT2]  UDP port (range) for mosh-server\n"
        "  --ssh-port=PORT      TCP port of the ssh connection\n"
        "  --ssh=COMMAND        ssh command and arguments (e.g. 'ssh -i key')\n"
        "  --server=PATH        remote mosh-server command\n"
        "  --predict=WHEN       when to predict locally (always/never/adaptive)\n"
        "  --no-init            skip terminal initialization (MOSH_NO_TERM_INIT=1)\n"
        "  --version            print version\n");
}

/* Ask mosh-client how many colors the terminal has (like mosh.pl does). */
int queryColors(const std::string& client) {
    int fds[2];
    if (pipe(fds) != 0) return 256;
    pid_t pid = fork();
    if (pid < 0) { close(fds[0]); close(fds[1]); return 256; }
    if (pid == 0) {
        close(fds[0]);
        dup2(fds[1], STDOUT_FILENO);
        close(fds[1]);
        /* mosh-client -c must not touch the terminal */
        freopen("/dev/null", "w", stderr);
        setenv("MOSH_NO_TERM_INIT", "1", 1);
        char* argv[] = { const_cast<char*>("mosh-client"), const_cast<char*>("-c"), nullptr };
        if (client == "mosh-client") execvp(argv[0], argv);
        else execv(client.c_str(), argv);
        _exit(127);
    }
    close(fds[1]);
    char buf[32];
    ssize_t n = read(fds[0], buf, sizeof(buf) - 1);
    close(fds[0]);
    int status = 0;
    waitpid(pid, &status, 0);
    if (n <= 0) return 256;
    buf[n] = '\0';
    int colors = atoi(buf);
    return colors > 0 ? colors : 256;
}

/* Locale NAME=VALUE assignments to export to mosh-server (-l). */
std::vector<std::string> localeVars() {
    static const char* names[] = {"LANG", "LANGUAGE", "LC_CTYPE", "LC_ALL"};
    std::vector<std::string> out;
    for (const char* name : names) {
        const char* v = getenv(name);
        if (v && *v) out.push_back(std::string(name) + "=" + v);
    }
    if (out.empty()) out.push_back("LANG=" + defaultLocale());
    return out;
}

} // namespace

int main(int argc, char** argv) {
    std::string userhost;
    std::string udpPorts;
    std::string sshPort;
    std::string sshCommand = "ssh";
    std::string serverCommand = "mosh-server";
    std::string predict;
    bool noInit = false;

    for (int i = 1; i < argc; i++) {
        std::string arg = argv[i];
        if (arg == "--") {
            if (i + 1 < argc && userhost.empty()) userhost = argv[++i];
            continue;
        }
        if (arg == "--version") {
            printf("%s\n", VERSION);
            return 0;
        }
        if (arg == "--help" || arg == "-h") {
            printUsage();
            return 0;
        }
        if (arg.rfind("--port=", 0) == 0) { udpPorts = arg.substr(7); continue; }
        if (arg.rfind("--ssh-port=", 0) == 0) { sshPort = arg.substr(11); continue; }
        if (arg.rfind("--ssh=", 0) == 0) { sshCommand = arg.substr(6); continue; }
        if (arg.rfind("--server=", 0) == 0) { serverCommand = arg.substr(9); continue; }
        if (arg.rfind("--predict=", 0) == 0) { predict = arg.substr(10); continue; }
        if (arg == "--no-init") { noInit = true; continue; }
        if (!arg.empty() && arg[0] == '-' && arg != "-") {
            fprintf(stderr, "mosh: unknown option: %s\n", arg.c_str());
            printUsage();
            return 1;
        }
        if (!userhost.empty()) die("more than one host given");
        userhost = arg;
    }

    if (userhost.empty()) {
        printUsage();
        return 1;
    }

    /* mosh-client location: sibling libmosh-client.so, else PATH lookup */
    std::string client = selfDir() + "/libmosh-client.so";
    if (access(client.c_str(), X_OK) != 0) client = "mosh-client";

    int colors = queryColors(client);

    /* Build the ssh command */
    std::vector<std::string> sshArgv = splitSpaces(sshCommand);
    if (sshArgv.empty()) sshArgv.push_back("ssh");
    sshArgv.push_back("-n");
    if (!sshPort.empty()) {
        sshArgv.push_back("-p");
        sshArgv.push_back(sshPort);
    }
    /*
     * Agent-forwarding hold: sshd removes the forwarded agent socket when
     * the bootstrap ssh session ends, which happens seconds after
     * mosh-server daemonizes — leaving SSH_AUTH_SOCK dangling for the whole
     * mosh session (upstream mosh has the same flaw). When a local agent
     * exists, append a remote hold so this ssh stays open for the lifetime
     * of the session; we terminate it when mosh-client exits. Keepalives
     * let a dead link tear the hold down on its own (~4 min).
     */
    bool agentHold = getenv("SSH_AUTH_SOCK") != nullptr;
    if (agentHold) {
        /*
         * -tt: run the hold on a remote pty so sshd SIGHUPs it when the
         * connection drops (non-pty commands survive disconnect and would
         * leak `sleep` processes on the server).
         */
        sshArgv.push_back("-tt");
        sshArgv.push_back("-o");
        sshArgv.push_back("ServerAliveInterval=30");
        sshArgv.push_back("-o");
        sshArgv.push_back("ServerAliveCountMax=8");
    }
    sshArgv.push_back(userhost);
    sshArgv.push_back("--");
    sshArgv.push_back(serverCommand);
    sshArgv.push_back("new");
    sshArgv.push_back("-c");
    sshArgv.push_back(std::to_string(colors));
    if (!udpPorts.empty()) {
        sshArgv.push_back("-p");
        sshArgv.push_back(udpPorts);
    }
    for (const std::string& lv : localeVars()) {
        sshArgv.push_back("-l");
        sshArgv.push_back(lv);
    }
    if (agentHold) {
        /* && : if mosh-server failed, don't linger for the hold period */
        sshArgv.push_back("&&");
        sshArgv.push_back("sleep");
        sshArgv.push_back("86400");
    }

    std::vector<char*> sshExec;
    for (const std::string& a : sshArgv) sshExec.push_back(const_cast<char*>(a.c_str()));
    sshExec.push_back(nullptr);

    int fds[2];
    if (pipe(fds) != 0) die(std::string("pipe: ") + strerror(errno));

    pid_t pid = fork();
    if (pid < 0) die(std::string("fork: ") + strerror(errno));

    if (pid == 0) {
        /* child: stdout -> pipe; stdin/stderr stay on the tty */
        close(fds[0]);
        if (dup2(fds[1], STDOUT_FILENO) < 0) _exit(126);
        close(fds[1]);
        execvp(sshExec[0], sshExec.data());
        fprintf(stderr, "mosh: cannot exec %s: %s\n", sshExec[0], strerror(errno));
        _exit(127);
    }
    g_ssh_pid = pid;

    close(fds[1]);
    FILE* out = fdopen(fds[0], "r");
    if (!out) die("fdopen failed");

    std::string ip, port, key;
    char* line = nullptr;
    size_t cap = 0;
    ssize_t len;
    while ((len = getline(&line, &cap, out)) > 0) {
        std::string s(line, (size_t)len);
        while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
        if (s.rfind("MOSH CONNECT ", 0) == 0) {
            std::string rest = s.substr(13);
            size_t sp = rest.find(' ');
            if (sp == std::string::npos) die("Bad MOSH CONNECT string: " + s);
            port = rest.substr(0, sp);
            key = rest.substr(sp + 1);
            while (!key.empty() && (key.back() == ' ')) key.pop_back();
            if (port.empty() || key.empty()) die("Bad MOSH CONNECT string: " + s);
            break;
        } else if (s.rfind("MOSH IP ", 0) == 0) {
            ip = s.substr(8);
        } else if (s.rfind("MOSH SSH_CONNECTION ", 0) == 0) {
            /* MOSH SSH_CONNECTION <srcip> <srcport> <dstip> <dstport> */
            std::vector<std::string> words = splitSpaces(s);
            if (words.size() == 6) ip = words[4]; /* dstip = server side address */
        } else {
            /* remote diagnostics (locale errors, etc.) */
            fprintf(stderr, "%s\n", s.c_str());
        }
    }
    free(line);

    if (port.empty() || key.empty()) {
        fclose(out);
        int status = 0;
        waitpid(pid, &status, 0);
        g_ssh_pid = -1;
        if (WIFEXITED(status) && WEXITSTATUS(status) != 0) {
            char buf[128];
            snprintf(buf, sizeof(buf), "ssh exited with status %d", WEXITSTATUS(status));
            die(buf);
        }
        die("Did not find mosh server startup message. (Have you installed mosh on your server?)");
    }
    if (ip.empty()) {
        size_t at = userhost.find('@');
        ip = at == std::string::npos ? userhost : userhost.substr(at + 1);
    }
    /* mosh-client requires a numeric address (AI_NUMERICHOST) */
    ip = resolveNumeric(ip);

    setenv("MOSH_KEY", key.c_str(), 1);
    if (!predict.empty()) setenv("MOSH_PREDICTION_DISPLAY", predict.c_str(), 1);
    if (noInit) setenv("MOSH_NO_TERM_INIT", "1", 1);

    std::string title = userhost + " | ";
    std::vector<char*> clientArgv;
    clientArgv.push_back(const_cast<char*>("mosh-client"));
    clientArgv.push_back(const_cast<char*>("-#"));
    clientArgv.push_back(const_cast<char*>(title.c_str()));
    clientArgv.push_back(const_cast<char*>(ip.c_str()));
    clientArgv.push_back(const_cast<char*>(port.c_str()));
    clientArgv.push_back(nullptr);

    /*
     * Fork for mosh-client so this launcher can reap it and then tear down
     * the agent-forwarding hold (the bootstrap ssh is still running its
     * remote `sleep`). Keeping `out` open in the parent means ssh never
     * sees EPIPE on stdout while the session is live.
     */
    pid_t mpid = fork();
    if (mpid < 0) die(std::string("fork: ") + strerror(errno));
    if (mpid == 0) {
        execvp(client.c_str(), clientArgv.data());
        fprintf(stderr, "mosh: cannot exec mosh-client: %s\n", strerror(errno));
        _exit(127);
    }
    int mstatus = 0;
    waitpid(mpid, &mstatus, 0);
    if (g_ssh_pid > 0) {
        kill(g_ssh_pid, SIGTERM);
        waitpid(g_ssh_pid, nullptr, 0);
        g_ssh_pid = -1;
    }
    fclose(out);
    if (WIFEXITED(mstatus)) return WEXITSTATUS(mstatus);
    if (WIFSIGNALED(mstatus)) return 128 + WTERMSIG(mstatus);
    return 1;
}
