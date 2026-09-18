// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.sun.net.httpserver.HttpServer;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputJobProperty;
import io.jenkins.plugins.interactiveinput.model.Choice;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import io.jenkins.plugins.interactiveinput.view.ReviewDocument;
import io.jenkins.plugins.interactiveinput.view.ViewStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Outbound dispatcher: off-thread, payload URLs, webhook POST, credentials fill permission check,
 * skip when Jenkins root URL is unset.
 */
@WithJenkins
class NotificationDispatcherTest {

    private static final ConcurrentHashMap<String, Gate> GATES = new ConcurrentHashMap<>();

    @Test
    void submitDoesNotWaitForChannelSend(JenkinsRule j) throws Exception {
        String gateId = UUID.randomUUID().toString();
        Gate gate = new Gate();
        GATES.put(gateId, gate);
        try {
            FreeStyleProject p = j.createFreeStyleProject("async-job");
            InteractiveInputJobProperty prop = new InteractiveInputJobProperty();
            prop.setChannels(List.of(new BlockingChannel(gateId)));
            p.addProperty(prop);

            Question q = question(p.getFullName(), 1, "q-async");
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> QuestionStore.get().submit(q));
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS), "worker should start send()");
            assertNull(gate.last, "send must still be blocked, proving it is not on the submit thread");
            gate.release.countDown();
            assertTrue(gate.finished.await(10, TimeUnit.SECONDS));
            assertNotNull(gate.last);
            assertEquals("q-async", gate.last.getId());
        } finally {
            Gate g = GATES.remove(gateId);
            if (g != null) {
                g.release.countDown();
            }
        }
    }

    @Test
    void slackWebhookReceivesJobMetadataAndInputUrl(JenkinsRule j) throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        CountDownLatch hit = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] ok = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
            hit.countDown();
        });
        server.start();
        try {
            String hookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            addSecretText("slack-hook", hookUrl);

            FreeStyleProject p = j.createFreeStyleProject("slack-job");
            FreeStyleBuild b = j.buildAndAssertSuccess(p);
            SlackChannel slack = new SlackChannel();
            slack.setWebhookCredentialsId("slack-hook");
            InteractiveInputJobProperty prop = new InteractiveInputJobProperty();
            prop.setChannels(List.of(slack));
            p.addProperty(prop);

            QuestionStore.get().submit(question(p.getFullName(), b.getNumber(), "q-slack"));
            assertTrue(hit.await(15, TimeUnit.SECONDS), "webhook should be POSTed");
            String json = body.get();
            assertNotNull(json);
            assertTrue(json.contains("slack-job"), json);
            assertTrue(json.contains("#" + b.getNumber()) || json.contains(" " + b.getNumber()), json);
            assertTrue(json.contains("interactive-input/?open=q-slack"), json);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reviewNotifyPostsViewUrl(JenkinsRule j) throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        CountDownLatch hit = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            hit.countDown();
        });
        server.start();
        try {
            String hookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            addSecretText("teams-hook", hookUrl);

            FreeStyleProject p = j.createFreeStyleProject("view-job");
            FreeStyleBuild b = j.buildAndAssertSuccess(p);
            TeamsChannel teams = new TeamsChannel();
            teams.setWebhookCredentialsId("teams-hook");
            InteractiveInputJobProperty prop = new InteractiveInputJobProperty();
            prop.setChannels(List.of(teams));
            p.addProperty(prop);

            ReviewDocument doc = new ReviewDocument(
                    "doc-1",
                    p.getFullName(),
                    b.getNumber(),
                    "Release",
                    "Release notes",
                    "notes.md",
                    ReviewDocument.FORMAT_MARKDOWN,
                    "markdown",
                    "darnr",
                    System.currentTimeMillis(),
                    true,
                    false,
                    true,
                    false,
                    null,
                    0L,
                    null,
                    ReviewDocument.MODE_REVIEW);
            ViewStore.get().submit(doc, "# hi\n");
            assertTrue(hit.await(15, TimeUnit.SECONDS), "Teams webhook should be POSTed");
            String json = body.get();
            assertTrue(json.contains("interactive-view/?doc=doc-1"), json);
            assertTrue(json.contains("view-job"), json);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void skipsWhenRootUrlMissing(JenkinsRule j) throws Exception {
        JenkinsLocationConfiguration.get().setUrl(null);
        FreeStyleProject p = j.createFreeStyleProject("no-root");
        RecordingChannel rec = new RecordingChannel();
        InteractiveInputJobProperty prop = new InteractiveInputJobProperty();
        prop.setChannels(List.of(rec));
        p.addProperty(prop);
        QuestionStore.get().submit(question(p.getFullName(), 1, "q-noroots"));
        Thread.sleep(500);
        assertNull(rec.last, "must not send without an absolute Jenkins URL");
    }

    @Test
    void credentialsFillRequiresConfigure(JenkinsRule j) throws Exception {
        addSecretText("hidden-hook", "https://example.invalid/h");

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .to("reader")
                .grant(Jenkins.ADMINISTER, Item.CONFIGURE)
                .everywhere()
                .to("admin"));
        FreeStyleProject p = j.createFreeStyleProject("cred-job");
        SlackChannel.DescriptorImpl d = new SlackChannel.DescriptorImpl();
        try (ACLContext ctx = ACL.as(User.getById("reader", true))) {
            ListBoxModel m = d.doFillWebhookCredentialsIdItems(p, "");
            assertTrue(m.stream().noneMatch(o -> "hidden-hook".equals(o.value)), "reader must not enumerate secrets");
        }
        try (ACLContext ctx = ACL.as(User.getById("admin", true))) {
            ListBoxModel m = d.doFillWebhookCredentialsIdItems(p, "");
            assertTrue(
                    m.stream().anyMatch(o -> "hidden-hook".equals(o.value)),
                    () -> "configurer should see the secret-text credential; got "
                            + m.stream().map(o -> o.value).toList());
            ListBoxModel absent = d.doFillWebhookCredentialsIdItems(p, null);
            assertTrue(
                    absent.stream().anyMatch(o -> "hidden-hook".equals(o.value)),
                    "absent QueryParameter (null current id) must still list credentials for a configurer");
        }
        assertNotNull(
                SlackChannel.DescriptorImpl.class
                        .getMethod("doFillWebhookCredentialsIdItems", Item.class, String.class)
                        .getAnnotation(org.kohsuke.stapler.verb.POST.class),
                "Slack fill must be @POST (Jenkins Security Scan CSRF)");
        assertNotNull(
                TeamsChannel.DescriptorImpl.class
                        .getMethod("doFillWebhookCredentialsIdItems", Item.class, String.class)
                        .getAnnotation(org.kohsuke.stapler.verb.POST.class),
                "Teams fill must be @POST (Jenkins Security Scan CSRF)");
    }

    @Test
    void emailSendDoesNotThrowWithoutSmtp(JenkinsRule j) {
        EmailChannel ch = new EmailChannel();
        ch.setRecipients("ops@example.com");
        NotificationEvent event = new NotificationEvent(
                NotificationEvent.Kind.QUESTION,
                "q1",
                "job",
                1,
                "u",
                "hello",
                "WAITING",
                "http://localhost/job/job/1/interactive-input/?open=q1");
        ch.send(event);
    }

    private static void addSecretText(String id, String secret) throws Exception {
        CredentialsStore store = CredentialsProvider.lookupStores(Jenkins.get()).iterator().next();
        store.addCredentials(
                Domain.global(),
                new StringCredentialsImpl(CredentialsScope.GLOBAL, id, id, Secret.fromString(secret)));
    }

    private static Question question(String job, int build, String id) {
        return new Question(
                id,
                "Approve?",
                List.of(new Choice("y", "Yes")),
                false,
                0L,
                null,
                null,
                job,
                build,
                "alice",
                System.currentTimeMillis(),
                false);
    }

    static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        volatile NotificationEvent last;
    }

    /**
     * Persistable stand-in: latches live in {@link #GATES} so XStream does not have to serialize
     * {@link CountDownLatch}.
     */
    public static final class BlockingChannel extends NotificationChannel {
        private final String gateId;

        public BlockingChannel(String gateId) {
            this.gateId = gateId;
        }

        @Override
        public void send(NotificationEvent event) {
            Gate gate = GATES.get(gateId);
            if (gate == null) {
                return;
            }
            gate.entered.countDown();
            try {
                gate.release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            gate.last = event;
            gate.finished.countDown();
        }
    }

    public static final class RecordingChannel extends NotificationChannel {
        volatile NotificationEvent last;

        @Override
        public void send(NotificationEvent event) {
            last = event;
        }
    }
}
