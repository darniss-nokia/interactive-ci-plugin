// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * B20: the bell polling cadence, default SLA and retention — bindable via JCasC / Script Console but
 * previously absent from the UI — must now render on <em>Manage Jenkins → System</em> and round-trip
 * through a form submit, so operators are not left with settable config that has no UI.
 */
@WithJenkins
class SystemConfigFormTest {

    @Test
    void systemFormRendersPollingSlaAndRetentionFields(JenkinsRule j) throws Exception {
        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(false); // pure server render: are the fields emitted?
            wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
            HtmlPage cfg = wc.goTo("manage/configure");
            String html = cfg.asXml();
            assertTrue(html.contains("Bell poll interval (seconds)"), "polling field must render on the System form");
            assertTrue(html.contains("Default SLA (minutes)"), "SLA field must render on the System form");
            assertTrue(html.contains("Retention (days)"), "retention field must render on the System form");
        }
    }

    @Test
    void pollingSlaAndRetentionSurviveAConfigSubmit(JenkinsRule j) throws Exception {
        InteractiveInputGlobalConfig cfg = InteractiveInputGlobalConfig.get();
        Polling polling = new Polling();
        polling.setIntervalSeconds(30);
        cfg.setPolling(polling);
        Sla sla = new Sla();
        sla.setDefaultMinutes(45);
        cfg.setSla(sla);
        cfg.setRetentionDays(3);

        j.configRoundtrip(); // submits the System form the way the UI does

        InteractiveInputGlobalConfig after = InteractiveInputGlobalConfig.get();
        assertEquals(30, after.getPolling().getIntervalSeconds(), "polling interval must survive a System save");
        assertEquals(45, after.getSla().getDefaultMinutes(), "default SLA must survive a System save");
        assertEquals(3, after.getRetentionDays(), "retention must survive a System save");
    }
}
