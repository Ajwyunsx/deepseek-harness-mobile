/**
 * Host-filesystem implementation of `ctx.fs`. Realpath-derived target identity makes aliases
 * share stale guards, and writes through a symlink update its target without replacing the link.
 * @module @deepseek-ai/dsh-fs-local
 */
import type { PatchHunk } from '@deepseek-ai/dsh-fs-patch';
import { Context } from '@deepseek-ai/cordis';
import z from '@deepseek-ai/schemastery';
import { FileSystem, type FsVersion } from '@deepseek-ai/dsh-fs';
import type { FsDeleteOutcome, FsMoveOutcome, FsDirEntry, FsEditOutcome, FsEditRequest, FsInfo, FsPathInfo, FsTarget, FsWriteIntent, FsWriteOutcome } from '@deepseek-ai/dsh-fs';
import type { FsIoInternals } from './fsio.ts';
/** Configuration for the local filesystem backend. */
export interface Config {
    /** Base directory for relative paths. Defaults to `process.cwd()`. */
    cwd?: string;
    /**
     * Exclusive UTF-8 byte limit on each overwrite-diff side, capped by the
     * runtime's safe allocation/decode maximum. Defaults to 10 MiB.
     */
    diffBasisMaxBytes?: number;
}
type ResolvedConfig = Required<Config>;
/**
 * The host-filesystem backend. Reads resolve relative paths from {@link Config.cwd}
 * (a resolution default, NOT a containment boundary — see the filesystem
 * capability-seam Agent Note); enforce
 * containment with a stricter backend or a `tools/execute` permission plugin.
 */
export declare class LocalFileSystem extends FileSystem {
    watch(target: FsTarget, changed: (error?: Error) => void, signal: AbortSignal): Promise<() => Promise<void>>;
    static Config: z<Config>;
    /** Validated config (schemastery applied the defaults before construction). */
    readonly config: ResolvedConfig;
    /** Test hook forwarded to fsio for atomic-publication boundaries. */
    internals: FsIoInternals;
    /** Per-targetKey tail promise: serializes mutating ops so the read→guard→write
     * window can't interleave, making concurrent writes/edits deterministically
     * ordered (one wins, the rest see the new version and reject as stale). */
    private locks;
    constructor(ctx: Context, config: Config);
    /** Run one mutation under the same lock ordering as multi-target operations. */
    private withLock;
    /** Acquire distinct keys in lexical order and release inner locks before outer locks. */
    private withLocks;
    resolve(path: string, opts?: {
        cwd?: string;
        signal?: AbortSignal;
    }): Promise<FsTarget>;
    processPath(target: FsTarget): string;
    processPathFromHostPath(hostPath: string): string | undefined;
    fileUrl(target: FsTarget): string;
    contains(parent: FsTarget, child: FsTarget): boolean;
    stat(target: FsTarget, signal?: AbortSignal): Promise<FsInfo | undefined>;
    lstat(path: string, opts?: {
        cwd?: string;
    }, signal?: AbortSignal): Promise<FsPathInfo | undefined>;
    readText(target: FsTarget, signal?: AbortSignal): Promise<string>;
    streamText(target: FsTarget, signal?: AbortSignal): Promise<AsyncIterable<string>>;
    readBytes(target: FsTarget, signal: AbortSignal | undefined, maxBytes: number): Promise<Uint8Array>;
    readByteRange(target: FsTarget, range: {
        offset: number;
        length: number;
    }, signal?: AbortSignal): Promise<Uint8Array>;
    listDir(target: FsTarget, signal?: AbortSignal): Promise<FsDirEntry[]>;
    writeText(target: FsTarget, content: string, expected?: FsWriteIntent, signal?: AbortSignal): Promise<FsWriteOutcome>;
    editText(target: FsTarget, edit: FsEditRequest, expected?: {
        version: FsVersion;
    }, signal?: AbortSignal): Promise<FsEditOutcome>;
    patchText(target: FsTarget, hunks: readonly PatchHunk[], expected?: {
        version: FsVersion;
    }, signal?: AbortSignal): Promise<FsEditOutcome>;
    deleteFile(target: FsTarget, expected?: {
        version: FsVersion;
    }, signal?: AbortSignal): Promise<FsDeleteOutcome>;
    moveFile(source: FsTarget, destination: FsTarget, options: Parameters<FileSystem['moveFile']>[2], signal?: AbortSignal): Promise<FsMoveOutcome>;
    private writeTarget;
    private requireFile;
    private patchContent;
    private versionAfterWrite;
}
export default LocalFileSystem;
//# sourceMappingURL=index.d.ts.map