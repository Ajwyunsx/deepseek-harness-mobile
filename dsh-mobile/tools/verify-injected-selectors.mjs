#!/usr/bin/env node
/**
 * Gate for the Android injector's DOM assumptions.
 *
 * mobile.css and inject.js target the dsh Web UI through `[class*="_xxx"]`
 * fragments and `[data-*]` attributes, because the built class names carry a
 * CSS-module hash. Those names are the upstream client's, so an upstream sync can
 * silently turn every injected rule into dead CSS — the UI still loads, it just
 * stops adapting to a phone.
 *
 * This checks each fragment the injector relies on against the merged client
 * sources and fails when one no longer exists. Run it after every upstream
 * merge; a fragment that legitimately disappeared must be fixed or deleted from
 * the injector in the same commit.
 *
 * Usage: node dsh-mobile/tools/verify-injected-selectors.mjs
 */
import { readdir, readFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const repoRoot = resolve(fileURLToPath(new URL('.', import.meta.url)), '..', '..')
const injectorDir = join(repoRoot, 'dsh-mobile', 'app', 'src', 'main', 'assets')
const clientRoots = ['packages/client', 'apps/web/src', 'packages/ui']

/** Client sources that carry the class names and data attributes at runtime. */
async function collectClientBlob() {
  const chunks = []
  for (const root of clientRoots) {
    const absolute = join(repoRoot, root)
    const files = await listFiles(absolute).catch(() => [])
    for (const file of files) {
      if (/\.(css|ts|tsx)$/.test(file) && !/[\\/](tests?|node_modules)[\\/]/.test(file) && !/\.(spec|e2e)\./.test(file)) {
        chunks.push(await readFile(file, 'utf8'))
      }
    }
  }
  return chunks.join('\n')
}

/** Recursively list files under a directory. */
async function listFiles(root) {
  const found = []
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const absolute = join(root, entry.name)
    if (entry.isDirectory()) found.push(...await listFiles(absolute))
    else if (entry.isFile()) found.push(absolute)
  }
  return found
}

/** Every `[class*="_fragment"]` fragment and `[data-attribute]` the injector uses. */
async function collectInjectorSelectors() {
  const classFragments = new Set()
  const dataAttributes = new Set()
  for (const name of ['mobile.css', 'inject.js']) {
    const source = await readFile(join(injectorDir, name), 'utf8')
    for (const match of source.matchAll(/\[class\*=\\?["']_([A-Za-z0-9_]+)\\?["']\]/g)) classFragments.add(match[1])
    for (const match of source.matchAll(/\[(data-[a-z0-9-]+)[\]=]/g)) dataAttributes.add(match[1])
  }
  return { classFragments: [...classFragments].sort(), dataAttributes: [...dataAttributes].sort() }
}

/**
 * Attributes the injector writes itself. They are not part of the client DOM, so
 * their absence from the client sources says nothing — `data-dsh-tip-armed` is
 * set by inject.js on each tooltip it has already handled.
 */
const INJECTOR_OWNED_ATTRIBUTES = new Set(['data-dsh-tip-armed'])

const blob = await collectClientBlob()
if (blob.length === 0) {
  console.error('FATAL: no client sources scanned — are the package paths still correct?')
  process.exit(2)
}
const { classFragments, dataAttributes } = await collectInjectorSelectors()
const clientAttributes = dataAttributes.filter(attribute => !INJECTOR_OWNED_ATTRIBUTES.has(attribute))

const missing = []
console.log(`scanning ${classFragments.length} class fragment(s) and ${clientAttributes.length} client data attribute(s)`
  + ` (${dataAttributes.length - clientAttributes.length} injector-owned skipped)`)
for (const fragment of classFragments) {
  if (!blob.includes(fragment)) missing.push(`class fragment "${fragment}"`)
}
for (const attribute of clientAttributes) {
  if (!blob.includes(attribute)) missing.push(`data attribute "${attribute}"`)
}

if (missing.length > 0) {
  console.error(`injected selectors no longer match the client DOM (${missing.length}):`)
  for (const entry of missing) console.error(`  - ${entry}`)
  console.error('Fix or delete the injected rule in dsh-mobile/app/src/main/assets/{mobile.css,inject.js}.')
  process.exit(1)
}
console.log('injected selectors: all fragments and attributes still present in the merged client sources')
