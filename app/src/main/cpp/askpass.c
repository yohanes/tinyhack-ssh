/*
 * libaskpass.so — SSH_ASKPASS helper for headless passphrase delivery.
 *
 * Android (targetSdk 29+) SELinux policy denies execve() of files in the
 * app data directory, so askpass *scripts* cannot work. This helper ships in
 * nativeLibraryDir (the only app-writable packaging location where exec is
 * allowed) and prints the passphrase from a 0600 file whose path is passed
 * via GHOSTTY_ASKPASS_FILE. The passphrase therefore never appears on any
 * argv and never passes through a shell.
 */
#include <stdio.h>
#include <stdlib.h>

int main(int argc, char **argv) {
    (void) argc;
    (void) argv; /* ssh passes the prompt text as argv[1]; ignored */

    const char *path = getenv("GHOSTTY_ASKPASS_FILE");
    if (path == NULL || path[0] == '\0') return 1;

    FILE *f = fopen(path, "r");
    if (f == NULL) return 1;

    int c;
    while ((c = fgetc(f)) != EOF) {
        if (fputc(c, stdout) == EOF) break;
        if (c == '\n') break; /* exactly one line: the passphrase */
    }
    fclose(f);
    return 0;
}
