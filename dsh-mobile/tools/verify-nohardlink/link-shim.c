/*
 * Reproduce, on a real Linux filesystem, the two behaviours that break
 * createIfAbsent in the Android port, so the fix can be verified without a
 * device:
 *
 *   symlink  - link() reports success but publishes a symbolic link to the
 *              staging file (the Android bridge's l2s shim). This is the silent
 *              data-loss case.
 *   eperm    - link() refuses hard links with EPERM (external storage/fat).
 *   unset    - the real libc link(), untouched.
 *
 * Build:  gcc -shared -fPIC -o link-shim.so link-shim.c
 * Use:    LINK_SHIM_MODE=symlink LD_PRELOAD=/path/link-shim.so node driver.mjs ...
 *
 * Only link() is intercepted; writeFileAtomic's other calls (open/rename/rm/
 * lstat) reach the kernel unchanged.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef int (*link_fn)(const char *, const char *);

static link_fn real_link(void) {
  static link_fn resolved;
  if (!resolved) resolved = (link_fn)dlsym(RTLD_NEXT, "link");
  return resolved;
}

int link(const char *oldpath, const char *newpath) {
  const char *mode = getenv("LINK_SHIM_MODE");
  if (mode && strcmp(mode, "eperm") == 0) {
    errno = EPERM;
    return -1;
  }
  if (mode && strcmp(mode, "symlink") == 0) {
    if (symlink(oldpath, newpath) == 0) return 0;
    /* Fall through when the filesystem cannot hold a symlink either (vfat), so
       the shim never invents an outcome the real bridge would not produce. */
  }
  return real_link()(oldpath, newpath);
}
