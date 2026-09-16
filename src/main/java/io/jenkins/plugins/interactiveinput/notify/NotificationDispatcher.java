// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.notify;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputJobProperty;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.store.QuestionStoreListener;
import io.jenkins.plugins.interactiveinput.view.ReviewDocument;
import io.jenkins.plugins.interactiveinput.view.ViewStoreListener;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.util.Timer;

/**
 * Queues notify-only outbound delivery off the pipeline thread. Listeners only snapshot an event and
 * {@link Timer#submit}; HTTP/SMTP runs on the Jenkins timer pool under {@link ACL#SYSTEM2}.
 */
public final class NotificationDispatcher {

    private static final Logger LOGGER = Logger.getLogger(NotificationDispatcher.class.getName());

    private NotificationDispatcher() {}

    static void onQuestion(@NonNull Question question) {
        String url = NotificationUrls.forQuestion(question);
        if (url == null) {
            LOGGER.log(
                    Level.FINE,
                    "skip outbound notify for question {0}: Jenkins root URL or job is missing",
                    question.getId());
            return;
        }
        enqueue(question.getJobFullName(), NotificationEvent.question(question, url));
    }

    static void onReview(@NonNull ReviewDocument doc) {
        if (!doc.isNotify()) {
            return;
        }
        String url = NotificationUrls.forReview(doc);
        if (url == null) {
            LOGGER.log(
                    Level.FINE,
                    "skip outbound notify for review {0}: Jenkins root URL or job is missing",
                    doc.getId());
            return;
        }
        enqueue(doc.getJobFullName(), NotificationEvent.review(doc, url));
    }

    static void enqueue(@NonNull String jobFullName, @NonNull NotificationEvent event) {
        Timer.get()
                .submit(
                        () -> {
                            try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) {
                                deliver(jobFullName, event);
                            } catch (RuntimeException e) {
                                LOGGER.log(Level.WARNING, "outbound notification worker failed", e);
                            }
                        });
    }

    static void deliver(@NonNull String jobFullName, @NonNull NotificationEvent event) {
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null) {
            return;
        }
        Job<?, ?> job = j.getItemByFullName(jobFullName, Job.class);
        if (job == null) {
            LOGGER.log(Level.FINE, "skip outbound notify: job {0} is gone", jobFullName);
            return;
        }
        InteractiveInputJobProperty prop = job.getProperty(InteractiveInputJobProperty.class);
        if (prop == null) {
            return;
        }
        List<NotificationChannel> channels = prop.getChannels();
        for (NotificationChannel channel : channels) {
            if (channel == null) {
                continue;
            }
            try {
                channel.send(event);
            } catch (RuntimeException e) {
                LOGGER.log(
                        Level.WARNING,
                        "notification channel " + channel.getClass().getName() + " threw",
                        e);
            }
        }
    }

    /** {@link QuestionStoreListener} that only queues; must not block {@code QuestionStore#submit}. */
    @Extension
    public static final class Questions extends QuestionStoreListener {
        @Override
        public void onSubmitted(@NonNull Question question) {
            onQuestion(question);
        }
    }

    /** {@link ViewStoreListener} that only queues; must not block {@code ViewStore#submit}. */
    @Extension
    public static final class Reviews extends ViewStoreListener {
        @Override
        public void onSubmitted(@NonNull ReviewDocument doc) {
            onReview(doc);
        }
    }
}
