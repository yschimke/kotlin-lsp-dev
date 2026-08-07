// Copyright 2026 kotlin-lsp-dev contributors. Use of this source code is governed by the Apache 2.0 license.

import * as path from 'node:path';
import * as vscode from 'vscode';

/**
 * The Kotlin project structure as a tree: modules, their source roots, and their dependencies down
 * to individual classes.
 *
 * Every part is already answered by something that exists -- `kotlin-lsp.doctor` knows the modules
 * and their classpath, `kotlin-lsp.listJarClasses` walks a jar one level at a time, and the
 * decompiler content provider already turns a `jar:` URI into readable source. This joins them so
 * "what is actually on my classpath, and what is inside it" is a place you can look rather than a
 * command whose output scrolls past.
 *
 * Expansion is lazy at every level. A dependency set of a few hundred jars holds hundreds of
 * thousands of classes, so listing eagerly would spend most of its time on nodes nobody opens.
 */

export type ExecuteCommand = <T>(command: string, args: unknown[]) => Promise<T | undefined>;

interface DoctorReport {
    project: string;
    modules: { name: string; sourceRoots: string[]; classpath: string[] }[];
}

interface JarListing {
    packages: string[];
    classes: { name: string; entry: string }[];
    truncated: boolean;
}

type Node =
    | { kind: 'module'; name: string; sourceRoots: string[]; classpath: string[] }
    | { kind: 'group'; label: string; entries: string[]; icon: string }
    | { kind: 'root'; url: string }
    | { kind: 'jar'; url: string }
    | { kind: 'package'; jarUrl: string; name: string }
    | { kind: 'class'; jarUrl: string; name: string; entry: string }
    | { kind: 'note'; message: string };

/** `jar:///home/me/.m2/foo.jar!/` -> `/home/me/.m2/foo.jar`, which is what the server wants. */
function jarPathOf(url: string): string {
    return vscode.Uri.parse(url.replace(/^jar:/, 'file:').replace(/!\/$/, '')).fsPath;
}

/** A class inside a jar, as a URI the decompiling content provider can serve. */
function entryUri(jarUrl: string, entry: string): vscode.Uri {
    return vscode.Uri.parse(jarUrl.endsWith('!/') ? jarUrl + entry : `${jarUrl}!/${entry}`);
}

function isJar(url: string): boolean {
    return url.startsWith('jar:');
}

function labelOf(url: string): string {
    const withoutArchive = url.replace(/!\/$/, '');
    return path.basename(vscode.Uri.parse(withoutArchive.replace(/^jar:/, 'file:')).fsPath);
}

class DependencyTreeProvider implements vscode.TreeDataProvider<Node> {
    private readonly changed = new vscode.EventEmitter<Node | undefined>();
    readonly onDidChangeTreeData = this.changed.event;

    constructor(private readonly execute: ExecuteCommand) {}

    refresh(): void {
        this.changed.fire(undefined);
    }

    getTreeItem(node: Node): vscode.TreeItem {
        const { Collapsed, None } = vscode.TreeItemCollapsibleState;
        switch (node.kind) {
            case 'module': {
                const item = new vscode.TreeItem(node.name, Collapsed);
                item.iconPath = new vscode.ThemeIcon('project');
                item.contextValue = 'kotlinModule';
                item.description = `${node.classpath.length} classpath entries`;
                return item;
            }
            case 'group': {
                const item = new vscode.TreeItem(node.label, Collapsed);
                item.iconPath = new vscode.ThemeIcon(node.icon);
                item.description = `${node.entries.length}`;
                return item;
            }
            case 'root': {
                const uri = vscode.Uri.parse(node.url);
                const item = new vscode.TreeItem(uri, None);
                item.label = vscode.workspace.asRelativePath(uri);
                item.iconPath = new vscode.ThemeIcon('folder');
                item.command = {
                    title: 'Reveal in Explorer',
                    command: 'revealInExplorer',
                    arguments: [uri],
                };
                return item;
            }
            case 'jar': {
                const item = new vscode.TreeItem(labelOf(node.url), Collapsed);
                item.iconPath = new vscode.ThemeIcon('file-zip');
                item.tooltip = jarPathOf(node.url);
                item.contextValue = 'kotlinJar';
                return item;
            }
            case 'package': {
                const item = new vscode.TreeItem(node.name, Collapsed);
                item.iconPath = new vscode.ThemeIcon('symbol-namespace');
                return item;
            }
            case 'class': {
                const item = new vscode.TreeItem(node.name, None);
                item.iconPath = new vscode.ThemeIcon('symbol-class');
                item.command = {
                    title: 'Open decompiled source',
                    command: 'kotlinLspDev.openJarEntry',
                    arguments: [entryUri(node.jarUrl, node.entry).toString()],
                };
                return item;
            }
            case 'note': {
                const item = new vscode.TreeItem(node.message, None);
                item.iconPath = new vscode.ThemeIcon('info');
                return item;
            }
        }
    }

    async getChildren(node?: Node): Promise<Node[]> {
        if (!node) return this.modules();
        switch (node.kind) {
            case 'module':
                return [
                    { kind: 'group', label: 'Source roots', entries: node.sourceRoots, icon: 'folder-opened' },
                    { kind: 'group', label: 'Dependencies', entries: node.classpath, icon: 'library' },
                ].filter((group) => group.entries.length > 0) as Node[];
            case 'group':
                return node.entries.map((url) =>
                    isJar(url) ? ({ kind: 'jar', url } as Node) : ({ kind: 'root', url } as Node),
                );
            case 'jar':
                return this.jarLevel(node.url, '');
            case 'package':
                return this.jarLevel(node.jarUrl, node.name);
            default:
                return [];
        }
    }

    private async modules(): Promise<Node[]> {
        const report = await this.execute<DoctorReport>('kotlin-lsp.doctor', []);
        if (!report) return [];
        if (report.modules.length === 0) {
            return [{ kind: 'note', message: 'No modules — the workspace has not been imported yet.' }];
        }
        return report.modules.map((module) => ({ kind: 'module', ...module }));
    }

    private async jarLevel(jarUrl: string, packageName: string): Promise<Node[]> {
        const listing = await this.execute<JarListing>('kotlin-lsp.listJarClasses', [
            jarPathOf(jarUrl),
            packageName,
        ]);
        if (!listing) return [];

        const qualify = (name: string) => (packageName ? `${packageName}.${name}` : name);
        const nodes: Node[] = listing.packages.map((name) => ({
            kind: 'package',
            jarUrl,
            name: qualify(name),
        }));
        for (const entry of listing.classes) {
            nodes.push({ kind: 'class', jarUrl, name: entry.name, entry: entry.entry });
        }
        // The server caps a single package; saying so beats a list that looks complete.
        if (listing.truncated) {
            nodes.push({ kind: 'note', message: 'More classes not shown (listing was truncated).' });
        }
        if (nodes.length === 0) {
            nodes.push({ kind: 'note', message: 'No classes in this jar.' });
        }
        return nodes;
    }
}

/**
 * Opens a Kotlin source file, unshown, if the server has not seen one yet.
 *
 * Decompiling a **Kotlin** class fails on `263.2689.0` when no Kotlin document has been opened in
 * the session -- the server answers `no stub serializer for kotlin.PACKAGE_DIRECTIVE` rather than
 * source. Verified directly: cold, 0 of 8 `kotlin.collections` classes decompiled; with one Kotlin
 * document open, 8 of 8 did, from byte-identical URIs. Java classes are unaffected.
 *
 * The tree is the one place that hits this, because it is the only way to reach a library class
 * without having navigated from Kotlin source first. Opening a document the user never sees is a
 * small price for the difference between source and an error comment.
 */
async function warmKotlinAnalysis(): Promise<void> {
    if (vscode.workspace.textDocuments.some((document) => document.languageId === 'kotlin')) return;
    const [source] = await vscode.workspace.findFiles('**/*.kt', '**/build/**', 1);
    if (source) await vscode.workspace.openTextDocument(source);
}

export function registerDependencyExplorer(
    context: vscode.ExtensionContext,
    execute: ExecuteCommand,
): { refresh: () => void } {
    const provider = new DependencyTreeProvider(execute);
    context.subscriptions.push(
        vscode.window.registerTreeDataProvider('kotlinLspDev.dependencies', provider),
        vscode.commands.registerCommand('kotlinLspDev.refreshDependencies', () => provider.refresh()),
        vscode.commands.registerCommand('kotlinLspDev.openJarEntry', async (uri: string) => {
            await warmKotlinAnalysis();
            const document = await vscode.workspace.openTextDocument(vscode.Uri.parse(uri));
            await vscode.window.showTextDocument(document, { preview: true });
        }),
    );
    return { refresh: () => provider.refresh() };
}
