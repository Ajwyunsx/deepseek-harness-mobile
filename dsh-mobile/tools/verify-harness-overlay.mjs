#!/usr/bin/env node
/**
 * Regression gate for the device-side harness overlay.
 *
 * Compiles HarnessOverlay.java with plain javac and runs it against synthetic
 * rootfs trees, so the logic that rewrites packages inside the container is
 * verified on a development host instead of only on a device. No network, no
 * Gradle, no Android SDK: a JDK on PATH plus the committed assets is enough.
 *
 * Usage (from the repository root):
 *   node dsh-mobile/tools/verify-harness-overlay.mjs
 *
 * Scenarios:
 *   assets           nested APK assets extract and survive a repeated extraction
 *   node             the runtime check enforces the harness Node >=24.9 requirement
 *   hoisted          npm hoisted the patched package to the tree root
 *   nested           the CLI nests it, and a stale hoisted copy carries another
 *                    version that must stay untouched
 *
 * The overlay directory is the generated one, so the gate fails when the assets
 * were not regenerated after changing the patched package.
 */

import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')
const overlayDir = join(repoRoot, 'dsh-mobile', 'app', 'src', 'main', 'assets', 'harness-overlay')
const overlaySource = join(repoRoot, 'dsh-mobile', 'app', 'src', 'main', 'java', 'com', 'dshmobile', 'app', 'HarnessOverlay.java')
const assetsSource = join(repoRoot, 'dsh-mobile', 'app', 'src', 'main', 'java', 'com', 'dshmobile', 'app', 'HarnessOverlayAssets.java')
const runnerSource = join(repoRoot, 'dsh-mobile', 'tools', 'overlay-check', 'OverlayCheck.java')
const assetsRunnerSource = join(repoRoot, 'dsh-mobile', 'tools', 'overlay-check', 'OverlayAssetsCheck.java')
const contextStubSource = join(repoRoot, 'dsh-mobile', 'tools', 'overlay-check', 'android', 'content', 'Context.java')
const assetManagerStubSource = join(repoRoot, 'dsh-mobile', 'tools', 'overlay-check', 'android', 'content', 'res', 'AssetManager.java')
const nodeRuntimeSource = join(repoRoot, 'dsh-mobile', 'app', 'src', 'main', 'java', 'com', 'dshmobile', 'app', 'NodeRuntime.java')
const nodeRuntimeRunnerSource = join(repoRoot, 'dsh-mobile', 'tools', 'overlay-check', 'NodeRuntimeCheck.java')

/** Run a command to completion; Windows resolves .cmd names through the shell. */
function run(command, args, options = {}) {
  const stdio = options.stdio ?? ['ignore', 'pipe', 'inherit']
  if (process.platform === 'win32') {
    // cmd.exe re-joins the arguments, so paths containing spaces need quoting.
    const quoted = args.map(arg => arg.includes(' ') ? `"${arg}"` : arg)
    return execFileSync(process.env.ComSpec ?? 'cmd.exe', ['/d', '/s', '/c', command, ...quoted], { ...options, stdio })
  }
  return execFileSync(command, args, { ...options, stdio })
}

async function sha256(file) {
  return createHash('sha256').update(await readFile(file)).digest('hex')
}

/** Manifest rows as { packageName, version, path, sha256 }. */
async function readManifest() {
  const raw = await readFile(join(overlayDir, 'manifest.tsv'), 'utf8').catch(() => {
    throw new Error('覆盖层清单不存在：先运行 node dsh-mobile/tools/build-harness-overlay.mjs')
  })
  return raw.split('\n')
    .map(line => line.trim())
    .filter(line => line !== '' && !line.startsWith('#'))
    .map(line => {
      const [packageName, version, path, sha256] = line.split('\t')
      if (packageName === undefined || version === undefined || path === undefined || sha256 === undefined) {
        throw new Error(`清单行格式错误: ${line}`)
      }
      return { packageName, version, path, sha256 }
    })
}

/** Write a package directory holding a placeholder entry file. */
async function writeInstalledPackage(packageDir, version, marker) {
  await mkdir(join(packageDir, 'lib'), { recursive: true })
  await writeFile(join(packageDir, 'package.json'), `${JSON.stringify({ name: '@deepseek-ai/dsh-fs-local', version }, null, 2)}\n`, 'utf8')
  await writeFile(join(packageDir, 'lib', 'index.js'), marker, 'utf8')
}

/** Write the container's CLI package, whose version is the installed harness version. */
async function writeInstalledCli(rootfsDir, version) {
  const cliDir = join(rootfsDir, 'opt', 'node', 'lib', 'node_modules', '@deepseek-ai', 'dsh')
  await mkdir(cliDir, { recursive: true })
  await writeFile(join(cliDir, 'package.json'), `${JSON.stringify({ name: '@deepseek-ai/dsh', version }, null, 2)}\n`, 'utf8')
}

/** Run the compiled overlay against a rootfs and return its stdout. */
function runOverlay(classesDir, rootfsDir) {
  return run('java', ['-cp', classesDir, 'OverlayCheck', rootfsDir, overlayDir], { encoding: 'utf8' })
}

const failures = []
function check(condition, message) {
  if (condition) {
    console.log(`  ✓ ${message}`)
  } else {
    console.log(`  ✗ ${message}`)
    failures.push(message)
  }
}

async function main() {
  const manifest = await readManifest()
  const packageName = manifest[0].packageName
  const packageVersion = manifest[0].version
  const entry = manifest.find(row => row.path === 'lib/index.js')
  if (entry === undefined) throw new Error('清单里没有 lib/index.js')
  const assetDir = packageName.replace(/^@/, '').replace('/', '-')

  const scratch = await mkdtemp(join(tmpdir(), 'dsh-overlay-check-'))
  const classesDir = join(scratch, 'classes')
  try {
    await mkdir(classesDir, { recursive: true })
    run('javac', ['-encoding', 'UTF-8', '-d', classesDir, runnerSource, overlaySource,
      assetsRunnerSource, assetsSource, contextStubSource, assetManagerStubSource,
      nodeRuntimeRunnerSource, nodeRuntimeSource])
    console.log(`compiled HarnessOverlay + HarnessOverlayAssets + checks → ${classesDir}`)

    console.log('\nscenario: nested Android asset extraction')
    const assetsOut = run('java', ['-cp', classesDir, 'com.dshmobile.app.OverlayAssetsCheck', overlayDir, join(scratch, 'extracted-overlay')], { encoding: 'utf8' })
    check(assetsOut.includes('OVERLAY_ASSETS_EXTRACT=true'), '嵌套资产目录可展开，重复展开仍逐字节一致')
    const nodeOut = run('java', ['-cp', classesDir, 'com.dshmobile.app.NodeRuntimeCheck', join(scratch, 'node-runtime')], { encoding: 'utf8' })
    check(nodeOut.includes('NODE_RUNTIME_CHECK=true'), 'Node 版本检查拒绝 22/24.8，接受 24.9 及更新主版本')

    console.log('\nscenario: hoisted package')
    const hoistedRootfs = join(scratch, 'hoisted-rootfs')
    const hoistedPackage = join(hoistedRootfs, 'opt', 'node', 'lib', 'node_modules', ...packageName.split('/'))
    await writeInstalledPackage(hoistedPackage, packageVersion, '// upstream placeholder\n')
    await writeInstalledCli(hoistedRootfs, packageVersion)
    const hoistedOut = runOverlay(classesDir, hoistedRootfs)
    check(hoistedOut.includes(`TARGET_VERSION=${packageVersion}`), '覆盖层声明的目标版本可读')
    check(hoistedOut.includes(`INSTALLED_HARNESS=${packageVersion}`), '读到容器内已装 harness 版本')
    check(hoistedOut.includes('NEEDS_BEFORE=true'), '首次运行需要覆盖')
    check(hoistedOut.includes('NEEDS_AFTER=false'), '覆盖后无需再覆盖（幂等）')
    check(await sha256(join(hoistedPackage, 'lib', 'index.js')) === entry.sha256, 'lib/index.js 覆盖为清单哈希')
    check(await readFile(join(hoistedPackage, 'lib', 'index.js'), 'utf8') === await readFile(join(overlayDir, assetDir, 'lib', 'index.js'), 'utf8'), '内容与资产逐字节一致')

    console.log('\nscenario: nested package + stale copy on another version')
    const nestedRootfs = join(scratch, 'nested-rootfs')
    const cliDir = join(nestedRootfs, 'opt', 'node', 'lib', 'node_modules', '@deepseek-ai', 'dsh')
    const nestedPackage = join(cliDir, 'node_modules', ...packageName.split('/'))
    const stalePackage = join(nestedRootfs, 'opt', 'node', 'lib', 'node_modules', ...packageName.split('/'))
    await writeInstalledPackage(nestedPackage, packageVersion, '// nested placeholder\n')
    await writeInstalledPackage(stalePackage, '0.1.0-not-ours', '// stale copy must stay\n')
    await writeInstalledCli(nestedRootfs, '0.1.0-not-ours')
    const nestedOut = runOverlay(classesDir, nestedRootfs)
    check(nestedOut.includes('INSTALLED_HARNESS=0.1.0-not-ours'), '读出不匹配的已装 harness 版本')
    check(nestedOut.includes('NEEDS_AFTER=false'), '嵌套副本被覆盖后一致')
    check(await sha256(join(nestedPackage, 'lib', 'index.js')) === entry.sha256, '嵌套副本被覆盖为清单哈希')
    check(await readFile(join(stalePackage, 'lib', 'index.js'), 'utf8') === '// stale copy must stay\n', '版本不符的陈旧副本未被改动')

    console.log('\nscenario: container without a harness install')
    const bareRootfs = join(scratch, 'bare-rootfs')
    await mkdir(join(bareRootfs, 'opt', 'node', 'lib', 'node_modules'), { recursive: true })
    const bareOut = runOverlay(classesDir, bareRootfs)
    check(bareOut.includes('INSTALLED_HARNESS=null'), '未安装时版本读作 null')
    check(bareOut.includes('NEEDS_BEFORE=false'), '未安装时不触发覆盖')
  } finally {
    await rm(scratch, { recursive: true, force: true })
  }

  if (failures.length > 0) {
    console.error(`\nharness overlay gate: ${failures.length} 项失败`)
    process.exitCode = 1
    return
  }
  console.log('\nharness overlay gate: 全部通过')
}

await main()
