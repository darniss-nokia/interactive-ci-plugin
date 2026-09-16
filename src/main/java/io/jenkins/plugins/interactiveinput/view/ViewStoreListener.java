// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.view;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;

/**
 * Extension point fired by {@link ViewStore} when a review document is published.
 *
 * <p>Implementations must be non-blocking and must not throw; the store isolates each listener.
 */
public abstract class ViewStoreListener implements ExtensionPoint {

    /** A new review has been published. */
    public void onSubmitted(@NonNull ReviewDocument doc) {}

    @NonNull
    public static ExtensionList<ViewStoreListener> all() {
        return ExtensionList.lookup(ViewStoreListener.class);
    }
}
