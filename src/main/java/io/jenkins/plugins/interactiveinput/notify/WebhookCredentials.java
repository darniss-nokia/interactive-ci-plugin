// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.notify;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import java.util.Collections;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

/**
 * Secret-text credential lookup and form fill for Slack/Teams webhook URLs. The fill method always
 * checks {@link Item#CONFIGURE} (or {@link Jenkins#ADMINISTER} when there is no item) before listing
 * credentials — required by the credentials consumer guide and by Stapler permission scans.
 */
public final class WebhookCredentials {

    private WebhookCredentials() {}

    /**
     * Populate the webhook credentials dropdown. Empty when the caller cannot configure the item.
     *
     * @param item the job being configured, or {@code null} on a global form
     * @param current the currently selected credentials id (kept visible via includeCurrentValue);
     *     {@code null} is treated as empty because {@code includeCurrentValue} is {@code @NonNull}
     */
    @NonNull
    public static ListBoxModel listBox(@CheckForNull Item item, @CheckForNull String current) {
        // AbstractIdCredentialsListBoxModel.includeCurrentValue is @NonNull; empty string is the
        // same as null for that method (it uses StringUtils.isEmpty).
        String selected = current != null ? current : "";
        StandardListBoxModel result = new StandardListBoxModel();
        if (item == null) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return result.includeCurrentValue(selected);
            }
            return result.includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            Jenkins.get(),
                            StringCredentials.class,
                            Collections.emptyList(),
                            CredentialsMatchers.instanceOf(StringCredentials.class))
                    .includeCurrentValue(selected);
        }
        if (!item.hasPermission(Item.CONFIGURE)) {
            return result.includeCurrentValue(selected);
        }
        return result.includeEmptyValue()
                .includeMatchingAs(
                        ACL.SYSTEM2,
                        item,
                        StringCredentials.class,
                        Collections.emptyList(),
                        CredentialsMatchers.instanceOf(StringCredentials.class))
                .includeCurrentValue(selected);
    }

    /**
     * Resolve a Secret-text credential in the job's context. Caller must already be SYSTEM or
     * otherwise authorised; the dispatcher wraps workers in {@link ACL#SYSTEM2}.
     *
     * @return the secret string, or {@code null} if missing
     */
    @CheckForNull
    public static String secretText(@CheckForNull Item item, @CheckForNull String credentialsId) {
        if (credentialsId == null || credentialsId.isBlank()) {
            return null;
        }
        StringCredentials cred = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItem(
                        StringCredentials.class, item, ACL.SYSTEM2, Collections.emptyList()),
                CredentialsMatchers.withId(credentialsId.trim()));
        if (cred == null) {
            return null;
        }
        // StringCredentials.getSecret() is @NonNull.
        return cred.getSecret().getPlainText();
    }
}
