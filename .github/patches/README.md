# rsync 3.3.0 compatibility backport

The validation workflow verifies the original 3.3.0 release tarball's pinned SHA-256 **before**
applying `rsync-3.3.0-popt-fortify.patch`. No other rsync version is patched. The binary remains
3.3.0 with this narrowly identified backport; it is not an unmodified release build.

The patch extracts the `poptDupArgv` change from rsync's upstream update to popt 1.19,
[commit 4c8683c8753f2493ef13c3425fb5847960a6e305](https://github.com/RsyncProject/rsync/commit/4c8683c8753f2493ef13c3425fb5847960a6e305)
([PR #592](https://github.com/RsyncProject/rsync/pull/592)). It retains the surrounding 3.3.0
annotations rather than backporting unrelated changes. The old implementation allocates space for
all strings but passes the whole allocation size to `strlcpy` even after advancing the destination
pointer; with glibc's fortified `strlcpy` and `_FORTIFY_SOURCE=3`, this aborts at argument parsing.
The upstream replacement copies each already-sized argument with `stpcpy` and advances past its NUL.
See the upstream [3.3.0 report and backtrace](https://github.com/RsyncProject/rsync/issues/589).

The workflow retains the four pinned releases, compiler defaults and the whole-suite assertions. It
also tests a simple local copy and the engine's `--from0` / `--files-from` invocation outside Java
before running the existing serial pinned suites and combined matrix. This backport is for CI coverage;
it is not a recommendation to deploy a historical rsync release, nor a fix for unrelated security
vulnerabilities in it.
