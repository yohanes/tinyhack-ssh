# Device networking validation — 2026-11-01

Final artifacts match `SHA256SUMS`. Tests ran through the terminal UI in the
separate `com.tinyhack.ssh.uml` app on the connected Moto G45, with the app's
untrusted_app SELinux context (see `android-host.txt`).

- `vec0`: static IPv4 10.0.2.15/24, gateway 10.0.2.2, DNS 1.1.1.1.
- `nslookup example.com`: resolved public IPv4 addresses using 1.1.1.1.
- `ping -c 3 1.1.1.1`: 3 replies, 0% loss.
- `wget http://connectivitycheck.gstatic.com/generate_204`: completed.
- Downloaded a 16,777,216-byte random file from a temporary HTTP server at
  192.168.1.224:18080. Guest and host SHA-256 both:
  `05f36823b20eff7b0fcfb19449de96c1034fc3d378c1a03aa6774853ad39b798`.
- Remounted root read-only and powered off. Host process inspection found no
  kernel, UML stub, passt, or adapter remaining. Temporary host server stopped.
- No kernel panic, BUG, WARNING, exhausted-budget diagnostic, or seccomp-install
  failure in the final network log.
- APK build, experimental debug lint, shell syntax checks, checksums, and
  `git diff --check` passed.

The raw console transcript is `network-console.txt`; some command echoes
interleave with output because ADB typed the following command while the
previous command was finishing. The executed commands and results are retained
without editing. `network.png` is a screenshot of the terminal.

The unavailable Android global HTTP proxy (192.168.1.26:8877) was disabled with
explicit user approval. Final `http_proxy` is `:0`, and the global proxy host
and port settings are absent. Native curl returned HTTP 200 for HTTPS
example.com and HTTP 204 for the connectivity-check URL. Plain HTTP
example.com reset even in native curl, so it was not used as the passing guest
test. Native HTTPS does not prove guest HTTPS; the guest's wget has no TLS.

Remaining experimental limits: ptrace-mode performance, file-backed guest RAM,
console resize diagnostic, fixed IPv4/DNS configuration, no IPv6 or inbound
forwarding tests, and no long-duration or network-switching tests.
