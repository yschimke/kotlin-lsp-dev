// Copyright 2026 kotlin-lsp-dev contributors. Use of this source code is governed by the Apache 2.0 license.

/**
 * Tracks whether the workspace index is complete, and lets callers wait for it.
 *
 * Split out of the extension with no `vscode` import so it can be tested outside an editor -- the
 * same separation `junit.ts` has, and for the same reason. The prompt this drives cannot be tested
 * (it is a modal dialog), but the part underneath it can: a settled/cleanup race here would either
 * hang a rename forever or resolve it twice, and neither is visible by reading the code.
 */
export class IndexGate {
    private ready = false;
    /** Callbacks for in-flight waiters, each settling exactly one `wait()`. */
    private readonly waiters = new Set<(complete: boolean) => void>();

    get isReady(): boolean {
        return this.ready;
    }

    /** The server reported the index complete. Releases everyone waiting. */
    markReady(): void {
        this.ready = true;
        this.settleAll(true);
    }

    /**
     * The server is starting over, so a previously complete index no longer says anything.
     *
     * Waiters are released as *not* ready rather than left pending: their server is gone, so the
     * signal they are waiting for is never going to arrive on it.
     */
    reset(): void {
        this.ready = false;
        this.settleAll(false);
    }

    /**
     * Resolves `true` when the index is complete, `false` on timeout or cancellation.
     *
     * `onCancel` receives a function that abandons the wait; callers hand it to whatever cancels
     * (a progress notification's token, say) so cancelling does not leave a waiter behind.
     */
    wait(timeoutMs: number, onCancel?: (cancel: () => void) => void): Promise<boolean> {
        if (this.ready) return Promise.resolve(true);

        return new Promise<boolean>((resolve) => {
            let timer: ReturnType<typeof setTimeout> | undefined;
            let settled = false;
            const finish = (complete: boolean): void => {
                // Every path here can race every other: readiness can arrive as the timeout fires,
                // and cancellation can arrive after either. First one wins, the rest are no-ops.
                if (settled) return;
                settled = true;
                if (timer !== undefined) clearTimeout(timer);
                this.waiters.delete(finish);
                resolve(complete);
            };

            this.waiters.add(finish);
            timer = setTimeout(() => finish(false), timeoutMs);
            onCancel?.(() => finish(false));
        });
    }

    /** Number of waiters still pending. Exposed so tests can prove none are leaked. */
    get pending(): number {
        return this.waiters.size;
    }

    private settleAll(complete: boolean): void {
        // Copied first: each callback removes itself from the set as it settles.
        for (const settle of [...this.waiters]) settle(complete);
        this.waiters.clear();
    }
}
