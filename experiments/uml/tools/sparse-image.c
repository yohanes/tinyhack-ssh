#define _FILE_OFFSET_BITS 64
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

/* Stream a raw template to a new sparse image. Zero blocks become holes;
 * ftruncate preserves trailing holes and extends to the requested capacity. */
int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "Usage: uml-sparse-image OUTPUT SIZE_BYTES\n");
        return 2;
    }
    char *end;
    errno = 0;
    long long requested = strtoll(argv[2], &end, 10);
    if (errno || !*argv[2] || *end || requested <= 0 || (off_t)requested != requested) {
        fprintf(stderr, "Invalid disk size\n");
        return 2;
    }
    int fd = open(argv[1], O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0600);
    if (fd < 0) { perror("create image"); return 1; }
    unsigned char block[65536];
    off_t total = 0;
    for (;;) {
        size_t used = 0;
        while (used < sizeof(block)) {
            ssize_t n = read(STDIN_FILENO, block + used, sizeof(block) - used);
            if (n < 0) { if (errno == EINTR) continue; goto failed; }
            if (!n) break;
            used += (size_t)n;
        }
        if (!used) break;
        if (used > (uint64_t)(requested - total)) {
            fprintf(stderr, "Template exceeds requested disk size\n");
            close(fd);
            return 1;
        }
        total += (off_t)used;
        size_t i = 0;
        while (i < used && block[i] == 0) ++i;
        if (i == used) {
            if (lseek(fd, (off_t)used, SEEK_CUR) < 0) goto failed;
        } else {
            size_t written = 0;
            while (written < used) {
                ssize_t n = write(fd, block + written, used - written);
                if (n < 0 && errno == EINTR) continue;
                if (n <= 0) goto failed;
                written += (size_t)n;
            }
        }
        if (used < sizeof(block)) break;
    }
    if (!total) { fprintf(stderr, "Empty template\n"); close(fd); return 1; }
    if (ftruncate(fd, (off_t)requested) || fsync(fd)) goto failed;
    if (close(fd)) { perror("close image"); return 1; }
    return 0;
failed:
    perror("sparse image");
    close(fd);
    return 1;
}
