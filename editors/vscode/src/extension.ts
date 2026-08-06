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

/**
 * A thin client for the enhanced Kotlin server built by kotlin-lsp-dev.
 *
 * Deliberately thin. It does **not** download a distribution and does **not** look for a JDK:
 * `scripts/install.sh` produces a self-contained server directory with the release's own JBR, and
 * that is the only thing supported. Everything interesting lives in the composition server, not
 * here -- this exists so VS Code can reach it and so the commands the server adds are reachable
 * at all, which they are not through the stock extension.
 */

let client: LanguageClient | undefined;
let status: vscode.StatusBarItem;
let output: vscode.OutputChannel;

const DEFAULT_SERVER_DIR = path.join(os.homedir(), '.local', 'share', 'kotlin-lsp-enhanced');

export async function activate(context: vscode.ExtensionContext): Promise<void> {
    output = vscode.window.createOutputChannel('Kotlin (kotlin-lsp-dev)');
    status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
    status.command = 'kotlinLspDev.showOutput';
    context.subscriptions.push(output, status);

    context.subscriptions.push(
        vscode.commands.registerCommand('kotlinLspDev.restart', () => restart(context)),
        vscode.commands.registerCommand('kotlinLspDev.showOutput', () => output.show(true)),
        vscode.commands.registerCommand('kotlinLspDev.doctor', doctor),
        vscode.commands.registerCommand('kotlinLspDev.analyzeStackTrace', analyzeStackTrace),
        vscode.commands.registerCommand('kotlinLspDev.findTextInDependencyJars', findTextInJars),
        vscode.commands.registerCommand('kotlinLspDev.copyFullyQualifiedName', copyFqn),
    );

    await start(context);
}

export async function deactivate(): Promise<void> {
    await client?.stop();
    client = undefined;
}

function serverDirectory(): string {
    const configured = vscode.workspace.getConfiguration('kotlinLspDev').get<string>('serverPath');
    return configured && configured.trim().length > 0 ? configured.trim() : DEFAULT_SERVER_DIR;
}

async function start(context: vscode.ExtensionContext): Promise<void> {
    const configuration = vscode.workspace.getConfiguration('kotlinLspDev');
    const port = configuration.get<number>('serverPort') ?? 0;
    if (port > 0) {
        await startAttached(port);
        return;
    }

    const directory = serverDirectory();
    const launcher = path.join(directory, 'bin', 'enhanced-server');
    if (!fs.existsSync(launcher)) {
        setStatus('$(error) Kotlin: no server', `Not found: ${launcher}`);
        const choice = await vscode.window.showErrorMessage(
            `No enhanced Kotlin server at ${directory}. Run scripts/install.sh, or set kotlinLspDev.serverPath.`,
            'Open settings',
        );
        if (choice === 'Open settings') {
            await vscode.commands.executeCommand('workbench.action.openSettings', 'kotlinLspDev.serverPath');
        }
        return;
    }

    const serverOptions: ServerOptions = {
        command: launcher,
        args: ['--stdio', '--system-path', context.storageUri?.fsPath ?? path.join(os.tmpdir(), 'kotlin-lsp-dev')],
        transport: TransportKind.stdio,
        options: {
            env: { ...process.env, KOTLIN_LSP_DEV_LOG: configuration.get<string>('log') ?? 'routing' },
        },
    };

    client = new LanguageClient(
        'kotlinLspDev',
        'Kotlin (kotlin-lsp-dev)',
        serverOptions,
        clientOptions(),
    );

    registerNotifications();
    setStatus('$(sync~spin) Kotlin: starting', 'Server starting');
    try {
        await client.start();
    } catch (error) {
        output.appendLine(`[start] ${error}`);
        return;
    }
    setStatus('$(sync~spin) Kotlin: indexing', 'Importing and indexing the workspace');
}

/**
 * Attach to a server already listening on 127.0.0.1:[port], started with
 * `bin/enhanced-server --socket <port>`.
 *
 * This is not just a convenience. The server's index lives in a shared cache keyed by workspace
 * (`~/.cache/JetBrains/analyzer/workspaces/<hash>`) and is locked while a server holds it, so two
 * servers cannot serve one project at the same time regardless of `--system-path`. If you already
 * run one -- to watch its routing log, or to keep the JVM warm across editor reloads -- attaching
 * is the only way for the editor to use it.
 */
async function startAttached(port: number): Promise<void> {
    const serverOptions: ServerOptions = () =>
        new Promise<StreamInfo>((resolve, reject) => {
            const socket = net.connect({ host: '127.0.0.1', port });
            socket.once('connect', () => resolve({ reader: socket, writer: socket }));
            socket.once('error', reject);
        });

    client = new LanguageClient(
        'kotlinLspDev',
        'Kotlin (kotlin-lsp-dev)',
        serverOptions,
        clientOptions(),
    );
    registerNotifications();

    setStatus('$(plug) Kotlin: attaching', `Connecting to 127.0.0.1:${port}`);
    try {
        await client.start();
    } catch (error) {
        setStatus('$(error) Kotlin: not attached', `Nothing listening on 127.0.0.1:${port}`);
        const choice = await vscode.window.showErrorMessage(
            `Could not connect to a Kotlin server on 127.0.0.1:${port}. ` +
                `Start one with: ${path.join(serverDirectory(), 'bin', 'enhanced-server')} --socket ${port}`,
            'Copy command',
        );
        if (choice === 'Copy command') {
            await vscode.env.clipboard.writeText(
                `${path.join(serverDirectory(), 'bin', 'enhanced-server')} --socket ${port}`,
            );
        }
        return;
    }
    setStatus('$(plug) Kotlin: attached', `Attached to 127.0.0.1:${port} — indexing`);
}

/**
 * Shared client configuration.
 *
 * The error handler exists because of one specific failure. If another server already holds this
 * workspace's index lock, every start fails with "Resource temporarily unavailable" on a RocksDB
 * LOCK file -- and the default handler retries forever, burning a JVM start each time and burying
 * the cause. Restarting cannot help: the lock is held by a different process, so this stops and
 * says what to do instead.
 */
function clientOptions(): LanguageClientOptions {
    let lockConflict = false;
    const isLockConflict = (error: unknown): boolean => {
        const text = String((error as { message?: string })?.message ?? error);
        return text.includes('LOCK') || text.includes('Resource temporarily unavailable');
    };

    return {
        documentSelector: [{ scheme: 'file', language: 'kotlin' }],
        outputChannel: output,
        // The server reports import and indexing as work-done progress; without this it would be
        // silent for the tens of seconds where results are legitimately incomplete.
        progressOnInitialization: true,
        errorHandler: {
            error: (error): ErrorHandlerResult => {
                if (isLockConflict(error)) {
                    lockConflict = true;
                    reportLockConflict();
                    return { action: ErrorAction.Shutdown };
                }
                return { action: ErrorAction.Continue };
            },
            closed: (): CloseHandlerResult => {
                if (lockConflict) return { action: CloseAction.DoNotRestart };
                return { action: CloseAction.Restart };
            },
        },
    };
}

async function reportLockConflict(): Promise<void> {
    setStatus('$(error) Kotlin: index locked', 'Another server already holds this workspace index');
    const choice = await vscode.window.showErrorMessage(
        'Another Kotlin server already holds this workspace\u2019s index lock, so a second one cannot start. ' +
            'Attach to the running server instead, or stop it.',
        'Set port to attach',
    );
    if (choice === 'Set port to attach') {
        await vscode.commands.executeCommand('workbench.action.openSettings', 'kotlinLspDev.serverPort');
    }
}

/** Status and log notifications, registered the same way whichever transport is in use. */
function registerNotifications(): void {
    if (!client) return;
    client.onDidChangeState((event) => {
        if (event.newState === State.Stopped) setStatus('$(error) Kotlin: stopped', 'Server stopped');
    });
    // `intellij/ready-for-test` is the server's own "the workspace is imported and indexed"
    // signal. It matters more than it sounds: before it arrives, index-backed operations answer
    // from an incomplete index rather than failing -- a rename can come back with the declaration
    // renamed and every usage missed, with no error anywhere. Surfacing it is the difference
    // between a confusing result and an obviously-not-ready one.
    client.onNotification('intellij/ready-for-test', () => {
        setStatus('$(check) Kotlin', 'Workspace indexed \u2014 index-backed results are complete');
    });
    client.onNotification('intellij/importLog', (params: { message?: string; failed?: boolean }) => {
        output.appendLine(`[import] ${params?.message ?? ''}`);
        if (params?.failed) setStatus('$(warning) Kotlin: import failed', params.message ?? 'Import failed');
    });
}

async function restart(context: vscode.ExtensionContext): Promise<void> {
    await client?.stop();
    client = undefined;
    await start(context);
}

function setStatus(text: string, tooltip: string): void {
    status.text = text;
    status.tooltip = tooltip;
    status.show();
}

/** Runs one of the server's `workspace/executeCommand` commands. */
async function execute<T>(command: string, args: unknown[]): Promise<T | undefined> {
    if (!client || client.state !== State.Running) {
        vscode.window.showWarningMessage('The Kotlin language server is not running.');
        return undefined;
    }
    try {
        return (await client.sendRequest('workspace/executeCommand', {
            command,
            arguments: args,
        })) as T;
    } catch (error) {
        vscode.window.showErrorMessage(`${command} failed: ${error}`);
        return undefined;
    }
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

    // Rendered rather than dumped: the whole point of a health report is being readable when
    // something is wrong, and the common failure (no modules, no JDK) should be obvious at a glance.
    const lines: string[] = [];
    lines.push(`Project:  ${report.project}`);
    lines.push(`Healthy:  ${report.healthy ? 'yes' : 'NO'}`);
    lines.push(`JDK:      ${report.jdk ? `${report.jdk.name} (${report.jdk.home})` : 'NONE — symbol resolution will be broken'}`);
    lines.push(`Modules:  ${report.modules.length}`);
    if (report.modules.length === 0) {
        lines.push('  (no modules — the workspace was not imported; most features cannot work)');
    }
    for (const module of report.modules) {
        lines.push('');
        lines.push(`  ${module.name}`);
        lines.push(`    source roots (${module.sourceRoots.length}):`);
        module.sourceRoots.forEach((root) => lines.push(`      ${root}`));
        lines.push(`    classpath (${module.classpath.length} entr${module.classpath.length === 1 ? 'y' : 'ies'}):`);
        module.classpath.slice(0, 40).forEach((entry) => lines.push(`      ${entry}`));
        if (module.classpath.length > 40) {
            lines.push(`      ... and ${module.classpath.length - 40} more`);
        }
    }

    const document = await vscode.workspace.openTextDocument({
        content: lines.join('\n'),
        language: 'text',
    });
    await vscode.window.showTextDocument(document, { preview: true });
}

interface Location {
    uri: string;
    range: { start: { line: number; character: number }; end: { line: number; character: number } };
}

async function analyzeStackTrace(): Promise<void> {
    // A stack trace is multi-line, and an input box is not; the clipboard is where a trace being
    // investigated almost always already is.
    const clipboard = await vscode.env.clipboard.readText();
    const text = await vscode.window.showInputBox({
        title: 'Analyze JVM stack trace',
        prompt: 'Paste a stack trace (the clipboard is pre-filled)',
        value: clipboard.split('\n').slice(0, 40).join('\n'),
    });
    if (!text) return;

    const frames = await execute<Location[]>('kotlin-lsp.analyzeStackTrace', [text]);
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
    if (!picked) return;
    const document = await vscode.workspace.openTextDocument(vscode.Uri.parse(picked.frame.uri));
    const editor = await vscode.window.showTextDocument(document);
    const position = new vscode.Position(picked.frame.range.start.line, picked.frame.range.start.character);
    editor.selection = new vscode.Selection(position, position);
    editor.revealRange(new vscode.Range(position, position), vscode.TextEditorRevealType.InCenter);
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
        { title: `${matches.length} match(es)`, placeHolder: 'Matches are read-only — jar contents cannot be opened' },
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
