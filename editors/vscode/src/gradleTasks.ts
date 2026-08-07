// Copyright 2026 kotlin-lsp-dev contributors. Use of this source code is governed by the Apache 2.0 license.

import * as fs from 'node:fs';
import * as path from 'node:path';
import * as vscode from 'vscode';

/**
 * Gradle lifecycle tasks in `Run Task`, for workspaces that have a wrapper.
 *
 * These are the tasks the Gradle base and JVM plugins define for every build, so they can be
 * offered without running Gradle to ask. Discovering the *real* task list means a `gradlew tasks`
 * invocation -- a daemon start and a full configuration phase -- on every task-list refresh, which
 * is a poor trade for a list that is nearly always one of these five.
 *
 * Anything else is still reachable: `resolveTask` accepts any task name written into `tasks.json`,
 * so a custom task is one entry away without this having to enumerate it.
 */

const TASK_TYPE = 'kotlin-gradle';
const LIFECYCLE_TASKS = ['build', 'assemble', 'check', 'test', 'clean'];

interface GradleTaskDefinition extends vscode.TaskDefinition {
    task: string;
    args?: string[];
}

function wrapperIn(folder: vscode.WorkspaceFolder): string | undefined {
    const name = process.platform === 'win32' ? 'gradlew.bat' : 'gradlew';
    return fs.existsSync(path.join(folder.uri.fsPath, name)) ? name : undefined;
}

function taskFor(
    folder: vscode.WorkspaceFolder,
    wrapper: string,
    definition: GradleTaskDefinition,
): vscode.Task {
    const command = [
        process.platform === 'win32' ? wrapper : `./${wrapper}`,
        definition.task,
        ...(definition.args ?? []),
    ].join(' ');
    const task = new vscode.Task(
        definition,
        folder,
        definition.task,
        'gradle',
        new vscode.ShellExecution(command, { cwd: folder.uri.fsPath }),
    );
    // `test` and `check` produce diagnostics worth keeping; `build` is the usual default target.
    task.group = definition.task === 'test' || definition.task === 'check'
        ? vscode.TaskGroup.Test
        : definition.task === 'clean'
          ? vscode.TaskGroup.Clean
          : vscode.TaskGroup.Build;
    return task;
}

export function registerGradleTasks(context: vscode.ExtensionContext): void {
    const provider: vscode.TaskProvider = {
        provideTasks(): vscode.Task[] {
            const tasks: vscode.Task[] = [];
            for (const folder of vscode.workspace.workspaceFolders ?? []) {
                const wrapper = wrapperIn(folder);
                if (!wrapper) continue;
                for (const name of LIFECYCLE_TASKS) {
                    tasks.push(taskFor(folder, wrapper, { type: TASK_TYPE, task: name }));
                }
            }
            return tasks;
        },
        resolveTask(task: vscode.Task): vscode.Task | undefined {
            const definition = task.definition as GradleTaskDefinition;
            if (!definition.task) return undefined;
            const folder =
                task.scope !== vscode.TaskScope.Global && task.scope !== vscode.TaskScope.Workspace
                    ? (task.scope as vscode.WorkspaceFolder | undefined)
                    : vscode.workspace.workspaceFolders?.[0];
            if (!folder) return undefined;
            const wrapper = wrapperIn(folder);
            return wrapper ? taskFor(folder, wrapper, definition) : undefined;
        },
    };
    context.subscriptions.push(vscode.tasks.registerTaskProvider(TASK_TYPE, provider));
}
