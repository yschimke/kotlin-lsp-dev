// Copyright 2026 kotlin-lsp-dev contributors. Use of this source code is governed by the Apache 2.0 license.

/**
 * Gradle's JUnit XML, parsed.
 *
 * Deliberately free of any `vscode` import: this is the one part of the Testing integration with
 * real logic, and keeping it a pure function is what makes it testable outside an editor -- the
 * same reason every overlay feature keeps its computation free of LSP types.
 */

/** One `<testcase>` outcome, keyed the same way the run lens ids tests. */
export interface TestOutcome {
    id: string;
    status: 'passed' | 'failed' | 'skipped';
    durationMs: number;
    message?: string;
}

/**
 * Parses Gradle's JUnit XML.
 *
 * Deliberately dependency-free and tolerant: this reads build output, and a parser that throws on
 * an unexpected attribute would turn a passing test run into a broken panel.
 */
export function parseJUnitXml(xml: string): TestOutcome[] {
    const outcomes: TestOutcome[] = [];
    // Match a testcase element with either a self-closing tag or a body carrying the failure.
    const testcase = /<testcase\b([^>]*?)(\/>|>([\s\S]*?)<\/testcase>)/g;
    let match: RegExpExecArray | null;
    while ((match = testcase.exec(xml)) !== null) {
        const attributes = match[1];
        const body = match[3] ?? '';
        const name = attribute(attributes, 'name');
        const className = attribute(attributes, 'classname');
        if (!name || !className) continue;

        let status: TestOutcome['status'] = 'passed';
        let message: string | undefined;
        if (/<failure\b|<error\b/.test(body)) {
            status = 'failed';
            message =
                attribute(body, 'message') ?? (decodeEntities(body.trim()).slice(0, 2000) || 'Test failed');
        } else if (/<skipped\b/.test(body)) {
            status = 'skipped';
        }
        outcomes.push({
            id: `${className}.${name}`,
            status,
            durationMs: Math.round(Number(attribute(attributes, 'time') ?? '0') * 1000),
            message,
        });
    }
    return outcomes;
}

function attribute(source: string, name: string): string | undefined {
    const match = new RegExp(`${name}="([^"]*)"`).exec(source);
    return match?.[1] === undefined ? undefined : decodeEntities(match[1]);
}

/**
 * XML entities back to text.
 *
 * Assertion messages are full of them -- `expected:&lt;1&gt; but was:&lt;2&gt;` is the single most
 * read line in a failing run, and showing it raw makes the panel look broken at precisely the
 * moment the reader is already frustrated.
 */
function decodeEntities(text: string): string {
    return text
        .replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>')
        .replace(/&quot;/g, '"')
        .replace(/&apos;/g, "'")
        .replace(/&#10;/g, '\n')
        .replace(/&#13;/g, '\r')
        .replace(/&#9;/g, '\t')
        // Ampersand last, or the decodes above would re-decode text that was literally "&lt;".
        .replace(/&amp;/g, '&');
}
