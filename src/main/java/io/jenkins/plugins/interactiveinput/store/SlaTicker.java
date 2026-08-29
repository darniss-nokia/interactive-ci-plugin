// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.store;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import io.jenkins.plugins.interactiveinput.bridge.InputStepBridge;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import io.jenkins.plugins.interactiveinput.view.ViewStore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Periodic task that expires overdue questions, compacts terminal ones (§8.3, §8.9) and reconciles
 * the opt-in input-step bridge (§8.7).
 *
 * <p>Runs on Jenkins' shared timer at a fixed 30-second cadence — the idiomatic equivalent of the
 * "single per-plugin {@code ScheduledExecutorService}" described in the spec, without spawning our
 * own thread pool.
 */
@Extension
public class SlaTicker extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(SlaTicker.class.getName());

    private static final long PERIOD_MS = TimeUnit.SECONDS.toMillis(30);

    public SlaTicker() {
        super("Interactive Input SLA ticker");
    }

    @Override
    public long getRecurrencePeriod() {
        return PERIOD_MS;
    }

    @Override
    protected void execute(TaskListener listener) {
        long now = System.currentTimeMillis();
        QuestionStore store = QuestionStore.get();
        // SLA expiry + retention is the ticker's core duty; run it first and never let the optional
        // bridge reconciliation below interfere with it.
        store.expireOverdue(now);
        long retentionMs = TimeUnit.DAYS.toMillis(InteractiveInputGlobalConfig.retentionDaysOrDefault());
        store.compact(now, retentionMs);
        // The interactive-view store shares the same SLA-expiry + retention cadence: expire overdue
        // blocking reviews and compact decided ones. Isolated in its own try so a review-store fault
        // never blocks question expiry or the bridge below.
        try {
            ViewStore views = ViewStore.get();
            views.expireOverdue(now);
            views.compact(now, retentionMs);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "interactive-view store maintenance failed", e);
        }
        try {
            InputStepBridge.get().sync();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "input-step bridge reconciliation failed", e);
        }
    }
}
