#!/usr/bin/env node
/**
 * Export this repository's patched harness packages into the Android app's
 * assets, so the container stops depending on the npm-published copy of those
 * packages.
 *
 * The app installs `@deepseek-ai/dsh` from the npm registry and then overwrites
 * the packages listed here with these assets (see HarnessOverlay.java). Only
 * packages this fork actually patches belong in OVERLAY_PACKAGES; each one is
 * packed exactly as `npm publish` would ship it.
 *
 * Usage (from the repository root, after `pnpm run build:lib:host`):
 *   node dsh-mobile/tools/build-harness-overlay.mjs
 *
 * The version recorded per package is the version the installed tree must
 * carry: the app skips an overlay whose version does not match.
 */

import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { cp, mkdir, mkdtemp, readdir, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

/** Harness packages this fork patches; each must be a workspace package. */
const OVERLAY_PACKAGES = ['@deepseek-ai/dsh-fs-local']

/**
 * Payload copied into the APK. Only what the container actually loads: the
 * overlay replaces files in an installed package, so it ships no docs, license,
 * or tests.
 */
const SHIP = ['lib', 'package.json']

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')
const assetRoot = join(repoRoot, 'dsh-mobile', 'app', 'src', 'main', 'assets', 'harness-overlay')

/** Asset directory name for a package name, without characters Android assets cannot hold. */
function assetDirName(packageName) {
  return packageName.replace(/^@/, '').replaceAll('/', '-')
}

/** Workspace directory of a package name, from the `packages/<group>/<name>` layout. */
async function findWorkspaceDir(packageName) {
  const groups = await readdir(join(repoRoot, 'packages'), { withFileTypes: true })
  for (const group of groups) {
    if (!group.isDirectory()) continue
    const candidates = await readdir(join(repoRoot, 'packages', group.name), { withFileTypes: true })
    for (const candidate of candidates) {
      if (!candidate.isDirectory()) continue
      const manifestPath = join(repoRoot, 'packages', group.name, candidate.name, 'package.json')
      const manifest = await readFile(manifestPath, 'utf8').then(JSON.parse).catch(() => undefined)
      if (manifest?.name === packageName) return join(repoRoot, 'packages', group.name, candidate.name)
    }
  }
  throw new Error(`${packageName}: no workspace package declares this name`)
}

/** Recursively list files under a directory as POSIX-relative paths. */
async function listFiles(root, current = root) {
  const entries = await readdir(current, { withFileTypes: true })
  const found = []
  for (const entry of entries) {
    const absolute = join(current, entry.name)
    if (entry.isDirectory()) found.push(...await listFiles(root, absolute))
    else if (entry.isFile()) found.push(relative(root, absolute).split(sep).join('/'))
  }
  return found.sort()
}

/** Newest modification time under a directory, or undefined when it holds no file. */
async function newestMtime(root) {
  let newest
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const absolute = join(root, entry.name)
    const candidate = entry.isDirectory() ? await newestMtime(absolute) : (await stat(absolute)).mtimeMs
    if (candidate !== undefined && (newest === undefined || candidate > newest)) newest = candidate
  }
  return newest
}

/** SHA-256 of a file, lowercase hex. */
async function sha256(file) {
  return createHash('sha256').update(await readFile(file)).digest('hex')
}

/**
 * Fail when a produced asset is untracked-but-ignored, because Git would then
 * never carry it: a clone or CI build would ship an overlay without the patched
 * code while every local check still passes. `lib/` is the known trap — the root
 * .gitignore excludes that directory name everywhere.
 * @param files - absolute paths of the produced assets.
 */
function assertNotIgnored(files) {
  const ignored = []
  for (const file of files) {
    try {
      execFileSync('git', ['check-ignore', '-q', '--', file], { cwd: repoRoot, stdio: ['ignore', 'ignore', 'ignore'] })
      ignored.push(file)
    } catch {
      // Exit status 1 means "not ignored", which is the expected result.
    }
  }
  if (ignored.length > 0) {
    throw new Error(`覆盖层资产被 .gitignore 忽略，提交后会缺失：\n  ${ignored.join('\n  ')}`)
  }
}

/**
 * Run a command to completion. Windows resolves `pnpm` through PATHEXT only in
 * a shell, so the command runs through `cmd.exe /c` there.
 * @param command - executable name, resolved from PATH.
 * @param args - arguments passed verbatim.
 * @param options - child-process options.
 */
function run(command, args, options) {
  if (process.platform === 'win32') {
    execFileSync(process.env.ComSpec ?? 'cmd.exe', ['/d', '/s', '/c', command, ...args], options)
    return
  }
  execFileSync(command, args, options)
}

/** Pack one workspace package and unpack the published payload into scratch. */
async function packOne(packageName, scratch) {
  const workspaceDir = await findWorkspaceDir(packageName)
  const packDir = join(scratch, assetDirName(packageName))
  await mkdir(packDir, { recursive: true })
  run('pnpm', ['--filter', packageName, 'pack', '--pack-destination', packDir], {
    cwd: repoRoot,
    stdio: ['ignore', 'ignore', 'inherit'],
  })
  const archives = (await readdir(packDir)).filter(name => name.endsWith('.tgz'))
  if (archives.length !== 1) {
    throw new Error(`${packageName}: expected one packed tarball, found ${archives.join(', ') || 'none'}`)
  }
  run('tar', ['-xzf', join(packDir, archives[0]), '-C', packDir], { stdio: 'inherit' })

  const extracted = join(packDir, 'package')
  const manifest = JSON.parse(await readFile(join(extracted, 'package.json'), 'utf8'))
  if (manifest.name !== packageName) {
    throw new Error(`${packageName}: packed tarball declares "${manifest.name}"`)
  }

  // npm normalizes tarball entry mtimes, so staleness compares the workspace's
  // own build output against src/ rather than the extracted copy.
  const sourceRoot = join(workspaceDir, 'src')
  const newestSource = await newestMtime(sourceRoot).catch(() => undefined)
  const built = join(workspaceDir, 'lib', 'index.js')
  if (newestSource !== undefined && newestSource > (await stat(built)).mtimeMs) {
    console.warn(`⚠ ${packageName}: src/ is newer than ${relative(repoRoot, built)} — run \`pnpm run build:lib:host\` first.`)
  }
  return { version: manifest.version, extracted }
}

async function main() {
  const scratch = await mkdtemp(join(tmpdir(), 'dsh-overlay-'))
  try {
    await rm(assetRoot, { recursive: true, force: true })
    await mkdir(assetRoot, { recursive: true })

    // Every workspace package carries the repository version, so the root version
    // is the harness version this overlay targets; the app pins its container
    // install to it instead of tracking the registry's `latest`.
    const harnessVersion = JSON.parse(await readFile(join(repoRoot, 'package.json'), 'utf8')).version
    const rows = []
    let bytes = 0
    const produced = [join(assetRoot, 'manifest.tsv'), join(assetRoot, 'harness-version.txt')]
    for (const packageName of OVERLAY_PACKAGES) {
      const { version, extracted } = await packOne(packageName, scratch)
      if (version !== harnessVersion) {
        throw new Error(`${packageName}@${version} 与仓库版本 ${harnessVersion} 不一致：覆盖层只能针对同一版本发布`)
      }
      const target = join(assetRoot, assetDirName(packageName))
      for (const shipped of SHIP) {
        await cp(join(extracted, shipped), join(target, shipped), { recursive: true })
      }
      let files = 0
      for (const relPath of await listFiles(target)) {
        const absolute = join(target, ...relPath.split('/'))
        produced.push(absolute)
        rows.push(`${packageName}\t${version}\t${relPath}\t${await sha256(absolute)}`)
        bytes += (await stat(absolute)).size
        files += 1
      }
      console.log(`overlay: ${packageName}@${version} → ${relative(repoRoot, target)} (${String(files)} file(s))`)
    }

    await writeFile(
      join(assetRoot, 'manifest.tsv'),
      `# harness overlay manifest: package\tversion\tpath\tsha256\n${rows.join('\n')}\n`,
      'utf8',
    )
    await writeFile(join(assetRoot, 'harness-version.txt'), `${harnessVersion}\n`, 'utf8')
    assertNotIgnored(produced)
    console.log(`overlay manifest: ${String(rows.length)} entry(ies), ${String(bytes)} bytes, harness ${harnessVersion}`)
  } finally {
    await rm(scratch, { recursive: true, force: true })
  }
}

await main()
