// Copyright 2026 kotlin-lsp-dev contributors. Use of this source code is governed by the Apache 2.0 license.
//
// The JUnit parser is the one part of the Testing integration with real logic, and the only part
// testable outside an editor -- which is why it lives in a module with no `vscode` import.
//
// Run: node --test editors/vscode/test/
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const { parseJUnitXml } = await import(path.join(here, '..', 'out', 'junit.js'));

test('scores passed, failed, errored and skipped distinctly', () => {
    const outcomes = parseJUnitXml(`
      <testsuite name="p.MyTest" tests="4">
        <testcase name="passes" classname="p.MyTest" time="0.1"/>
        <testcase name="fails" classname="p.MyTest" time="0.2">
          <failure message="expected:&lt;1&gt; but was:&lt;2&gt;">stack</failure>
        </testcase>
        <testcase name="errors" classname="p.MyTest" time="0.1">
          <error message="boom">trace</error>
        </testcase>
        <testcase name="ignored" classname="p.MyTest" time="0"><skipped/></testcase>
      </testsuite>`);
    const status = Object.fromEntries(outcomes.map((o) => [o.id, o.status]));
    assert.deepEqual(status, {
        'p.MyTest.passes': 'passed',
        'p.MyTest.fails': 'failed',
        // An <error> is a failure too; treating it as anything else shows a green tick on a crash.
        'p.MyTest.errors': 'failed',
        'p.MyTest.ignored': 'skipped',
    });
});

test('decodes entities in the assertion message', () => {
    const [outcome] = parseJUnitXml(
        `<testsuite><testcase name="t" classname="C" time="0">
           <failure message="expected:&lt;1&gt; but was:&lt;2&gt;"/>
         </testcase></testsuite>`,
    );
    // This is the most-read line of a failing run; raw entities make the panel look broken.
    assert.equal(outcome.message, 'expected:<1> but was:<2>');
});

test('ids match how the run lens names a test', () => {
    const [outcome] = parseJUnitXml(
        '<testsuite><testcase name="m" classname="pkg.Cls" time="0.25"/></testsuite>',
    );
    // The server emits pkg.Class.method; results keyed any other way cannot be matched back.
    assert.equal(outcome.id, 'pkg.Cls.m');
    assert.equal(outcome.durationMs, 250);
});

test('tolerates malformed input rather than throwing', () => {
    // This reads build output; throwing would turn a passing run into a broken panel.
    assert.deepEqual(parseJUnitXml('<testsuite><testcase name="no-class"/></testsuite>'), []);
    assert.deepEqual(parseJUnitXml('not xml at all'), []);
});

test('agrees with Gradle on a real report set', (t) => {
    const dir = path.join(here, '..', '..', '..', 'build', 'test-results', 'test');
    if (!fs.existsSync(dir)) return t.skip('no Gradle reports; run ./gradlew test first');
    let parsed = 0;
    let declared = 0;
    for (const file of fs.readdirSync(dir).filter((f) => f.endsWith('.xml'))) {
        const xml = fs.readFileSync(path.join(dir, file), 'utf8');
        parsed += parseJUnitXml(xml).length;
        declared += Number(/tests="(\d+)"/.exec(xml)?.[1] ?? 0);
    }
    assert.equal(parsed, declared);
});
