// Copyright 2026 kotlin-lsp-dev contributors. Use of this source code is governed by the Apache 2.0 license.

import * as fs from 'node:fs';
import * as net from 'node:net';
import * as os from 'node:os';
import * as path from 'node:path';
import * as vscode from 'vscode';
import {
    CloseAction,
    CloseHandlerResult,
    ErrorAction,
    ErrorHandlerResult,
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    State,
    StreamInfo,
    TransportKind,
} from 'vscode-languageclient/node';
import { ExecuteCommand, registerDependencyExplorer } from './dependencyExplorer';
import { registerGradleTasks } from './gradleTasks';
import { registerLanguageModelTools } from './languageModelTools';
import { registerTestExplorer } from './testExplorer';

/**
 * A client for the enhanced Kotlin server built by kotlin-lsp-dev.
 *
 * Two deliberate limits, both because `scripts/install.sh` produces a self-contained server
 * directory carrying the release's own JBR: this does **not** download a distribution, and does
 * **not** look for a JDK. Everything else the official extension does, it does -- decompiled
 * source navigation, debugging, workspace export, organize imports -- plus the operations the
 * overlay adds, which are unreachable through the official extension because nothing invokes them.
 */

let client: LanguageClient | undefined;
let status: vscode.StatusBarItem;
let output: vscode.OutputChannel;
let indexed = false;
let overlayPresent = false;
let refreshDependencies: () => void = () => undefined;

const DEFAULT_SERVER_DIR = path.join(os.homedir(), '.local', 'share', 'kotlin-lsp-enhanced');
/** Schemes the server serves decompiled or bundled sources under. */
const DECOMPILED_SCHEMES = ['jar', 'jrt'];

export async function activate(context: vscode.ExtensionContext): Promise<void> {
    output = vscode.window.createOutputChannel('Kotlin (kotlin-lsp-dev)');
    status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
    status.command = 'kotlinLspDev.showOutput';
    context.subscriptions.push(output, status);

    context.subscriptions.push(
        vscode.commands.registerCommand('kotlinLspDev.restart', () => restart(context)),
        vscode.commands.registerCommand('kotlinLspDev.clearCachesAndRestart', () => clearCachesAndRestart(context)),
        vscode.commands.registerCommand('kotlinLspDev.reloadWorkspace', () => reloadWorkspace(context)),
        vscode.commands.registerCommand('kotlinLspDev.showOutput', () => output.show(true)),
        vscode.commands.registerCommand('kotlinLspDev.openServerPathSetting', () =>
            vscode.commands.executeCommand('workbench.action.openSettings', 'kotlinLspDev.serverPath')),
        vscode.commands.registerCommand('kotlinLspDev.showFeatures', showFeatures),
        vscode.commands.registerCommand('kotlinLspDev.doctor', doctor),
        vscode.commands.registerCommand('kotlinLspDev.analyzeStackTrace', analyzeStackTrace),
        vscode.commands.registerCommand('kotlinLspDev.findTextInDependencyJars', findTextInJars),
        vscode.commands.registerCommand('kotlinLspDev.copyFullyQualifiedName', copyFqn),
        vscode.commands.registerCommand('kotlinLspDev.organizeImports', organizeImports),
        vscode.commands.registerCommand('kotlinLspDev.exportWorkspaceToJson', exportWorkspace),
        // Deliberately not in contributes.commands: it is invoked from inlay hints, not the
        // palette. Namespaced rather than reusing the official extension's id, which would
        // collide outright whenever both are enabled.
        vscode.commands.registerCommand('kotlinLspDev.navigateToJarLocation', navigateToJarLocation),
        // Invoked from code lenses. A lens that looks clickable and does nothing reads as a
        // broken button, so the server emits these and the client makes them real.
        vscode.commands.registerCommand('kotlinLspDev.showReferences', (uri: string, line: number, character: number) =>
            peek(uri, line, character, 'vscode.executeReferenceProvider')),
        vscode.commands.registerCommand('kotlinLspDev.showImplementations', (uri: string, line: number, character: number) =>
            peek(uri, line, character, 'vscode.executeImplementationProvider')),
        vscode.commands.registerCommand('kotlinLspDev.runTest', runTest),
    );

    registerTestExplorer(context, output);
    refreshDependencies = registerDependencyExplorer(context, executeQuietly).refresh;
    registerGradleTasks(context);
    registerLanguageModelTools(context, executeQuietly);
    registerStackTraceLinks(context);
    registerDecompiledSources(context);
    registerDebugging(context);
    registerFileTemplates(context);
    await warnAboutConflictingExtensions();
    await start(context);
}

export async function deactivate(): Promise<void> {
    await client?.stop();
    client = undefined;
}

// --- startup ---------------------------------------------------------------------------------

function serverDirectory(): string {
    const configured = vscode.workspace.getConfiguration('kotlinLspDev').get<string>('serverPath');
    return configured && configured.trim().length > 0 ? configured.trim() : DEFAULT_SERVER_DIR;
}

/** Where this workspace's index lives when isolation is on. */
function indexCacheDirectory(context: vscode.ExtensionContext): string | undefined {
    if (!vscode.workspace.getConfiguration('kotlinLspDev').get<boolean>('isolateIndex')) return undefined;
    const storage = context.storageUri?.fsPath;
    return storage ? path.join(storage, 'index-cache') : undefined;
}

/**
 * The launcher to start: ours if the directory has one, otherwise the stock release's.
 *
 * This is what lets the extension work against a plain kotlin-lsp download. The composition server
 * adds capability repairs and the locally-answered operations; without it everything else still
 * works, just with fewer features. Falling back beats refusing to start.
 */
function resolveLauncher(directory: string): string | undefined {
    for (const name of ['enhanced-server', 'intellij-server']) {
        const candidate = path.join(directory, 'bin', name);
        if (fs.existsSync(candidate)) return candidate;
    }
    return undefined;
}

/** True when the connected server carries the overlay, judged by what it advertises. */
function detectOverlay(): boolean {
    const commands =
        (client?.initializeResult?.capabilities?.executeCommandProvider?.commands as string[] | undefined) ?? [];
    return commands.some((command) => command.startsWith('kotlin-lsp.'));
}

/**
 * Enables or hides the parts of the UI that only exist with the overlay.
 *
 * Judged from the server's own advertised commands rather than from the install layout, because
 * that is true regardless of how the server got there -- including the official extension's
 * bundled copy.
 */
async function applyServerCapabilities(): Promise<void> {
    overlayPresent = detectOverlay();
    await vscode.commands.executeCommand('setContext', 'kotlinLspDev.overlay', overlayPresent);
    output.appendLine(
        overlayPresent
            ? '[server] enhanced server detected: overlay commands available'
            : '[server] stock kotlin-lsp detected: overlay-only commands are hidden',
    );
}

async function start(context: vscode.ExtensionContext): Promise<void> {
    indexed = false;
    const configuration = vscode.workspace.getConfiguration('kotlinLspDev');
    const port = configuration.get<number>('serverPort') ?? 0;
    if (port > 0) {
        await startAttached(port);
        return;
    }

    const directory = serverDirectory();
    const launcher = resolveLauncher(directory);
    if (!launcher) {
        setStatus('$(error) Kotlin: no server', `No server at ${directory}`);
        const choice = await vscode.window.showErrorMessage(
            `No Kotlin server at ${directory}. Point kotlinLspDev.serverPath at an unpacked ` +
                `kotlin-lsp release, or run scripts/install.sh for the enhanced one.`,
            'Open settings',
        );
        if (choice === 'Open settings') {
            await vscode.commands.executeCommand('workbench.action.openSettings', 'kotlinLspDev.serverPath');
        }
        return;
    }

    const systemPath = context.storageUri?.fsPath ?? path.join(os.tmpdir(), 'kotlin-lsp-dev');
    const env: NodeJS.ProcessEnv = {
        ...process.env,
        KOTLIN_LSP_DEV_LOG: configuration.get<string>('log') ?? 'routing',
    };

    // The workspace index is keyed by workspace inside the cache directory and locked by whichever
    // server holds it -- `--system-path` does not separate it. Sharing therefore means a second
    // server on this project (the official extension's, or one started by hand) simply cannot
    // start. Isolating is the difference between coexisting and failing at launch.
    const cache = indexCacheDirectory(context);
    if (cache) {
        fs.mkdirSync(cache, { recursive: true });
        env.XDG_CACHE_HOME = cache;
    }

    const jvmArgs = configuration.get<string[]>('additionalJvmArgs') ?? [];
    if (jvmArgs.length > 0) {
        env.JAVA_TOOL_OPTIONS = [process.env.JAVA_TOOL_OPTIONS ?? '', ...jvmArgs].join(' ').trim();
    }

    const serverOptions: ServerOptions = {
        command: launcher,
        args: ['--stdio', '--system-path', systemPath],
        transport: TransportKind.stdio,
        options: { env },
    };

    client = new LanguageClient('kotlinLspDev', 'Kotlin (kotlin-lsp-dev)', serverOptions, clientOptions());
    registerNotifications();
    setStatus('$(sync~spin) Kotlin: starting', 'Server starting');
    try {
        await client.start();
    } catch (error) {
        output.appendLine(`[start] ${error}`);
        return;
    }
    await applyServerCapabilities();
    setStatus(
        overlayPresent ? '$(sync~spin) Kotlin: indexing' : '$(sync~spin) Kotlin (stock): indexing',
        'Importing and indexing the workspace',
    );
}

/**
 * Attach to a server already listening on 127.0.0.1:[port], started with
 * `bin/enhanced-server --socket <port>`.
 */
async function startAttached(port: number): Promise<void> {
    const serverOptions: ServerOptions = () =>
        new Promise<StreamInfo>((resolve, reject) => {
            const socket = net.connect({ host: '127.0.0.1', port });
            socket.once('connect', () => resolve({ reader: socket, writer: socket }));
            socket.once('error', reject);
        });

    client = new LanguageClient('kotlinLspDev', 'Kotlin (kotlin-lsp-dev)', serverOptions, clientOptions());
    registerNotifications();
    setStatus('$(plug) Kotlin: attaching', `Connecting to 127.0.0.1:${port}`);
    try {
        await client.start();
    } catch (error) {
        setStatus('$(error) Kotlin: not attached', `Nothing listening on 127.0.0.1:${port}`);
        const command = `${path.join(serverDirectory(), 'bin', 'enhanced-server')} --socket ${port}`;
        const choice = await vscode.window.showErrorMessage(
            `Could not connect to a Kotlin server on 127.0.0.1:${port}. Start one with: ${command}`,
            'Copy command',
        );
        if (choice === 'Copy command') await vscode.env.clipboard.writeText(command);
        return;
    }
    await applyServerCapabilities();
    setStatus('$(plug) Kotlin: attached', `Attached to 127.0.0.1:${port} — indexing`);
}

/**
 * Shared client configuration.
 *
 * The error handler exists for one specific failure: if another server holds this workspace's
 * index lock, every start fails on a RocksDB LOCK file and the default handler retries forever,
 * burning a JVM start each time and burying the cause. Restarting cannot help -- the lock belongs
 * to a different process -- so this stops and says what to do.
 */
function clientOptions(): LanguageClientOptions {
    let lockConflict = false;
    const isLockConflict = (error: unknown): boolean => {
        const text = String((error as { message?: string })?.message ?? error);
        return text.includes('LOCK') || text.includes('Resource temporarily unavailable');
    };

    // The server expects `buildTools` keyed by workspace-folder URI, not a single value -- a flat
    // `buildTool` is silently ignored. `""` selects no build tool, which falls back to the JSON
    // workspace importer; `null` means "any".
    const folders = vscode.workspace.workspaceFolders ?? [];
    const buildTools = Object.fromEntries(
        folders.map((folder) => [
            folder.uri.toString(),
            vscode.workspace.getConfiguration('kotlinLspDev', folder.uri).get<string | null>('buildTool') ?? null,
        ]),
    );
    const anyBuildToolConfigured = Object.values(buildTools).some((value) => value !== null);

    return {
        documentSelector: [
            { scheme: 'file', language: 'kotlin' },
            // Decompiled sources open under jar:/jrt:, and are worth full language features.
            ...DECOMPILED_SCHEMES.map((scheme) => ({ scheme, language: 'kotlin' })),
        ],
        outputChannel: output,
        progressOnInitialization: true,
        initializationOptions: anyBuildToolConfigured ? { buildTools } : undefined,
        middleware: {
            // Guarded at `prepareRename` as well as at the edit: this is what F2 asks first, so
            // the warning arrives before a new name has been typed rather than after.
            // Inert on `263.2689.0`, which advertises `renameProvider: true` with no
            // `prepareProvider` -- the editor therefore never sends `textDocument/prepareRename`.
            // Kept so the guard still fires at the earlier, better moment on a release that adds
            // it; `provideRenameEdits` below is what actually guards today.
            prepareRename: async (document, position, token, next) => {
                // Declining throws rather than returning null: null makes the editor report "this
                // element can't be renamed", which blames the symbol for a timing problem.
                if (!(await ensureIndexedForRename())) throw new Error(RENAME_NOT_INDEXED);
                return next(document, position, token);
            },
            // The request that actually produces the partial edit, and the guard that matters.
            provideRenameEdits: async (document, position, newName, token, next) => {
                if (!(await ensureIndexedForRename())) throw new Error(RENAME_NOT_INDEXED);
                return next(document, position, newName, token);
            },
            // A hint part pointing into a jar cannot be opened as a location by the editor, so
            // swap it for a command that routes through the decompiler. Same fix the official
            // extension makes; without it, clicking a type hint on a library symbol does nothing.
            resolveInlayHint: async (hint, token, next) => {
                const resolved = await next(hint, token);
                const label = resolved?.label;
                if (!resolved || !Array.isArray(label)) return resolved;
                for (const part of label as vscode.InlayHintLabelPart[]) {
                    const location = part.location;
                    if (!location || !DECOMPILED_SCHEMES.includes(location.uri.scheme)) continue;
                    delete (part as { location?: unknown }).location;
                    (part as { command?: vscode.Command }).command = {
                        title: 'Go to definition',
                        command: 'kotlinLspDev.navigateToJarLocation',
                        arguments: [
                            location.uri.toString(),
                            location.range.start.line,
                            location.range.start.character,
                        ],
                    };
                }
                return resolved;
            },
        },
        errorHandler: {
            error: (error): ErrorHandlerResult => {
                if (isLockConflict(error)) {
                    lockConflict = true;
                    void reportLockConflict();
                    return { action: ErrorAction.Shutdown };
                }
                return { action: ErrorAction.Continue };
            },
            closed: (): CloseHandlerResult =>
                lockConflict ? { action: CloseAction.DoNotRestart } : { action: CloseAction.Restart },
        },
    };
}

async function reportLockConflict(): Promise<void> {
    setStatus('$(error) Kotlin: index locked', 'Another server already holds this workspace index');
    const choice = await vscode.window.showErrorMessage(
        'Another Kotlin server holds this workspace’s index lock, so a second one cannot start. ' +
            'Turn on kotlinLspDev.isolateIndex to give this window its own index, attach to the ' +
            'running server, or stop it.',
        'Open settings',
    );
    if (choice === 'Open settings') {
        await vscode.commands.executeCommand('workbench.action.openSettings', 'kotlinLspDev');
    }
}

function registerNotifications(): void {
    if (!client) return;
    client.onDidChangeState((event) => {
        if (event.newState === State.Stopped) setStatus('$(error) Kotlin: stopped', 'Server stopped');
    });
    // `intellij/ready-for-test` is the server's own "imported and indexed" signal. Before it
    // arrives, index-backed operations answer from an incomplete index rather than failing -- a
    // rename can come back with the declaration renamed and every usage missed, with no error
    // anywhere. Surfacing it is the difference between a confusing result and an obvious one.
    client.onNotification('intellij/ready-for-test', () => {
        indexed = true;
        waitingForIndex.forEach((resolve) => resolve());
        waitingForIndex.length = 0;
        setStatus('$(check) Kotlin', 'Workspace indexed — index-backed results are complete');
        // The tree asked for modules before the import finished and was told there were none;
        // without this it keeps saying so until something makes the user press refresh.
        refreshDependencies();
    });
    client.onNotification('intellij/importLog', (params: { message?: string; failed?: boolean }) => {
        const message = (params?.message ?? '').trimEnd();
        if (message) output.appendLine(`[import] ${message}`);
        // The import can fail while the server keeps running, and then nothing works for reasons
        // that look like feature bugs. Say so once, loudly, rather than leaving it in a log.
        if (params?.failed || message.includes('Error importing project')) {
            setStatus('$(error) Kotlin: import failed', 'Project import failed — no modules, no index');
            void vscode.window
                .showErrorMessage(
                    'The Kotlin server failed to import this project, so symbol resolution will be broken. ' +
                        'Run "Kotlin: Doctor" for details.',
                    'Doctor',
                    'Show output',
                )
                .then((choice) => {
                    if (choice === 'Doctor') void vscode.commands.executeCommand('kotlinLspDev.doctor');
                    if (choice === 'Show output') output.show(true);
                });
        }
    });
}

// --- lifecycle commands ----------------------------------------------------------------------

async function restart(context: vscode.ExtensionContext): Promise<void> {
    await client?.stop();
    client = undefined;
    await start(context);
}

async function reloadWorkspace(context: vscode.ExtensionContext): Promise<void> {
    // The server imports the workspace during initialize, so a reload is a restart. Doing that
    // rather than pretending there is a cheaper path keeps the behaviour honest.
    await vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: 'Reloading Kotlin workspace' },
        () => restart(context),
    );
}

async function clearCachesAndRestart(context: vscode.ExtensionContext): Promise<void> {
    const cache = indexCacheDirectory(context);
    if (!cache) {
        await vscode.window.showWarningMessage(
            'Caches are shared with every other Kotlin server (kotlinLspDev.isolateIndex is off), so ' +
                'this cannot clear them safely. Turn isolation on, or delete ~/.cache/JetBrains/analyzer yourself.',
        );
        return;
    }
    await client?.stop();
    client = undefined;
    await vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: 'Clearing Kotlin index cache' },
        async () => {
            await fs.promises.rm(cache, { recursive: true, force: true });
        },
    );
    output.appendLine(`[cache] removed ${cache}`);
    await start(context);
}

// --- server-backed commands ------------------------------------------------------------------

/**
 * Runs one of the server's `workspace/executeCommand` commands.
 *
 * `quiet` suppresses the notifications, for callers the user did not personally invoke. A tree view
 * refreshing itself, or a chat tool answering in its own words, must not raise a popup about a
 * server that is merely still starting.
 */
async function execute<T>(command: string, args: unknown[], quiet = false): Promise<T | undefined> {
    if (!client || client.state !== State.Running) {
        if (!quiet) vscode.window.showWarningMessage('The Kotlin language server is not running.');
        return undefined;
    }
    // Naming the cause matters: without it this reads as the feature being broken rather than
    // absent, and the fix (install the enhanced server) is not guessable from a command failure.
    if (command.startsWith('kotlin-lsp.') && !overlayPresent) {
        if (!quiet) {
            vscode.window.showInformationMessage(
                'This needs the enhanced server. The one in use is a stock kotlin-lsp release, which ' +
                    'does not provide this command.',
            );
        }
        return undefined;
    }
    try {
        return (await client.sendRequest('workspace/executeCommand', { command, arguments: args })) as T;
    } catch (error) {
        if (!quiet) vscode.window.showErrorMessage(`${command} failed: ${error}`);
        else output.appendLine(`[${command}] ${error}`);
        return undefined;
    }
}

/** For background callers: same commands, no notifications. */
const executeQuietly: ExecuteCommand = <T>(command: string, args: unknown[]) =>
    execute<T>(command, args, true);

// --- rename against a cold index ---------------------------------------------------------------

/** Resolvers waiting for `intellij/ready-for-test`. */
const waitingForIndex: (() => void)[] = [];

const RENAME_NOT_INDEXED =
    'Rename cancelled: the workspace is still indexing, so usages in other files would be missed.';

/** How long to wait for the index before offering to give up. Indexing a large project is slow. */
const INDEX_WAIT_SECONDS = 600;

/**
 * Blocks a rename until the index is complete, because the failure is silent.
 *
 * Before `intellij/ready-for-test`, rename does not fail -- it *succeeds partially*: the
 * declaration is renamed, usages in other files are missed, and no error is reported anywhere.
 * Reproduced deliberately:
 *
 *     early rename (index cold):  2 changes, Widget.kt only
 *     after ready-for-test:       Widget.kt + UseIt.kt + file rename
 *
 * The result is a project that no longer compiles, from an operation that looked like it worked.
 * Every other index-backed feature degrades visibly -- a missing completion or an empty peek view
 * is obviously incomplete -- so rename is the one place worth stopping.
 *
 * Waiting is the default rather than refusing: the user asked to rename, and the only thing wrong
 * is the timing. Renaming anyway stays available for a rename known to be local to one file.
 */
async function ensureIndexedForRename(): Promise<boolean> {
    if (indexed) return true;

    const choice = await vscode.window.showWarningMessage(
        'The workspace is still indexing. Renaming now updates the declaration but silently misses ' +
            'usages in other files.',
        { modal: true },
        'Wait for indexing',
        'Rename anyway',
    );
    if (choice === 'Rename anyway') return true;
    if (choice !== 'Wait for indexing') return false;

    return vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: 'Waiting for the Kotlin index', cancellable: true },
        (_progress, token) =>
            new Promise<boolean>((resolve) => {
                if (indexed) return resolve(true);
                let timer: NodeJS.Timeout;
                const ready = () => finish(true);
                const finish = (complete: boolean) => {
                    clearTimeout(timer);
                    const at = waitingForIndex.indexOf(ready);
                    if (at >= 0) waitingForIndex.splice(at, 1);
                    resolve(complete);
                };
                waitingForIndex.push(ready);
                timer = setTimeout(() => finish(false), INDEX_WAIT_SECONDS * 1000);
                token.onCancellationRequested(() => finish(false));
            }),
    );
}

/** Warns when an index-backed answer is about to be incomplete rather than merely wrong-looking. */
function warnIfNotIndexed(what: string): void {
    if (indexed) return;
    void vscode.window.showWarningMessage(
        `The workspace is still indexing, so ${what} may be incomplete. Wait for the status bar tick.`,
    );
}

interface DoctorReport {
    project: string;
    jdk: { name: string; home: string } | null;
    healthy: boolean;
    modules: { name: string; sourceRoots: string[]; classpath: string[] }[];
}

async function doctor(): Promise<void> {
    const report = await execute<DoctorReport>('kotlin-lsp.doctor', []);
    if (!report) return;

    const lines: string[] = [];
    lines.push(`Project:  ${report.project}`);
    lines.push(`Healthy:  ${report.healthy ? 'yes' : 'NO'}`);
    lines.push(`Indexed:  ${indexed ? 'yes' : 'not yet — results may be incomplete'}`);
    lines.push(
        `JDK:      ${report.jdk ? `${report.jdk.name} (${report.jdk.home})` : 'NONE — symbol resolution will be broken'}`,
    );
    lines.push(`Modules:  ${report.modules.length}`);
    if (report.modules.length === 0) {
        lines.push('  (no modules — the workspace was not imported, so most features cannot work.');
        lines.push('   Check the output channel for an import error.)');
    }
    for (const module of report.modules) {
        lines.push('');
        lines.push(`  ${module.name}`);
        lines.push(`    source roots (${module.sourceRoots.length}):`);
        module.sourceRoots.forEach((root) => lines.push(`      ${root}`));
        lines.push(`    classpath (${module.classpath.length}):`);
        module.classpath.slice(0, 40).forEach((entry) => lines.push(`      ${entry}`));
        if (module.classpath.length > 40) lines.push(`      ... and ${module.classpath.length - 40} more`);
    }

    const document = await vscode.workspace.openTextDocument({ content: lines.join('\n'), language: 'text' });
    await vscode.window.showTextDocument(document, { preview: true });
}

interface LspLocation {
    uri: string;
    range: { start: { line: number; character: number }; end: { line: number; character: number } };
}

async function analyzeStackTrace(): Promise<void> {
    // A stack trace is multi-line and an input box is not; the clipboard is where a trace being
    // investigated almost always already is.
    const clipboard = await vscode.env.clipboard.readText();
    const text = await vscode.window.showInputBox({
        title: 'Analyze JVM stack trace',
        prompt: 'Paste a stack trace (the clipboard is pre-filled)',
        value: clipboard.split('\n').slice(0, 40).join('\n'),
    });
    if (!text) return;

    warnIfNotIndexed('frame resolution');
    const frames = await execute<LspLocation[]>('kotlin-lsp.analyzeStackTrace', [text]);
    if (!frames || frames.length === 0) {
        vscode.window.showInformationMessage('No stack frames resolved to files in this workspace.');
        return;
    }

    const picked = await vscode.window.showQuickPick(
        frames.map((frame) => ({
            label: `${path.basename(vscode.Uri.parse(frame.uri).fsPath)}:${frame.range.start.line + 1}`,
            description: vscode.workspace.asRelativePath(vscode.Uri.parse(frame.uri)),
            frame,
        })),
        { title: `${frames.length} resolved frame(s)`, placeHolder: 'Go to frame' },
    );
    if (picked) await revealLocation(vscode.Uri.parse(picked.frame.uri), picked.frame.range.start);
}

interface JarMatch {
    jar: string;
    entry: string;
    line: number;
    text: string;
}

async function findTextInJars(): Promise<void> {
    const query = await vscode.window.showInputBox({
        title: 'Find text in dependency jars',
        prompt: 'Text to search for inside the classpath jars',
    });
    if (!query) return;

    const matches = await vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: `Searching dependency jars for "${query}"` },
        () => execute<JarMatch[]>('kotlin-lsp.findTextInDependencyJars', [query]),
    );
    if (!matches || matches.length === 0) {
        vscode.window.showInformationMessage(`No matches for "${query}" in dependency jars.`);
        return;
    }

    await vscode.window.showQuickPick(
        matches.map((match) => ({
            label: `${match.entry}:${match.line}`,
            description: path.basename(match.jar),
            detail: match.text.trim(),
        })),
        { title: `${matches.length} match(es)`, placeHolder: 'Matches are read-only' },
    );
}

async function copyFqn(): Promise<void> {
    const editor = vscode.window.activeTextEditor;
    if (!editor || editor.document.languageId !== 'kotlin') {
        vscode.window.showWarningMessage('Open a Kotlin file and place the caret on a declaration.');
        return;
    }
    const offset = editor.document.offsetAt(editor.selection.active);
    const name = await execute<string | null>('kotlin-lsp.copyFullyQualifiedName', [
        editor.document.uri.toString(),
        offset,
    ]);
    if (!name) {
        vscode.window.showInformationMessage('No fully-qualified name at the caret.');
        return;
    }
    await vscode.env.clipboard.writeText(name);
    vscode.window.setStatusBarMessage(`Copied ${name}`, 3000);
}

async function organizeImports(): Promise<void> {
    const editor = vscode.window.activeTextEditor;
    if (!editor || editor.document.languageId !== 'kotlin') {
        vscode.window.showWarningMessage('Open a Kotlin file first.');
        return;
    }
    await vscode.commands.executeCommand('editor.action.sourceAction', {
        kind: 'source.organizeImports',
        apply: 'first',
    });
}

async function exportWorkspace(): Promise<void> {
    const folder = vscode.workspace.workspaceFolders?.[0];
    if (!folder) {
        vscode.window.showWarningMessage('Open a folder first.');
        return;
    }
    // The command writes <root>/workspace.json and returns nothing, so there is no result to
    // render -- only a file to offer.
    if (!client || client.state !== State.Running) {
        vscode.window.showWarningMessage('The Kotlin language server is not running.');
        return;
    }
    try {
        await client.sendRequest('workspace/executeCommand', {
            command: 'exportWorkspace',
            arguments: [folder.uri.fsPath],
        });
    } catch (error) {
        vscode.window.showErrorMessage(`exportWorkspace failed: ${error}`);
        return;
    }

    const written = vscode.Uri.joinPath(folder.uri, 'workspace.json');
    const choice = await vscode.window.showInformationMessage(
        `Exported workspace structure to ${vscode.workspace.asRelativePath(written)}.`,
        'Open',
    );
    if (choice === 'Open') {
        const document = await vscode.workspace.openTextDocument(written);
        await vscode.window.showTextDocument(document);
    }
}

/** Lists the overlay features actually built into this server, from the install manifest. */
async function showFeatures(): Promise<void> {
    const manifest = path.join(serverDirectory(), 'kotlin-lsp-dev-features.txt');
    let features: string[] = [];
    try {
        features = (await fs.promises.readFile(manifest, 'utf8'))
            .split('\n')
            .map((line) => line.trim())
            .filter(Boolean);
    } catch {
        vscode.window.showInformationMessage(
            overlayPresent
                ? `No feature manifest at ${manifest}; this server was not installed by scripts/install.sh.`
                : 'This is a stock kotlin-lsp release, so it carries no overlay features. ' +
                      'Run scripts/install.sh for the enhanced server.',
        );
        return;
    }
    const lines = [
        `Enhanced features in ${serverDirectory()}:`,
        '',
        ...features.map((feature) => `  • ${feature}`),
        '',
        'A feature missing here was release-gated at build time: its LSP API is absent from the',
        'release this server was built against, so it was skipped rather than shipped broken.',
    ];
    const document = await vscode.workspace.openTextDocument({ content: lines.join('\n'), language: 'text' });
    await vscode.window.showTextDocument(document, { preview: true });
}

// --- decompiled sources ----------------------------------------------------------------------

/**
 * Serves `jar:`/`jrt:` documents by asking the server to decompile them.
 *
 * Without this, Go to Definition on anything from a dependency or the JDK opens nothing at all --
 * the editor cannot read those URIs. The server's `decompile` command returns the text.
 */
interface DecompiledDocument {
    code: string;
    language?: string;
}

function registerDecompiledSources(context: vscode.ExtensionContext): void {
    // The provider is asked for content more than once per document, and decompiling is not
    // cheap; the language is remembered too, because it can only be applied once the document
    // exists, which happens after the content is returned.
    const decompiled = new Map<string, DecompiledDocument>();

    const provider: vscode.TextDocumentContentProvider = {
        async provideTextDocumentContent(uri: vscode.Uri): Promise<string> {
            const key = uri.toString();
            const cached = decompiled.get(key);
            if (cached) return cached.code;

            const result = await execute<DecompiledDocument>('decompile', [key], true);
            if (!result || typeof result.code !== 'string') {
                // Saying only "could not decompile" sends people looking for a broken decompiler.
                // The usual cause is the server refusing, and the reason is in the output channel.
                return [
                    `// Could not decompile ${key}`,
                    '//',
                    '// The server returned no source for this entry. The reason, if it gave one, is in',
                    '// the "Kotlin (kotlin-lsp-dev)" output channel.',
                ].join('\n');
            }
            decompiled.set(key, result);
            return result.code;
        },
    };

    for (const scheme of DECOMPILED_SCHEMES) {
        context.subscriptions.push(vscode.workspace.registerTextDocumentContentProvider(scheme, provider));
    }

    // Decompiled Java opens as Kotlin otherwise, because our document selector claims the scheme.
    context.subscriptions.push(
        vscode.workspace.onDidOpenTextDocument(async (document) => {
            const entry = decompiled.get(document.uri.toString());
            if (!entry?.language || entry.language === document.languageId) return;
            await vscode.languages.setTextDocumentLanguage(document, entry.language);
        }),
    );
}

async function navigateToJarLocation(uri: string, line: number, character: number): Promise<void> {
    await revealLocation(vscode.Uri.parse(uri), { line, character });
}

async function revealLocation(uri: vscode.Uri, at: { line: number; character: number }): Promise<void> {
    const document = await vscode.workspace.openTextDocument(uri);
    const editor = await vscode.window.showTextDocument(document, { preview: true });
    const position = new vscode.Position(at.line, at.character);
    editor.selection = new vscode.Selection(position, position);
    editor.revealRange(new vscode.Range(position, position), vscode.TextEditorRevealType.InCenter);
}

// --- debugging -------------------------------------------------------------------------------

/** The server hosts a debug adapter; `start_debug_server` starts it and returns a port. */
function registerDebugging(context: vscode.ExtensionContext): void {
    const factory: vscode.DebugAdapterDescriptorFactory = {
        async createDebugAdapterDescriptor(
            session: vscode.DebugSession,
        ): Promise<vscode.DebugAdapterDescriptor | undefined> {
            const port = await execute<number>('start_debug_server', [session.workspaceFolder?.uri.toString()]);
            if (typeof port !== 'number') {
                vscode.window.showErrorMessage('The Kotlin server did not start a debug adapter.');
                return undefined;
            }
            output.appendLine(`[debug] adapter listening on port ${port}`);
            return new vscode.DebugAdapterServer(port);
        },
    };
    context.subscriptions.push(vscode.debug.registerDebugAdapterDescriptorFactory('kotlinLspDev', factory));
}

// --- file templates ------------------------------------------------------------------------

/**
 * Fills a newly created empty file from a template.
 *
 * The interpolation is the server's (`interpolateFileTemplate`), which is what knows the package
 * name for the file's location -- the part that is tedious to type and easy to get wrong. Templates
 * come from `kotlinLspDev.templates`, keyed by language id; with none configured this does nothing.
 */
function registerFileTemplates(context: vscode.ExtensionContext): void {
    context.subscriptions.push(
        vscode.workspace.onDidCreateFiles(async (event) => {
            if (event.files.length !== 1) return;
            await applyTemplate(event.files[0]);
        }),
    );
}

async function applyTemplate(uri: vscode.Uri): Promise<void> {
    if (!client || client.state !== State.Running) return;
    let document: vscode.TextDocument;
    try {
        document = await vscode.workspace.openTextDocument(uri);
    } catch {
        return;
    }
    // Only an empty file: anything already written is the user's.
    if (document.getText().length > 0) return;

    const configured = vscode.workspace
        .getConfiguration('kotlinLspDev', uri)
        .get<Record<string, Record<string, string>>>('templates');
    const templates = configured?.[document.languageId];
    const names = templates ? Object.keys(templates) : [];
    if (names.length === 0) return;

    const chosen =
        names.length === 1
            ? names[0]
            : await vscode.window.showQuickPick(names, { placeHolder: 'Select a file template' });
    if (!chosen) return;

    const content = await execute<string>('interpolateFileTemplate', [uri.toString(), templates![chosen]]);
    if (!content || typeof content !== 'string') return;

    const editor = await vscode.window.showTextDocument(document);
    // `|` marks the caret in a template; `$0` is how a snippet spells that.
    await editor.insertSnippet(new vscode.SnippetString(content.replace('|', '$0')));
    await vscode.commands.executeCommand('editor.action.formatDocument');
}

// --- terminal stack traces ------------------------------------------------------------------

/**
 * Makes JVM stack frames in the terminal clickable.
 *
 * A stack trace is the moment you most want to be in the editor, and it is normally the one place
 * the editor cannot help: the terminal shows `at pkg.Class.method(File.kt:42)` as plain text. The
 * frame already names the file and line, so this needs no server round-trip -- it resolves against
 * the workspace and opens the file at the line.
 */
function registerStackTraceLinks(context: vscode.ExtensionContext): void {
    // `at pkg.Class.method(File.kt:42)` -- capture the file and line inside the parentheses.
    const frame = /\(([\w$]+\.kts?):(\d+)\)/g;

    const provider: vscode.TerminalLinkProvider<vscode.TerminalLink & { file: string; line: number }> = {
        provideTerminalLinks(terminalContext) {
            const links: (vscode.TerminalLink & { file: string; line: number })[] = [];
            let match: RegExpExecArray | null;
            frame.lastIndex = 0;
            while ((match = frame.exec(terminalContext.line)) !== null) {
                links.push({
                    startIndex: match.index + 1,
                    length: match[0].length - 2,
                    tooltip: `Open ${match[1]}:${match[2]}`,
                    file: match[1],
                    line: Number(match[2]),
                });
            }
            return links;
        },
        async handleTerminalLink(link) {
            const found = await vscode.workspace.findFiles(`**/${link.file}`, '**/build/**', 2);
            if (found.length === 0) {
                vscode.window.showInformationMessage(`${link.file} is not in this workspace.`);
                return;
            }
            // More than one match means the file name is ambiguous; ask rather than guess wrong.
            const target =
                found.length === 1
                    ? found[0]
                    : (
                          await vscode.window.showQuickPick(
                              found.map((uri) => ({
                                  label: vscode.workspace.asRelativePath(uri),
                                  uri,
                              })),
                              { title: `Which ${link.file}?` },
                          )
                      )?.uri;
            if (!target) return;
            await revealLocation(target, { line: Math.max(0, link.line - 1), character: 0 });
        },
    };
    context.subscriptions.push(vscode.window.registerTerminalLinkProvider(provider));
}

// --- code lens actions ----------------------------------------------------------------------

/** Opens the peek view over whatever the given provider finds, as clicking a lens should. */
async function peek(uri: string, line: number, character: number, provider: string): Promise<void> {
    const target = vscode.Uri.parse(uri);
    const position = new vscode.Position(line, character);
    const locations =
        (await vscode.commands.executeCommand<vscode.Location[]>(provider, target, position)) ?? [];
    if (locations.length === 0) {
        vscode.window.showInformationMessage('Nothing found.');
        return;
    }
    await vscode.commands.executeCommand(
        'editor.action.showReferences',
        target,
        position,
        locations,
    );
}

/**
 * Runs a single test through the project's Gradle wrapper.
 *
 * `--tests` needs the test named precisely; the server supplies `pkg.Class.method`, because a run
 * button that quietly runs the whole suite is worse than one that does nothing.
 */
async function runTest(uri: string, testId: string): Promise<void> {
    const target = vscode.Uri.parse(uri);
    const folder = vscode.workspace.getWorkspaceFolder(target) ?? vscode.workspace.workspaceFolders?.[0];
    if (!folder) {
        vscode.window.showWarningMessage('No workspace folder to run the test in.');
        return;
    }
    const wrapper = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
    if (!fs.existsSync(path.join(folder.uri.fsPath, wrapper.replace('./', '')))) {
        vscode.window.showWarningMessage(
            `No Gradle wrapper in ${folder.name}, so this test cannot be run from here.`,
        );
        return;
    }
    const task = new vscode.Task(
        { type: 'kotlinLspDev', testId },
        folder,
        `test ${testId}`,
        'kotlin-lsp-dev',
        new vscode.ShellExecution(`${wrapper} test --tests "${testId}"`, { cwd: folder.uri.fsPath }),
    );
    task.presentationOptions = { reveal: vscode.TaskRevealKind.Always, clear: true };
    await vscode.tasks.executeTask(task);
}

// --- housekeeping ----------------------------------------------------------------------------

/**
 * Two clients claiming `.kt` both start a server, and the second loses the index-lock race. The
 * failure that produces is opaque, so name the cause up front.
 */
async function warnAboutConflictingExtensions(): Promise<void> {
    const conflicting = ['jetbrains.kotlin-server', 'fwcd.kotlin', 'mathiasfrohlich.kotlin']
        .map((id) => vscode.extensions.getExtension(id))
        .filter((extension): extension is vscode.Extension<unknown> => extension !== undefined);
    if (conflicting.length === 0) return;

    const names = conflicting.map((extension) => extension.id).join(', ');
    output.appendLine(`[conflict] other Kotlin extensions enabled: ${names}`);
    const choice = await vscode.window.showWarningMessage(
        `Another Kotlin extension is enabled (${names}). Both will start a language server for the ` +
            'same project, and only one can hold the index.',
        'Show extensions',
    );
    if (choice === 'Show extensions') {
        await vscode.commands.executeCommand('workbench.extensions.search', 'kotlin');
    }
}

function setStatus(text: string, tooltip: string): void {
    status.text = text;
    status.tooltip = tooltip;
    status.show();
}
