/**
 * Behavioural driver for the fs-local `createIfAbsent` path, run inside the WSL
 * Ubuntu guest against a real filesystem.
 *
 * It answers two independent questions for one write:
 *   outcome  - what the tool layer would report to the model (success or error)
 *   onDisk   - what is actually at the target path afterwards
 *
 * Run it from a directory whose `node_modules` holds `@deepseek-ai/dsh-fs-local`
 * and `@deepseek-ai/cordis`.
 *
 * Usage: node drive.mjs <mountDir> <label>
 */
import { existsSync, lstatSync, readFileSync, readlinkSync } from 'node:fs'
import { join } from 'node:path'
import { Context } from '@deepseek-ai/cordis'
import { LocalFileSystem } from '@deepseek-ai/dsh-fs-local'

const [mount, label] = process.argv.slice(2)
// Random suffix: repeated runs on a reused mount must not collide with files a
// previous run left behind, or createIfAbsent would report FS_NOT_OBSERVED
// instead of exercising the publication path at all.
const name = `new-${label}-${Math.random().toString(36).slice(2, 10)}.txt`
const ctx = new Context()
await ctx.plugin(LocalFileSystem, { cwd: mount })
const fs = ctx.fs
const target = await fs.resolve(name)

let outcome
try {
  await fs.writeText(target, 'PAYLOAD\n', { kind: 'createIfAbsent' })
  outcome = 'reported-success'
} catch (error) {
  outcome = `error:${String(error?.code ?? error?.message ?? error)}`
}

const path = join(mount, name)
let onDisk = 'ABSENT'
// lstat, not existsSync: a dangling symlink is exactly the failure under test,
// and existsSync would follow the link and report "no entry".
const info = lstatSync(path, { throwIfNoEntry: false })
if (info) {
  if (info.isSymbolicLink()) {
    const target = readlinkSync(path)
    onDisk = existsSync(path) ? `symlink->${target}` : `DANGLING-symlink->${target}`
  } else if (info.isFile()) {
    onDisk = readFileSync(path, 'utf8') === 'PAYLOAD\n' ? 'regular-file(content-ok)' : 'regular-file(WRONG-content)'
  } else {
    onDisk = 'other'
  }
}

console.log(JSON.stringify({ label, outcome, onDisk }))
