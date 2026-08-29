# Contributing to Interactive CI

Thanks for your interest in improving Interactive CI! This guide covers local setup, standards,
and the pull-request flow.

## Prerequisites

- **JDK 21+** (the 2.568 baseline requires Java 21; the CI/reference build uses JDK 21).
- **Maven 3.8.6+**.
- Network access to `https://repo.jenkins-ci.org/public/` (behind a corporate proxy, configure
  `~/.m2/settings.xml` — see [`SESSION_NOTES.md`](SESSION_NOTES.md)).

## Build & test

```bash
mvn -B -ntp clean verify      # compile, run all tests, SpotBugs, package the HPI
mvn -B -ntp hpi:run           # run a local Jenkins with the plugin at http://localhost:8080/jenkins/
```

The build must be green (**0 failures, 0 errors**) and SpotBugs must report no new issues before a
PR is merged.

## Project layout

```
src/main/java/io/jenkins/plugins/interactiveinput/
  config/   JCasC-compatible global config (Features, Polling, Sla)
  model/    Question, Choice, Answer, QuestionStatus (data model)
  store/    QuestionStore (durable registry) + SlaTicker + listener
  step/     askInteractive step + execution
  rest/     versioned REST API (ApiRootAction)
  ui/       NotificationBell PageDecorator
  bridge/   opt-in InputStepBridge
  util/     MarkdownRenderer (safe HTML)
src/main/resources/...          bell.js, bell.css, Jelly views, index.jelly
src/test/java/...               JUnit 5 suite (JenkinsRule + pure unit)
```

## Coding standards

- **Production-ready, minimal-diff changes.** Follow existing patterns; prefer the smallest change
  that solves the problem. No speculative refactors or unrelated formatting churn.
- **Error handling + logging.** Use `java.util.logging` (`Logger`); never swallow exceptions silently
  where an operator would need the signal.
- **Security first.** Any new endpoint must check permissions explicitly and be `@RequirePOST` if it
  mutates. Never insert untrusted data as HTML on the client; render Markdown only through
  `MarkdownRenderer`.
- **Nullness annotations.** Use `@NonNull` / `@CheckForNull` (SpotBugs/JSR-305) consistently.
- **Formatting.** The build runs Spotless (via the plugin parent POM); run `mvn spotless:apply` to
  auto-format before committing.
- **Comments** explain *why*, not *what*. Don't narrate the code.

## Tests

- Add/extend JUnit 5 tests for any behavioural change. Use `JenkinsRule` for integration paths
  (step, REST, bridge, permissions, JCasC) and plain unit tests for pure logic (model, markdown).
- Keep the JCasC round-trip test (`JcascRoundTripTest`) passing when touching config classes.

## Commit & PR flow

1. Branch from the default branch: `feature/<short-name>` or `fix/<short-name>`.
2. Use [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`, …).
3. Ensure `mvn -B -ntp clean verify` is green and update `CHANGELOG.md` under **Unreleased**.
4. Open a PR describing **evidence → analysis → decision → implementation**, with reproduction/verification steps.
5. For security-sensitive changes, request a security review and mention the threat considered.

## Reporting bugs / security issues

- Functional bugs: open a GitHub issue with a minimal reproduction (`Jenkinsfile` snippet + expected
  vs actual).
- Security vulnerabilities: **do not** file a public issue — see [`docs/SECURITY.md`](docs/SECURITY.md).

## License

By contributing, you agree that your contributions are licensed under the project's
[MIT License](LICENSE).
