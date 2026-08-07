// Copyright 2026 kotlin-lsp-dev contributors. Use of this source code is governed by the Apache 2.0 license.

import * as fs from 'node:fs';
import * as path from 'node:path';
import * as vscode from 'vscode';
import { parseJUnitXml, TestOutcome } from './junit';

/**
 * Kotlin tests in VS Code's Testing panel.
 *
 * Discovery reuses what the server already publishes: the code-vision "▶ Run test" lens carries a
 * runnable id (`pkg.Class.method`) and the range of the test's name. Asking the editor for code
 * lenses is therefore a complete test discovery for a file, with no new server API and over a path
 * the smoke suite already covers.
 *
 * Results come from Gradle's JUnit XML rather than the exit code. A single run of several tests
 * has one exit code, so scoring from it would mark every selected test failed when one failed --
 * the panel would point at the wrong test, which is worse than not reporting at all.
 */

const TEST_FILE_GLOB = '**/*{Test,Tests,Spec}.kt';

export function registerTestExplorer(
    context: vscode.ExtensionContext,
    output: vscode.OutputChannel,
): void {
    const controller = vscode.tests.createTestController('kotlinLspDev.tests', 'Kotlin');
    context.subscriptions.push(controller);

    /** Test items keyed by run id, so results can be matched back to the tree. */
    const byId = new Map<string, vscode.TestItem>();

    const discoverFile = async (file: vscode.TestItem): Promise<void> => {
        if (!file.uri) return;
        const lenses =
            (await vscode.commands.executeCommand<vscode.CodeLens[]>(
                'vscode.executeCodeLensProvider',
                file.uri,
            )) ?? [];
        file.children.replace([]);
        for (const lens of lenses) {
            const command = lens.command;
            if (command?.command !== 'kotlinLspDev.runTest') continue;
            const id = command.arguments?.[1] as string | undefined;
            if (!id) continue;
            const item = controller.createTestItem(id, id.split('.').pop() ?? id, file.uri);
            item.range = lens.range;
            file.children.add(item);
            byId.set(id, item);
        }
        // A file with no tests should not sit in the tree pretending otherwise.
        if (file.children.size === 0) controller.items.delete(file.id);
    };

    const addFile = (uri: vscode.Uri): vscode.TestItem => {
        const existing = controller.items.get(uri.toString());
        if (existing) return existing;
        const item = controller.createTestItem(
            uri.toString(),
            vscode.workspace.asRelativePath(uri),
            uri,
        );
        item.canResolveChildren = true;
        controller.items.add(item);
        return item;
    };

    controller.resolveHandler = async (item) => {
        if (item) {
            await discoverFile(item);
            return;
        }
        for (const uri of await vscode.workspace.findFiles(TEST_FILE_GLOB, '**/build/**')) {
            addFile(uri);
        }
    };

    controller.refreshHandler = async () => {
        controller.items.replace([]);
        byId.clear();
        for (const uri of await vscode.workspace.findFiles(TEST_FILE_GLOB, '**/build/**')) {
            addFile(uri);
        }
    };

    // Keep a saved file's tests current without a manual refresh.
    context.subscriptions.push(
        vscode.workspace.onDidSaveTextDocument(async (document) => {
            if (document.languageId !== 'kotlin') return;
            const item = controller.items.get(document.uri.toString());
            if (item) await discoverFile(item);
        }),
    );

    controller.createRunProfile(
        'Run',
        vscode.TestRunProfileKind.Run,
        (request, token) => runTests(controller, byId, request, token, output, false),
        true,
    );
    controller.createRunProfile(
        'Debug',
        vscode.TestRunProfileKind.Debug,
        (request, token) => runTests(controller, byId, request, token, output, true),
        false,
    );
}

/** Gradle's `--debug-jvm` suspends the test JVM here and waits for a debugger. */
const GRADLE_DEBUG_PORT = 5005;

/**
 * Attaches the debugger to the suspended test JVM.
 *
 * `--debug-jvm` suspends *before* the tests run, so the attach has to happen while the Gradle task
 * is still going -- waiting for the task to end would deadlock, since it cannot end until something
 * attaches. The port is not open the instant the task starts either, so this retries briefly.
 */
async function attachDebugger(
    folder: vscode.WorkspaceFolder,
    token: vscode.CancellationToken,
    output: vscode.OutputChannel,
): Promise<void> {
    for (let attempt = 0; attempt < 30 && !token.isCancellationRequested; attempt++) {
        await new Promise((resolve) => setTimeout(resolve, 500));
        const started = await vscode.debug.startDebugging(folder, {
            type: 'kotlinLspDev',
            request: 'attach',
            name: 'Kotlin: Debug test',
            hostName: 'localhost',
            port: GRADLE_DEBUG_PORT,
        });
        if (started) {
            output.appendLine(`[test] debugger attached on port ${GRADLE_DEBUG_PORT}`);
            return;
        }
    }
    output.appendLine(`[test] could not attach a debugger on port ${GRADLE_DEBUG_PORT}`);
}

async function runTests(
    controller: vscode.TestController,
    byId: Map<string, vscode.TestItem>,
    request: vscode.TestRunRequest,
    token: vscode.CancellationToken,
    output: vscode.OutputChannel,
    debug: boolean,
): Promise<void> {
    const run = controller.createTestRun(request);

    const queue: vscode.TestItem[] = [];
    const collect = (item: vscode.TestItem) => {
        if (item.children.size > 0) {
            item.children.forEach(collect);
        } else {
            queue.push(item);
        }
    };
    if (request.include) {
        request.include.forEach(collect);
    } else {
        controller.items.forEach(collect);
    }
    const selected = queue.filter((item) => !request.exclude?.includes(item));
    if (selected.length === 0) {
        run.end();
        return;
    }
    selected.forEach((item) => run.enqueued(item));

    const folder =
        (selected[0].uri && vscode.workspace.getWorkspaceFolder(selected[0].uri)) ??
        vscode.workspace.workspaceFolders?.[0];
    if (!folder) {
        selected.forEach((item) => run.errored(item, new vscode.TestMessage('No workspace folder.')));
        run.end();
        return;
    }

    const wrapperName = process.platform === 'win32' ? 'gradlew.bat' : 'gradlew';
    if (!fs.existsSync(path.join(folder.uri.fsPath, wrapperName))) {
        selected.forEach((item) =>
            run.errored(item, new vscode.TestMessage(`No Gradle wrapper in ${folder.name}.`)),
        );
        run.end();
        return;
    }

    const startedAt = Date.now();
    selected.forEach((item) => run.started(item));

    const filters = selected.map((item) => `--tests "${item.id}"`).join(' ');
    const command =
        `${process.platform === 'win32' ? wrapperName : `./${wrapperName}`} test ${filters}` +
        (debug ? ' --debug-jvm' : '');
    output.appendLine(`[test] ${command}`);

    const execution = await vscode.tasks.executeTask(
        new vscode.Task(
            { type: 'kotlinLspDev.test' },
            folder,
            `Kotlin tests (${selected.length})`,
            'kotlin-lsp-dev',
            new vscode.ShellExecution(command, { cwd: folder.uri.fsPath }),
        ),
    );

    // Deliberately not awaited: the JVM is suspended waiting for a debugger, so the task cannot
    // finish until this attaches, and awaiting it here would deadlock the run.
    if (debug) void attachDebugger(folder, token, output);

    await new Promise<void>((resolve) => {
        const ended = vscode.tasks.onDidEndTaskProcess((event) => {
            if (event.execution !== execution) return;
            ended.dispose();
            resolve();
        });
        token.onCancellationRequested(() => {
            execution.terminate();
            ended.dispose();
            resolve();
        });
    });

    // Results come from the XML, not the exit code: one run covering several tests has a single
    // exit code, and scoring from it would blame every selected test for one failure.
    const outcomes = await readOutcomes(folder.uri.fsPath, startedAt);
    for (const item of selected) {
        const outcome = outcomes.get(item.id);
        if (!outcome) {
            run.skipped(item);
            continue;
        }
        if (outcome.status === 'passed') run.passed(item, outcome.durationMs);
        else if (outcome.status === 'skipped') run.skipped(item);
        else run.failed(item, new vscode.TestMessage(outcome.message ?? 'Test failed'), outcome.durationMs);
    }
    void byId;
    run.end();
}

/** Reads every JUnit report written since the run started. */
async function readOutcomes(root: string, since: number): Promise<Map<string, TestOutcome>> {
    const outcomes = new Map<string, TestOutcome>();
    const reports = await vscode.workspace.findFiles(
        new vscode.RelativePattern(root, '**/build/test-results/**/TEST-*.xml'),
    );
    for (const report of reports) {
        let stat: fs.Stats;
        try {
            stat = await fs.promises.stat(report.fsPath);
        } catch {
            continue;
        }
        // Stale reports from an earlier run would otherwise report yesterday's outcome as today's.
        if (stat.mtimeMs < since) continue;
        try {
            for (const outcome of parseJUnitXml(await fs.promises.readFile(report.fsPath, 'utf8'))) {
                outcomes.set(outcome.id, outcome);
            }
        } catch {
            // A malformed report is not worth failing the run over.
        }
    }
    return outcomes;
}
