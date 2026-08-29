# Security Policy

## Reporting a vulnerability

**Do not open a public GitHub issue for security problems.**

- For plugins hosted under the Jenkins project, report via the Jenkins security process:
  <https://www.jenkins.io/security/> (email `jenkinsci-cert@googlegroups.com`).
- Otherwise, email the maintainer: **Darniss `<darniss.mail@gmail.com>`**.

Please include a description, affected versions, reproduction steps, and impact. We aim to
acknowledge within 5 business days.

---

## Security model (how the plugin defends itself)

The plugin is a permission-checked surface over paused pipelines. Its guarantees:

### Authentication & authorization
- **Permissions mirror `pipeline-input-step`.** Answering/aborting a question requires `Item/Build`
  on the source job — or, when a `submitterFilter` is set, membership in that user/group set (with
  the standard `Overall/Administer` bypass). Viewing requires `Item/Read`.
- The REST root is an `UnprotectedRootAction` **only** so the `/health` probe is reachable; **every
  other endpoint performs its own explicit permission check.**
- `GET /questions/{id}` returns **404** whether the question is missing *or* the caller lacks
  `Item/Read` — no existence/enumeration leak.
- The global list (`GET /questions?all=true`) requires **`Overall/Administer`**; the default list is
  scoped to what the caller may answer.

### CSRF
- All mutating endpoints (`answer`, `abort`, `preview`) are `@RequirePOST`, so Jenkins' crumb filter
  (`CrumbExclusion`/`CrumbFilter`) is enforced at the framework level. The bell fetches a crumb from
  `crumbIssuer` and attaches it to every write.

### Cross-site scripting (XSS)
- **Server-side sanitisation.** `contextMarkdown` and free-text are rendered by `commonmark`
  configured with `escapeHtml(true)` + `sanitizeUrls(true)` + `percentEncodeUrls(true)`. Raw HTML is
  escaped (e.g. `<script>` → `&lt;script&gt;`), and `javascript:`/other unsafe URL schemes are
  stripped. Verified live: a `<script>` payload round-trips as inert text.
- **Client-side discipline.** In `bell.js`, all untrusted fields (`prompt`, choice `label`/`why`,
  `jobFullName`) are inserted via `textContent`. Only server-sanitised HTML (`contextHtml` and the
  `/preview` response) is inserted via `innerHTML`.
- **Rendered HTML review documents (isolated, never inlined).** An `interactiveView` HTML snapshot is
  pipeline-generated and therefore untrusted, so it is **never** inserted into a Jenkins page. It is
  served by `GET /views/{id}/rendered` as `text/html` under
  `Content-Security-Policy: sandbox allow-scripts; base-uri 'none'; form-action 'none'; frame-ancestors 'self'`
  and displayed in an `<iframe sandbox="allow-scripts">`. Withholding `allow-same-origin` is the boundary:
  the document gets a unique **opaque** origin, so its scripts may run (a self-contained report such as a
  Robot Framework `log.html` is entirely script-driven and shows nothing without them) but cannot read the
  embedding page, the session cookie, `localStorage`, or a CSRF crumb — Jenkins sends no CORS headers, so a
  cross-origin read is refused. `allow-forms`, `allow-popups` and `allow-top-navigation` are withheld too.
  Sending the policy as a **header** (not only as the frame attribute) means opening the URL directly is
  equally contained. The endpoint is `Item.READ`-gated (404 no-leak), refuses any non-HTML document, and is
  disabled entirely by the `htmlRendering` feature flag. Residual, accepted risk of `allow-scripts`:
  outbound requests and misleading content **inside** the frame; the content originates from the operator's
  own pipeline, and an operator who declines that trade turns the flag off.
- **Sanitisation is not an option for these files.** An allowlist sanitiser strips `<script>`, which for a
  generated report removes all of its content (a Robot `log.html` degrades to its "JavaScript disabled"
  error). Isolation, not sanitisation, is therefore the control for HTML documents; markdown continues to
  be sanitised and inlined as before.

### Input validation
- Answers must reference a **declared** choice id (or the `__deny__` sentinel); free text is only
  accepted when the question set `allowFreeText: true`. Invalid → `400`, unauthorised → `403`,
  already-settled → `409`.

### Data at rest & concurrency
- Questions persist to `$JENKINS_HOME/interactive-input/questions.xml` via `XmlFile`/XStream (same
  mechanism Jenkins uses for its own config), inside `$JENKINS_HOME` (not web-served).
- The store is a `ConcurrentHashMap`; each question's transitions are serialised via
  `synchronized(question)`, so concurrent answer/abort/expire cannot double-settle.

### Denial-of-service considerations
- The bell **polls** at a configurable cadence with a hard floor of **5s** to protect the controller.
- The `inputStepBridge` scan is bounded (newest `MAX_BUILDS_PER_JOB` builds, gated on `isBuilding`
  and presence of an `InputAction`) and only runs when explicitly enabled.

### Least privilege / opt-in
- Every capability is a feature flag; the bridge and dashboard tile are **off by default**. Operators
  enable only what they need, via UI or JCasC.

---

## Out of scope (v0.1)
- Answering **parameterised** native inputs in-modal (they deep-link to the native form).
- Push notifications (SSE/WebSocket) — polling only.
- Escalation channels (Slack/email/PagerDuty) — accepted but not delivered yet.
