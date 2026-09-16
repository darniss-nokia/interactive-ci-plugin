// © 2026 Nokia
// Licensed under the MIT License
// SPDX-License-Identifier: MIT

package io.jenkins.plugins.interactiveinput.notify;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.tasks.Mailer;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Notify-only email channel. Uses the controller's Mailer SMTP session, so Microsoft 365 / Exchange
 * / Gmail all work when <em>Manage Jenkins → System → E-mail Notification</em> is configured. No
 * Microsoft Graph API.
 */
public class EmailChannel extends NotificationChannel {

    private static final Logger LOGGER = Logger.getLogger(EmailChannel.class.getName());

    @CheckForNull
    private String recipients;

    @DataBoundConstructor
    public EmailChannel() {}

    @CheckForNull
    public String getRecipients() {
        return recipients;
    }

    @DataBoundSetter
    public void setRecipients(@CheckForNull String recipients) {
        this.recipients = Util.fixEmptyAndTrim(recipients);
    }

    @Override
    public void send(@NonNull NotificationEvent event) {
        List<String> to = EmailRecipients.parse(recipients);
        if (to.isEmpty()) {
            LOGGER.log(Level.FINE, "email channel has no valid recipients; skipping");
            return;
        }
        try {
            Mailer.DescriptorImpl mailer = Mailer.descriptor();
            if (mailer == null) {
                LOGGER.log(Level.WARNING, "Mailer plugin is not installed; email channel skipped");
                return;
            }
            String smtp = Util.fixEmptyAndTrim(mailer.getSmtpHost());
            if (smtp == null) {
                smtp = Util.fixEmptyAndTrim(mailer.getSmtpServer());
            }
            if (smtp == null) {
                LOGGER.log(Level.WARNING, "Jenkins SMTP is not configured; email channel skipped");
                return;
            }
            Session session = mailer.createSession();
            if (session == null) {
                LOGGER.log(Level.WARNING, "Jenkins SMTP session is unavailable; email channel skipped");
                return;
            }
            MimeMessage msg = new MimeMessage(session);
            String from = Util.fixEmptyAndTrim(mailer.getReplyToAddress());
            if (from == null) {
                from = mailer.getAdminAddress();
            }
            if (from != null && !from.isBlank()) {
                msg.setFrom(new InternetAddress(from, false));
            }
            for (String addr : to) {
                msg.addRecipient(Message.RecipientType.TO, new InternetAddress(addr, false));
            }
            msg.setSubject(subject(event), "UTF-8");
            msg.setText(body(event), "UTF-8");
            Transport.send(msg);
        } catch (NoClassDefFoundError e) {
            LOGGER.log(Level.WARNING, "Mailer classes are not on the classpath; email channel skipped");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "email channel failed: {0}", e.toString());
        }
    }

    @NonNull
    static String subject(@NonNull NotificationEvent event) {
        String kind = event.getKind() == NotificationEvent.Kind.REVIEW ? "Interactive view" : "Interactive input";
        return "[Jenkins] " + kind + " needed: " + event.getJobFullName() + " #" + event.getBuildNumber();
    }

    @NonNull
    static String body(@NonNull NotificationEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append(event.summaryLine()).append('\n');
        sb.append(event.getTitle()).append('\n');
        sb.append(event.getUrl()).append('\n');
        return sb.toString();
    }

    @Extension(optional = true)
    @Symbol("email")
    public static final class DescriptorImpl extends Descriptor<NotificationChannel> {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Email";
        }
    }
}
