// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.view;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Serializable;
import net.sf.json.JSONObject;

/**
 * Metadata for one persisted version of a {@link ReviewDocument}'s content.
 *
 * <p>The version <em>bytes</em> are stored as a separate file under
 * {@code $JENKINS_HOME/interactive-input/views/<docId>/v<index>.txt} (so the metadata XML stays
 * small); this record only carries who/when produced the version. Version 1 is the immutable original
 * snapshot taken at step time; later versions are reviewer edits of the durable review copy (the
 * original workspace file is never modified).
 */
public class ContentVersion implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int index;

    @NonNull
    private final String editedBy;

    private final long editedTs;

    @NonNull
    private final String note;

    public ContentVersion(int index, @NonNull String editedBy, long editedTs, @NonNull String note) {
        this.index = index;
        this.editedBy = editedBy;
        this.editedTs = editedTs;
        this.note = note;
    }

    public int getIndex() {
        return index;
    }

    @NonNull
    public String getEditedBy() {
        return editedBy;
    }

    public long getEditedTs() {
        return editedTs;
    }

    @NonNull
    public String getNote() {
        return note;
    }

    @NonNull
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("index", index);
        o.put("editedBy", editedBy);
        o.put("editedTs", editedTs);
        o.put("note", note);
        return o;
    }
}
