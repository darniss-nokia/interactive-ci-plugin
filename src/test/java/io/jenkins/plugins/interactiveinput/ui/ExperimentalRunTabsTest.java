// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputAppearanceConfig;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputRunPageAlertJobProperty;
import io.jenkins.plugins.interactiveinput.model.Choice;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.output.InteractiveOutputBuildAction;
import io.jenkins.plugins.interactiveinput.output.InteractiveOutputRunTab;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import io.jenkins.plugins.interactiveinput.view.ReviewDocument;
import io.jenkins.plugins.interactiveinput.view.ViewStore;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import jenkins.model.Tab;
import jenkins.model.experimentalflags.UserExperimentalFlagsProperty;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Experimental-layout native cards (no "Legacy"). Core's {@code jenkins/run/OverviewTab/index.jelly}
 * renders every action's {@code summary.jelly} inside a hardcoded "Legacy" card, and only iterates
 * {@code Run#getRunTabs()} (non-null-icon {@link Tab}s) for native overview cards. So we render our
 * build content as {@link InteractiveOutputRunTab} / {@link InteractiveInputRunTab} in the experimental
 * layout and suppress the classic {@code summary.jelly} there — while leaving the classic layout
 * untouched. The switch is the per-user experimental flag, read via {@link ExperimentalLayout}.
 *
 * <p>Layout is driven per user by a {@link UserExperimentalFlagsProperty}; each assertion block runs
 * under {@link ACL#as2} for a user with (or without) the flag, exactly as core resolves it at render.
 */
@WithJenkins
class ExperimentalRunTabsTest {

    private static final String NEW_BUILD_PAGE = "new-build-page.flag";
    private static final String NEW_JOB_PAGE = "new-job-page.flag";

    /**
     * A security realm is required so {@link User#impersonate2()} can resolve the per-user flag owners.
     * Authorization is left at the JenkinsRule default (UNSECURED), so the impersonated user still passes
     * the store's permission checks — we are exercising layout detection, not authorization.
     */
    private static void withUsers(JenkinsRule j) {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
    }

    private static User user(String id, Map<String, String> flags) throws Exception {
        User u = User.getById(id, true);
        u.addProperty(new UserExperimentalFlagsProperty(flags));
        return u;
    }

    private static boolean hasTab(Run<?, ?> run, Class<? extends Tab> type) {
        return run.getRunTabs().stream().anyMatch(type::isInstance);
    }

    /**
     * Hitting a run-tab URL must still land on the canonical action page. Guards the {@code @GET} +
     * {@code Item.READ} scan fixes against breaking tab clicks.
     */
    private static void assertTabClickRedirects(JenkinsRule j, Run<?, ?> run, String tabUrl, String canonicalUrl)
            throws Exception {
        try (JenkinsRule.WebClient wc = j.createWebClient().login("tabClick")) {
            HtmlPage dest = wc.goTo(run.getUrl() + tabUrl + "/");
            String path = dest.getUrl().getPath();
            assertTrue(
                    path.contains("/" + canonicalUrl + "/"),
                    "tab click must land on /" + canonicalUrl + "/, got " + path);
            assertFalse(path.contains("/" + tabUrl + "/"), "must leave the overview route, still on " + path);
        }
    }

    /** A minimal review document for exercising the overview card's grouping/heading logic. */
    private static ReviewDocument review(
            String id, String job, int build, String reportName, String title, String fileName, String groupId) {
        return new ReviewDocument(
                id,
                job,
                build,
                reportName,
                title,
                fileName,
                ReviewDocument.FORMAT_MARKDOWN,
                "markdown",
                "tester",
                System.currentTimeMillis(),
                true, // commentable
                false, // editable
                false, // notify
                false, // blocking
                null, // submitterFilter
                0L, // slaMs
                groupId,
                ReviewDocument.MODE_REVIEW);
    }

    @Test
    void outputCardIsNativeInExperimentalAndClassicSummaryFlips(JenkinsRule j) throws Exception {
        withUsers(j);
        WorkflowJob p = j.createProject(WorkflowJob.class, "ojob");
        p.setDefinition(new CpsFlowDefinition(
                "interactiveOutput(reportName: 'Cost', chartType: 'bar', metrics: [[label:'C', value:'1', key:'c']])",
                true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b));

        InteractiveOutputBuildAction action = b.getAction(InteractiveOutputBuildAction.class);
        assertNotNull(action, "the output build action is present");
        InteractiveOutputRunTab tab = b.getAction(InteractiveOutputRunTab.class);
        assertNotNull(tab, "the native tab is attached whenever the output is visible (layout-independent)");
        // Distinct URL so the tab's own route never collides with the canonical action route.
        assertEquals("interactive-output-overview", tab.getUrlName());
        assertEquals("interactive-output", action.getUrlName());
        assertTabClickRedirects(j, b, InteractiveOutputRunTab.URL_NAME, InteractiveOutputBuildAction.URL_NAME);

        // Classic viewer: no native tab, the classic summary row renders, action stays reachable via icon.
        try (ACLContext ignored = ACL.as2(user("outClassic", Map.of()).impersonate2())) {
            assertFalse(hasTab(b, InteractiveOutputRunTab.class), "no native run tab in the classic layout");
            assertNull(tab.getIconFileName(), "the tab is hidden (null icon) in the classic layout");
            assertTrue(action.isClassicSummaryVisible(), "the classic summary row renders in the classic layout");
            assertNotNull(action.getIconFileName(), "the action stays reachable (sidebar/overflow) in classic");
        }

        // Experimental viewer: native tab present, classic summary suppressed (nothing lands in "Legacy").
        try (ACLContext ignored =
                ACL.as2(user("outExp", Map.of(NEW_BUILD_PAGE, "true")).impersonate2())) {
            assertTrue(
                    hasTab(b, InteractiveOutputRunTab.class), "the native run tab renders in the experimental layout");
            assertNotNull(tab.getIconFileName(), "the tab is visible in the experimental layout");
            assertFalse(action.isClassicSummaryVisible(), "the classic summary is suppressed (no Legacy duplicate)");
            assertNotNull(action.getIconFileName(), "the action is still reachable via the overflow menu");
        }

        // Appearance toggle hides the Output card in both layouts (experimental card and classic summary),
        // while the dedicated Interactive Output page stays reachable via the action's own icon.
        InteractiveInputAppearanceConfig appearance = InteractiveInputAppearanceConfig.get();
        assertNotNull(appearance);
        appearance.setOutputBuildCard(false);
        try (ACLContext ignored = ACL.as2(user("outClassicOff", Map.of()).impersonate2())) {
            assertFalse(
                    action.isClassicSummaryVisible(), "the classic summary is hidden when the Output toggle is off");
            assertNotNull(action.getIconFileName(), "the output action stays reachable when the card is off");
        }
        try (ACLContext ignored =
                ACL.as2(user("outExpOff", Map.of(NEW_BUILD_PAGE, "true")).impersonate2())) {
            assertNull(tab.getIconFileName(), "the experimental output card is hidden when the toggle is off");
            assertFalse(hasTab(b, InteractiveOutputRunTab.class), "no output run tab when the toggle is off");
        }
        appearance.setOutputBuildCard(true);
    }

    @Test
    void inputCardIsNativeInExperimentalOnlyWhileWaiting(JenkinsRule j) throws Exception {
        withUsers(j);
        FreeStyleProject p = j.createFreeStyleProject("ijob");
        p.addProperty(new InteractiveInputRunPageAlertJobProperty());
        FreeStyleBuild b = j.buildAndAssertSuccess(p);

        InteractiveInputRunTab tab = b.getAction(InteractiveInputRunTab.class);
        assertNotNull(tab, "the input tab is attached when the run-page alert property is on");
        assertEquals("interactive-input-overview", tab.getUrlName());

        QuestionStore.get()
                .submit(new Question(
                        "iq1",
                        "Approve?",
                        List.of(new Choice("y", "Yes")),
                        false,
                        0L,
                        null,
                        null,
                        p.getFullName(),
                        b.getNumber(),
                        "tester",
                        System.currentTimeMillis(),
                        false));
        InteractiveInputRunAction runAction = b.getAction(InteractiveInputRunAction.class);
        assertNotNull(runAction, "the run action attaches once the build has a question");
        assertTabClickRedirects(j, b, InteractiveInputRunTab.URL_NAME, InteractiveInputRunAction.URL_NAME);

        // Classic viewer: no native tab; the classic attention row renders.
        try (ACLContext ignored = ACL.as2(user("inClassic", Map.of()).impersonate2())) {
            assertNull(tab.getIconFileName(), "the input tab is hidden in the classic layout");
            assertTrue(runAction.isClassicSummaryVisible(), "the classic attention row renders in the classic layout");
        }

        // Experimental viewer, waiting: native card visible; classic attention row suppressed.
        try (ACLContext ignored =
                ACL.as2(user("inExp", Map.of(NEW_BUILD_PAGE, "true")).impersonate2())) {
            assertTrue(tab.isWaiting(), "sanity: the build is waiting for input");
            assertNotNull(tab.getIconFileName(), "the input tab is visible in experimental while waiting");
            assertTrue(hasTab(b, InteractiveInputRunTab.class), "the input tab is in getRunTabs while waiting");
            assertFalse(runAction.isClassicSummaryVisible(), "the classic attention row is suppressed in experimental");
        }

        // Once settled the attention card disappears even in experimental (nothing to answer).
        QuestionStore.get().abort("iq1", "tester", "test");
        try (ACLContext ignored =
                ACL.as2(user("inExp2", Map.of(NEW_BUILD_PAGE, "true")).impersonate2())) {
            assertFalse(tab.isWaiting(), "no longer waiting after settle");
            assertNull(tab.getIconFileName(), "the input tab is hidden once the question is settled");
        }
    }

    @Test
    void viewCardIsNativeInExperimentalWhenReviewsExist(JenkinsRule j) throws Exception {
        withUsers(j);
        FreeStyleProject p = j.createFreeStyleProject("vjob");
        FreeStyleBuild b = j.buildAndAssertSuccess(p);

        // A build with at least one review — the same gate the persisted view action uses.
        ViewStore.get()
                .submit(
                        new ReviewDocument(
                                "vrev1",
                                p.getFullName(),
                                b.getNumber(),
                                "Report",
                                "report.md",
                                "report.md",
                                ReviewDocument.FORMAT_MARKDOWN,
                                "markdown",
                                "tester",
                                System.currentTimeMillis(),
                                true, // commentable
                                false, // editable
                                true, // notify
                                false, // blocking
                                null, // submitterFilter
                                0L, // slaMs
                                null, // groupId
                                ReviewDocument.MODE_REVIEW),
                        "# hello");

        InteractiveViewRunTab tab = b.getAction(InteractiveViewRunTab.class);
        assertNotNull(tab, "the view tab is attached once the build has a review (layout-independent)");
        // Distinct URL so the tab's own route never collides with the canonical view action route.
        assertEquals("interactive-view-overview", tab.getUrlName());
        assertTabClickRedirects(j, b, InteractiveViewRunTab.URL_NAME, InteractiveViewRunAction.URL_NAME);

        // Classic viewer: no native tab; the persisted view action stays reachable via the sidebar/overflow.
        try (ACLContext ignored = ACL.as2(user("viewClassic", Map.of()).impersonate2())) {
            assertNull(tab.getIconFileName(), "the view tab is hidden (null icon) in the classic layout");
            assertFalse(hasTab(b, InteractiveViewRunTab.class), "no native view run tab in the classic layout");
        }

        // Experimental viewer: native card visible, listing the build's readable reviews.
        try (ACLContext ignored =
                ACL.as2(user("viewExp", Map.of(NEW_BUILD_PAGE, "true")).impersonate2())) {
            assertNotNull(tab.getIconFileName(), "the view tab is visible in the experimental layout");
            assertTrue(hasTab(b, InteractiveViewRunTab.class), "the view tab is in getRunTabs in experimental");
            assertFalse(tab.getReviews().isEmpty(), "the card lists the build's readable reviews");
        }

        // Appearance toggle hides the View card (and its tab) even in the experimental layout; the
        // dedicated review page / sidebar entry remain reachable via the persisted view action.
        InteractiveInputAppearanceConfig appearance = InteractiveInputAppearanceConfig.get();
        assertNotNull(appearance);
        appearance.setViewBuildCard(false);
        try (ACLContext ignored =
                ACL.as2(user("viewExpOff", Map.of(NEW_BUILD_PAGE, "true")).impersonate2())) {
            assertNull(tab.getIconFileName(), "the view card is hidden when the Appearance toggle is off");
            assertFalse(hasTab(b, InteractiveViewRunTab.class), "no view run tab when the Appearance toggle is off");
        }
        appearance.setViewBuildCard(true);
    }

    @Test
    void outputTabIndexRequiresItemRead(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        MockAuthorizationStrategy auth = new MockAuthorizationStrategy();
        auth.grant(Jenkins.READ).everywhere().to("reader", "outsider");
        auth.grant(Item.READ, Item.BUILD).everywhere().to("reader");
        j.jenkins.setAuthorizationStrategy(auth);

        WorkflowJob p = j.createProject(WorkflowJob.class, "tab-perm");
        p.setDefinition(new CpsFlowDefinition(
                "interactiveOutput(reportName: 'Cost', chartType: 'bar', metrics: [[label:'C', value:'1', key:'c']])",
                true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.assertBuildStatusSuccess(j.waitForCompletion(b));

        String tabPath = b.getUrl() + InteractiveOutputRunTab.URL_NAME + "/";
        try (JenkinsRule.WebClient wc = j.createWebClient().login("reader")) {
            HtmlPage dest = wc.goTo(tabPath);
            assertTrue(
                    dest.getUrl().getPath().contains("/" + InteractiveOutputBuildAction.URL_NAME + "/"),
                    "a reader following the output tab lands on the canonical page");
        }

        JenkinsRule.WebClient outsider = j.createWebClient().login("outsider");
        outsider.getOptions().setThrowExceptionOnFailingStatusCode(false);
        outsider.getOptions().setRedirectEnabled(true);
        int status = outsider.getPage(new WebRequest(new URL(j.getURL(), tabPath), HttpMethod.GET))
                .getWebResponse()
                .getStatusCode();
        assertTrue(status == 403 || status == 404, "no Item.READ must not follow the tab; got " + status);
    }

    @Test
    void viewOverviewGroupsSuppressRedundantHeadings(JenkinsRule j) throws Exception {
        withUsers(j);
        FreeStyleProject p = j.createFreeStyleProject("vgroups");
        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        String job = p.getFullName();
        int n = b.getNumber();
        ViewStore vs = ViewStore.get();
        // Lone file whose title defaults to its report name (single-file publish) -> heading is redundant.
        vs.submit(review("g-solo", job, n, "Solo", "Solo", "solo.md", null), "# a");
        // Lone file carrying a distinct title -> the report heading still adds information.
        vs.submit(review("g-custom", job, n, "Custom", "Custom Title", "c.md", null), "# b");
        // A real multi-file group (glob) -> the heading groups the files.
        vs.submit(review("g-a", job, n, "Docs", "docs/a.md", "docs/a.md", "grp"), "# c");
        vs.submit(review("g-b", job, n, "Docs", "docs/b.md", "docs/b.md", "grp"), "# d");

        InteractiveViewRunTab tab = b.getAction(InteractiveViewRunTab.class);
        assertNotNull(tab, "the view tab attaches once the build has reviews");
        Map<String, Boolean> headingShown = new HashMap<>();
        for (InteractiveViewRunTab.OverviewGroup g : tab.getOverviewGroups()) {
            headingShown.put(g.getReportName(), g.isShowHeading());
        }
        assertEquals(
                Boolean.FALSE,
                headingShown.get("Solo"),
                "a lone file whose title equals its report name hides the duplicate heading");
        assertEquals(
                Boolean.TRUE, headingShown.get("Custom"), "a lone file with a custom title keeps its report heading");
        assertEquals(Boolean.TRUE, headingShown.get("Docs"), "a multi-file group keeps its report heading");
    }

    @Test
    void jobBoxIsSuppressedUnderExperimentalJobPage(JenkinsRule j) throws Exception {
        withUsers(j);
        FreeStyleProject p = j.createFreeStyleProject("jbox");
        InteractiveInputJobAction action = p.getAction(InteractiveInputJobAction.class);
        assertNotNull(action, "the job action is attached when the per-project centre is on");

        // Classic viewer: the inline box follows the Appearance toggle (behaviour unchanged).
        try (ACLContext ignored = ACL.as2(user("jobClassic", Map.of()).impersonate2())) {
            assertEquals(
                    action.isJobPageBoxEnabled(),
                    action.isJobBoxVisibleClassic(),
                    "in the classic layout the box tracks the Appearance toggle");
        }
        // Experimental viewer: the inline box is suppressed so nothing of ours renders inside "Legacy".
        try (ACLContext ignored =
                ACL.as2(user("jobExp", Map.of(NEW_JOB_PAGE, "true")).impersonate2())) {
            assertFalse(
                    action.isJobBoxVisibleClassic(), "the inline box is suppressed under the experimental job page");
        }
    }
}
