// Copyright 2026 kotlin-lsp-dev contributors. Use of this source code is governed by the Apache 2.0 license.

import assert from 'node:assert/strict';
import test from 'node:test';
import { IndexGate } from '../out/indexGate.js';

/**
 * Covers the mechanism the rename guard sits on. The prompt itself is a modal dialog and cannot be
 * tested here; what can be tested is whether waiting ever hangs, resolves twice, or leaks a waiter
 * -- the failures that would make a rename appear to freeze the editor.
 */

test('an already-complete index does not wait at all', async () => {
    const gate = new IndexGate();
    gate.markReady();
    assert.equal(gate.isReady, true);
    // A zero timeout would resolve false if this actually waited.
    assert.equal(await gate.wait(0), true);
});

test('waiting resolves when the index becomes ready', async () => {
    const gate = new IndexGate();
    const waiting = gate.wait(5_000);
    assert.equal(gate.pending, 1);
    gate.markReady();
    assert.equal(await waiting, true);
    assert.equal(gate.pending, 0, 'the waiter must not be left behind');
});

test('waiting gives up after the timeout rather than hanging', async () => {
    const gate = new IndexGate();
    assert.equal(await gate.wait(10), false);
    assert.equal(gate.pending, 0);
});

test('cancelling abandons the wait and leaves nothing behind', async () => {
    const gate = new IndexGate();
    let cancel = () => {};
    const waiting = gate.wait(5_000, (abandon) => {
        cancel = abandon;
    });
    cancel();
    assert.equal(await waiting, false);
    assert.equal(gate.pending, 0);
});

test('readiness after cancellation does not settle the wait twice', async () => {
    // Resolving twice is silent -- the second call is ignored by the promise -- so the way this
    // shows up is a stale waiter surviving into the next rename, not an error.
    const gate = new IndexGate();
    let cancel = () => {};
    const waiting = gate.wait(5_000, (abandon) => {
        cancel = abandon;
    });
    cancel();
    gate.markReady();
    assert.equal(await waiting, false, 'the first outcome wins');
    assert.equal(gate.pending, 0);
});

test('every waiter is released, not just the first', async () => {
    const gate = new IndexGate();
    const all = [gate.wait(5_000), gate.wait(5_000), gate.wait(5_000)];
    assert.equal(gate.pending, 3);
    gate.markReady();
    assert.deepEqual(await Promise.all(all), [true, true, true]);
    assert.equal(gate.pending, 0);
});

test('a restart releases waiters instead of stranding them', async () => {
    // The server they were waiting on is gone, so `intellij/ready-for-test` is never coming.
    // Leaving them pending would hang the rename until its own timeout, with no server involved.
    const gate = new IndexGate();
    const waiting = gate.wait(5_000);
    gate.reset();
    assert.equal(await waiting, false);
    assert.equal(gate.isReady, false);
    assert.equal(gate.pending, 0);
});

test('a restart makes a previously complete index count as incomplete', () => {
    const gate = new IndexGate();
    gate.markReady();
    gate.reset();
    assert.equal(gate.isReady, false, 'a rename after a restart must wait again');
});
