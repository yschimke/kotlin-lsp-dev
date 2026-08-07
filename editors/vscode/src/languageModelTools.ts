// Copyright 2026 kotlin-lsp-dev contributors. Use of this source code is governed by the Apache 2.0 license.

import * as vscode from 'vscode';
import type { ExecuteCommand } from './dependencyExplorer';

/**
 * Two of the server's commands, offered to chat as tools.
 *
 * Both answer questions a model cannot answer from the source alone: what the *imported* workspace
 * actually looks like, and what is inside the dependency jars. Reading the build files gives the
 * declared intent; these give the resolved result, which is the thing that disagrees with intent
 * when something is wrong.
 *
 * Only read-only commands are exposed. Nothing here edits, runs, or restarts anything -- a tool
 * call the user did not review should not be able to change their project.
 */

interface DoctorReport {
    project: string;
    jdk: { name: string; home: string } | null;
    healthy: boolean;
    modules: { name: string; sourceRoots: string[]; classpath: string[] }[];
}

interface JarMatch {
    jar: string;
    entry: string;
    line: number;
    text: string;
}

/** Enough of the classpath to be useful, capped so a big project cannot flood the context. */
const CLASSPATH_SAMPLE = 25;
const MATCH_LIMIT = 25;

function textResult(text: string): vscode.LanguageModelToolResult {
    return new vscode.LanguageModelToolResult([new vscode.LanguageModelTextPart(text)]);
}

export function registerLanguageModelTools(
    context: vscode.ExtensionContext,
    execute: ExecuteCommand,
): void {
    // Chat tools are not available in every host this extension can run in; their absence should
    // cost the other features nothing.
    if (typeof vscode.lm?.registerTool !== 'function') return;

    context.subscriptions.push(
        vscode.lm.registerTool('kotlinLspDev-doctor', {
            async invoke(): Promise<vscode.LanguageModelToolResult> {
                const report = await execute<DoctorReport>('kotlin-lsp.doctor', []);
                if (!report) {
                    return textResult(
                        'The Kotlin language server did not answer. It may not be running, or the ' +
                            'server in use may be a stock kotlin-lsp release without this command.',
                    );
                }
                const lines = [
                    `Project: ${report.project}`,
                    `Healthy: ${report.healthy ? 'yes' : 'no'}`,
                    `JDK: ${report.jdk ? `${report.jdk.name} (${report.jdk.home})` : 'none resolved'}`,
                    `Modules: ${report.modules.length}`,
                ];
                for (const module of report.modules) {
                    lines.push('');
                    lines.push(`Module ${module.name}`);
                    lines.push(`  source roots: ${module.sourceRoots.join(', ') || 'none'}`);
                    lines.push(`  classpath entries: ${module.classpath.length}`);
                    module.classpath.slice(0, CLASSPATH_SAMPLE).forEach((entry) => lines.push(`    ${entry}`));
                    if (module.classpath.length > CLASSPATH_SAMPLE) {
                        lines.push(`    ... ${module.classpath.length - CLASSPATH_SAMPLE} more not shown`);
                    }
                }
                return textResult(lines.join('\n'));
            },
        }),

        vscode.lm.registerTool<{ query: string }>('kotlinLspDev-searchDependencyJars', {
            async invoke(options): Promise<vscode.LanguageModelToolResult> {
                const query = options.input?.query?.trim();
                if (!query) return textResult('No search text was given.');

                const matches = await execute<JarMatch[]>('kotlin-lsp.findTextInDependencyJars', [query]);
                if (!matches) {
                    return textResult('The Kotlin language server did not answer the search.');
                }
                if (matches.length === 0) {
                    return textResult(`No matches for "${query}" in the dependency jars.`);
                }
                const shown = matches.slice(0, MATCH_LIMIT);
                const lines = shown.map((match) => `${match.jar} :: ${match.entry}:${match.line}: ${match.text}`);
                if (matches.length > shown.length) {
                    lines.push(`... ${matches.length - shown.length} further matches not shown`);
                }
                return textResult(lines.join('\n'));
            },
        }),
    );
}
