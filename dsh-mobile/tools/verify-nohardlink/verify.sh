#!/usr/bin/env bash
# Proof, on real filesystems, that the harness overlay's fs-local patch fixes the
# silent data loss reported in issue #13 — without needing an Android device.
#
# It drives the *npm-installed* package (the one the container actually loads)
# through its public cordis API, twice per condition: BEFORE the overlay payload
# is applied (upstream build) and AFTER it (our build). Three conditions:
#
#   A  link() reports success but publishes a symlink   — the Android bridge symptom
#   B  link() refuses with EPERM                        — external storage
#   C  a real filesystem with no hard links (vfat loop)  — the kernel's own refusal
#
# A before/after pair is the evidence: A-before is the reported bug (tool says
# success, the name dangles), A-after is the fix (explicit FS_NOT_REGULAR_FILE);
# B/C-after show the new degradation actually writes the file.
#
# Requirements: Linux (or WSL) as root, with gcc, node, losetup, mkfs.vfat and an
# npm tree that has `@deepseek-ai/dsh` installed. The guest needs no network.
#
# Prepare the tree anywhere npm can reach (the guest itself may be offline):
#   npm init -y && npm i --no-audit --no-fund --ignore-scripts @deepseek-ai/dsh@0.1.7-rc.2
# Capture the published baseline the "before" runs use (never from the tree, which
# a previous run leaves patched):
#   npm pack @deepseek-ai/dsh-fs-local@0.1.7-rc.2 && tar -xzf *.tgz
#   cp package/lib/index.js <WORK>/lib-index.upstream.js
# Then:
#   HARNESS_TREE=/root/fsvfy/harness-tree bash verify.sh
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
ASSET="$REPO/dsh-mobile/app/src/main/assets/harness-overlay/deepseek-ai-dsh-fs-local"
MANIFEST="$REPO/dsh-mobile/app/src/main/assets/harness-overlay/manifest.tsv"
TREE=${HARNESS_TREE:-/root/fsvfy/harness-tree}
WORK=${WORK_DIR:-/root/fsvfy}
PLAIN="$WORK/plainfs"
MOUNT=${VFAF_MOUNT:-/mnt/nohl}
SHIM="$WORK/link-shim.so"
PRISTINE="$WORK/lib-index.upstream.js"

[ -f "$MANIFEST" ] || { echo "FATAL: overlay manifest missing — run dsh-mobile/tools/build-harness-overlay.mjs first"; exit 2; }
[ -f "$ASSET/lib/index.js" ] || { echo "FATAL: overlay payload missing at $ASSET"; exit 2; }
[ -d "$TREE/node_modules" ] || { echo "FATAL: no npm tree at $TREE (see the header for how to prepare one)"; exit 2; }

PKG=""
for candidate in "$TREE/node_modules/@deepseek-ai/dsh-fs-local" \
                 "$TREE/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-fs-local"; do
  [ -d "$candidate" ] && { PKG="$candidate"; break; }
done
[ -n "$PKG" ] || { echo "FATAL: @deepseek-ai/dsh-fs-local not found under $TREE"; exit 2; }

# The "before" runs need the *published* build. It is never taken from the tree:
# a previous run of this gate leaves the tree patched, and a patched baseline
# would silently make every before/after pair identical. Capture it with
#   npm pack @deepseek-ai/dsh-fs-local@<version>   → package/lib/index.js
mkdir -p "$WORK" "$PLAIN"
cp "$HERE/drive.mjs" "$TREE/drive.mjs"
[ -f "$PRISTINE" ] || { echo "FATAL: no upstream baseline at $PRISTINE — see the header (npm pack) or set UPSTREAM_LIB"; exit 2; }
if grep -q 'isHardLinkUnavailable' "$PRISTINE"; then
  echo "FATAL: $PRISTINE already contains the fix; capture the published build instead"; exit 2
fi
grep -q 'isHardLinkUnavailable' "$ASSET/lib/index.js" || { echo "FATAL: overlay payload lacks the fix markers"; exit 2; }
gcc -shared -fPIC -o "$SHIM" "$HERE/link-shim.c" || { echo "FATAL: shim build failed"; exit 2; }

echo "package under test : ${PKG#"$TREE"/}"
echo "upstream sha256    : $(sha256sum "$PRISTINE" | cut -d' ' -f1)"
echo "overlay sha256     : $(sha256sum "$ASSET/lib/index.js" | cut -d' ' -f1)"
echo "manifest sha256    : $(awk -F'\t' '$3=="lib/index.js"{print $4}' "$MANIFEST")"

# Real filesystem that cannot hold hard links. Rebuilt every run so no earlier
# run's files can collide with this one.
umount "$MOUNT" 2>/dev/null || true
mkdir -p "$MOUNT"
IMG="$WORK/nohl.img"
rm -f "$IMG"
dd if=/dev/zero of="$IMG" bs=1M count=64 status=none
mkfs.vfat -F 32 "$IMG" >/dev/null
LOOP=$(losetup --show -f "$IMG") || { echo "FATAL: losetup failed"; exit 2; }
mount -t vfat "$LOOP" "$MOUNT" || { echo "FATAL: vfat mount failed"; exit 2; }
echo "no-hardlink mount  : $(findmnt -no SOURCE,FSTYPE "$MOUNT")"

drive() { # drive <label> <dir> [shim mode]
  local label=$1 dir=$2 mode=${3:-}
  if [ -n "$mode" ]; then
    LINK_SHIM_MODE="$mode" LD_PRELOAD="$SHIM" node "$TREE/drive.mjs" "$dir" "$label" 2>/dev/null
  else
    node "$TREE/drive.mjs" "$dir" "$label" 2>/dev/null
  fi
}
restore() { cp "$PRISTINE" "$PKG/lib/index.js"; }
apply_overlay() { cp -r "$ASSET/." "$PKG/"; }

results=""
pair() { # pair <case> <dir> [shim mode]
  local case=$1 dir=$2 mode=${3:-}
  restore;     results="$results$(drive "$case-before" "$dir" "$mode")"$'\n'
  apply_overlay; results="$results$(drive "$case-after" "$dir" "$mode")"$'\n'
}

echo
echo "== running A (symlink shim) / B (EPERM shim) / C (real vfat) =="
pair A "$PLAIN" symlink
pair B "$PLAIN" eperm
pair C "$MOUNT"

echo
echo "== evidence =="
printf '%s' "$results" | grep -v '^$' | sed 's/^/  /'

fail=0
expect() { # expect <label> <substring>
  if printf '%s' "$results" | grep -q "\"label\":\"$1\".*$2"; then
    echo "  ok   $1 contains $2"
  else
    echo "  FAIL $1 should contain $2"; fail=1
  fi
}
expect A-before 'DANGLING-symlink'
expect A-before 'reported-success'
expect A-after  'error:FS_NOT_REGULAR_FILE'
expect B-before 'error:FS_IO_ERROR'
expect B-after  'regular-file(content-ok)'
expect C-before 'error:FS_IO_ERROR'
expect C-after  'regular-file(content-ok)'

installed=$(sha256sum "$PKG/lib/index.js" | cut -d' ' -f1)
manifest=$(awk -F'\t' '$3=="lib/index.js"{print $4}' "$MANIFEST")
if [ "$installed" = "$manifest" ]; then echo "  ok   installed bundle matches the overlay manifest"; else echo "  FAIL installed bundle differs from the manifest"; fail=1; fi

residue=$(find "$PLAIN" "$MOUNT" -maxdepth 1 -name '.*tmp' 2>/dev/null | wc -l)
if [ "$residue" -eq 0 ]; then echo "  ok   no staging residue"; else echo "  FAIL $residue staging leftover(s)"; fail=1; fi

echo
if [ "$fail" -eq 0 ]; then echo "harness no-hardlink gate: all expectations met"; else echo "harness no-hardlink gate: FAILURES above"; fi
exit "$fail"
