package com.fileverifier;

import java.util.function.Consumer;

/** One outstanding EDT drain per view, including across replaced job generations. */
final class UiDrainScheduler {
    private final Consumer<Runnable> edt;
    private Object current;
    private Runnable pending;
    private boolean scheduled;

    UiDrainScheduler(Consumer<Runnable> edt) { this.edt = edt; }

    synchronized void activate(Object identity) {
        current = identity;
        pending = null; // a queued old-job drain is no longer useful
    }

    synchronized void submit(Object identity, Runnable task) {
        if (identity != current) return;
        pending = task;
        if (!scheduled) {
            scheduled = true;
            edt.accept(this::drain);
        }
    }

    private void drain() {
        Runnable task;
        synchronized (this) {
            task = pending;
            pending = null;
        }
        try {
            if (task != null) task.run();
        } finally {
            synchronized (this) {
                if (pending == null) scheduled = false;
                else edt.accept(this::drain);
            }
        }
    }
}
