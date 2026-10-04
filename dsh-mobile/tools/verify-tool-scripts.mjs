#!/usr/bin/env node
/**
 * Syntax gate for the dsh-mobile tool scripts.
 *
 * Those `.mjs` files sit outside every lint and typecheck surface in this
 * repository (oxlint ignores dsh-mobile, and they carry no TypeScript), so a
 * duplicate declaration or a stray syntax slip only surfaces at runtime — which
 * is how two of these tools were first "tested". `node --check` catches exactly
 * that class (early errors such as a redeclared `const`) without running the
 * tool, so it belongs in the merge/release routine.
 *
 * Usage: node dsh-mobile/tools/verify-tool-scripts.mjs
 */
import { execFileSync } from 'node:child_process'
import { readdir } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const toolsDir = resolve(fileURLToPath(new URL('.', import.meta.url)))

/** Every `.mjs` under dsh-mobile/tools, recursively. */
async function listScripts(root) {
  const found = []
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const absolute = join(root, entry.name)
    if (entry.isDirectory()) found.push(...await listScripts(absolute))
    else if (entry.isFile() && entry.name.endsWith('.mjs')) found.push(absolute)
  }
  return found.sort()
}

const scripts = await listScripts(toolsDir)
if (scripts.length === 0) {
  console.error('FATAL: no .mjs tool scripts found — is the tools directory still here?')
  process.exit(2)
}

const failures = []
for (const script of scripts) {
  const name = script.slice(toolsDir.length + 1)
  try {
    execFileSync(process.execPath, ['--check', script], { stdio: ['ignore', 'ignore', 'pipe'] })
    console.log(`  ✓ ${name}`)
  } catch (error) {
    const detail = String(error.stderr ?? error.message).trim().split('\n').slice(0, 3).join(' / ')
    console.log(`  ✗ ${name}: ${detail}`)
    failures.push(name)
  }
}

console.log(failures.length === 0
  ? `tool script syntax gate: ${scripts.length} script(s) parse`
  : `tool script syntax gate: ${failures.length} of ${scripts.length} script(s) failed to parse`)
process.exitCode = failures.length === 0 ? 0 : 1
