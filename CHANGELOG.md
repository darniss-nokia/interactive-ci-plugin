# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Interactive View can now render an HTML document, not just show its source.** An HTML review offers a
  **Rendered / Source** toggle (Rendered first); every other format is unchanged. Reported for a Robot
  Framework `log.html`, which previously read as 542 KB of escaped markup. Sanitising such a file into the
  page is not an option — its entire content is produced by its own JavaScript, so an allowlist sanitiser
  leaves only its *"Opening Robot Framework log failed / JavaScript disabled"* notice — so the document is
  **isolated instead of sanitised**: a new read-only `GET /views/{id}/rendered?version=n` serves it as
  `text/html` under `Content-Security-Policy: sandbox allow-scripts; base-uri 'none'; form-action 'none';
  frame-ancestors 'self'` (new `SandboxedHtmlResponse`), and the viewer displays it in an
  `<iframe sandbox="allow-scripts">`. Withholding `allow-same-origin` is the boundary: the report gets a
  unique **opaque** origin, so its scripts run but cannot read the Jenkins page, the session cookie,
  `localStorage` or a CSRF crumb; `allow-forms` / `allow-popups` / `allow-top-navigation` are withheld too.
  The policy is sent as a **header** as well as a frame attribute, so opening the URL directly is equally
  contained, and it *replaces* (never appends to) core's page CSP — a browser enforces every CSP header it
  receives, and core's `script-src 'self'` would otherwise have silently blanked the report. The endpoint is
  `Item.READ`-gated (404 no-leak), refuses any non-HTML document, and is gated by a new
  **htmlRendering** feature flag (*Manage Jenkins → System*, JCasC
  `unclassified.interactiveInput.features.htmlRendering`, default on) that returns HTML to source-only when
  off. Because the frame is isolated, the Rendered view is read-only: inline line comments stay on the
  Source view, and the decision buttons sit outside the frame, so both are unaffected.
- **Interactive View file & folder downloads.** The review editor can now download the file being viewed
  and — for a multi-file group (a glob/`dir` publish) — every file in the group as a single ZIP. Two new
  read-only REST endpoints back it: `GET /views/{id}/download` (the current version's content as an
  attachment) and `GET /views/{id}/downloadGroup` (a ZIP of every readable co-group member on that build).
  The UI adds a `Download` button in the detail toolbar, a per-card `Download` on the build's review list,
  and a `Download all (.zip)` on each multi-file group header. Both endpoints enforce `Item.READ` via
  `ViewStore.canView` (404 no-leak, never 403) and stream through a new `DownloadHttpResponse` that sets
  `Content-Disposition: attachment` (ASCII `filename` **and** RFC 5987 `filename*`, so a stray quote/CR/LF
  in a name can never break the header) plus `X-Content-Type-Options: nosniff`. ZIP entry names are
  sanitised (no CR/LF, no leading `/`, no `.`/`..` segments) and de-duplicated. Sizes stay bounded by the
  step's existing 2 MB/file and 8 MB/group snapshot caps, so the archive is built safely in memory.
- **Highlight-to-comment mode for inline review comments (now the default).** Inline comments can be added
  two ways, chosen by a per-user toolbar toggle persisted in `localStorage` (`ii-view-comment-mode`):
  **Highlight** (default) — select any text in a line and click a floating *Add comment* button — and
  **Plus** — the original hover-`+` affordance (identical to before when selected). Highlight mode resolves
  the selection's exact source line (a Source row, or the innermost `data-source-line` element in the
  rendered view) and opens that line's thread, reusing the same per-line comment model and REST so a
  comment round-trips to the pipeline unchanged. The `+` affordances are hidden via CSS in highlight mode
  while the existing comment-count markers stay visible. Whatever the reviewer highlights is now preserved:
  the exact selected text — a sub-phrase of a long line, or a span across several lines — is captured as an
  optional, display-only `quote` on the comment (new `ReviewComment` field; length-bounded server-side and
  rendered verbatim via `textContent`, never markdown/HTML) and shown back above both the composer and the
  posted comment, while the comment still anchors at the selection's **first** line (so a multi-line drag no
  longer collapses to a single line, and a partial selection no longer silently expands to the whole line).
  A comment left via the Plus/`+` path, a general comment, and legacy data carry no quote (XStream-safe null).
- **Line-context snippets in inline comments.** The selected-line thread header, the comment composer
  placeholder, each comment's `L{n}` chip tooltip and the line-comment navigation list now show a short,
  whitespace-collapsed, length-bounded snippet of the referenced source line (always via `textContent`,
  never `innerHTML`), so a reviewer sees *what* a line says — not just its number — when reading or writing
  a comment.
- **Per-project notification centre** — notifications now surface per pipeline/build instead of only
  at one Jenkins-wide point:
  - `InteractiveInputJobAction` (`TransientActionFactory<Job>`) — an inline box on the job/pipeline
    page (`jobMain.jelly`) plus a sidebar page (`index.jelly`) listing that job's pending questions;
    the link/box appear only when something is pending.
  - `InteractiveInputRunAction` (`BuildBadgeAction`, `TransientActionFactory<Run>`) — an "awaiting
    input" badge in the build-history list while a build waits, and a per-build **audit view** showing
    what was displayed and what was chosen. Attached only to builds that used interactive-input.
  - REST scoping: `GET /questions?job=<fullName>` (per-project, `Item.READ`, 404 no-leak) and
    `?job=<fullName>&build=<n>` (per-build audit incl. the recorded answer).
- **Attribution** — `Question.startedBy` (resolved via `CauseResolver`: user id, else
  `scm`/`timer`/`upstream`/`system`) is populated by the step and the bridge, exposed in the REST JSON,
  and rendered as "started by &lt;user&gt;" on every surface.
- **Anchored console audit link** — the `askInteractive` step logs a `HyperlinkNote` to the per-build
  audit view at invocation (like the built-in `input`), marks the flow node **Paused**
  (`PauseAction`), and logs the resolved outcome (answered/aborted/expired, by whom, and what was
  chosen).
- **Per-pipeline notification preferences** — `InteractiveInputJobProperty` (`OptionalJobProperty`)
  adds an *Interactive Input notifications* section to a pipeline's **Configure** page (email/Teams/
  recipients/webhook). Preferences are **persisted only**; delivery ships in a future release.
- **Appearance configuration** — new `InteractiveInputAppearanceConfig` (`GlobalConfiguration` in the
  `AppearanceCategory`) surfaces under **Manage Jenkins → Appearance → Interactive Input**, and as code
  under `appearance.interactiveInputAppearance`. It holds six independent on/off switches and an icon
  chooser:
  - `notificationCentre` (default **off**) — the global header bell, now **context-scoped**: the
    dashboard lists every answerable question; inside a pipeline (job/build page) it narrows to that
    pipeline's questions (server-side `NotificationBell` resolves the `Job` ancestor and the client
    calls `GET /questions?job=<fullName>`).
  - `perProjectCentre` (default **on**) — gates the per-project surfaces (migrated here from `features`).
  - `jobPageBox` (default **on**) — independently toggles the large inline box on the job page, so the
    badge + sidebar can be kept without the box.
  - `tabNotificationBadge` (default **on**) — mirrors the viewer's pending count in the browser tab.
    When the favicon is **same-origin** (a canvas can read it) it paints a red dot on top of it and
    leaves the title alone; when the favicon is **cross-origin or missing** (a canvas may not read its
    pixels — e.g. a Simple Theme plugin favicon on another host) it falls back to a red-circle glyph
    (U+1F534) + `(N)` prefix on the tab **title** and leaves the favicon exactly as the theme set it.
    It never replaces the site favicon.
  - `viewBuildCard` (default **on**) — shows the compact **Interactive View** card (and its top-nav tab)
    on a build's overview under the experimental layout. Turning it off hides the card while the dedicated
    review page, sidebar link and build-history badge stay reachable.
  - `outputBuildCard` (default **on**) — shows the **Interactive Output** card of per-build metrics on a
    build's page — the native overview card under the experimental layout **and** the classic summary row.
    Turning it off hides both; the dedicated output page stays reachable.
  - `icon` (default `megaphone`) — the notification icon used across the bell, badge and
    sidebar, chosen from seven meaning-matched Ionicons (`ionicons-api`).
- **Attention pulse** — the build-history "awaiting input" badge and the job-page box title blink
  slowly in red (`@keyframes ii-attn-pulse`), with a `prefers-reduced-motion` fallback.
- **Functional configuration UI** — the feature flags (including the opt-in `inputStepBridge`) are now
  toggleable under **Manage Jenkins → System → Interactive Input** (`InteractiveInputGlobalConfig`
  `config.jelly` + `Features/config.jelly`), not only via JCasC. `configure()` starts the flags all-off
  before binding (so an unchecked box turns the flag off) and leaves polling / SLA / retention — which
  are not on this form — untouched.
- **User-scoped notifications & lock** — two **System** (functional) authorization switches on
  `InteractiveInputGlobalConfig`, as code under `unclassified.interactiveInput` (they govern *who* may
  see/answer, so they are not look-and-feel — review item B18), layered on top of the existing
  permission checks:
  - `userScopedNotifications` (default **off**) — when on, every surface (bell, per-project box,
    build-list badge, sidebar) shows a viewer only the questions for **builds they started**, plus
    ownerless builds (trigger/SCM/timer-started, which have no human owner). When off, behaviour is
    unchanged (everyone who can answer sees everything).
  - `lockToBuildStarter` (default **off**) — when on, non-starters may **see** others' questions but
    cannot answer them (`Jenkins.ADMINISTER` still overrides). Enforced server-side in the store and
    REST answer/abort; the REST JSON now carries a per-question `canAnswer` flag and the modal shows a
    "Locked." note with disabled buttons when it is `false`.
  - Store surface: `QuestionStore.listNotifications*/countNotifications*/hasNotificationForBuild` and
    `canAnswerEffective`; ownership resolved via `CauseResolver.isRealUser`.
- **Multi-question "series" modal** — when a single build has two or more questions waiting (e.g. an
  agent posts a series), its build-list dot opens one modal that pages through them with a numbered
  slider (Prev/Next + clickable pips, answered slides marked done). Individual questions on the other
  surfaces (bell dropdown, job-page box) are still answered one at a time.
- **`interactiveView` pipeline step + review page** — publish a generated file (markdown, HTML, source
  code or plain text) for a Confluence-style review inside Jenkins:
  - `interactiveView(file | includes | dir, excludes, reportName, title, format, mode, commentable,
    editable, notify, wait, slaMinutes, submitterFilter)` **snapshots** the target workspace file(s) into a
    durable store (`$JENKINS_HOME/interactive-input/views/`, metadata in `views.xml`), so the review
    survives workspace cleanup. Markdown is rendered to sanitised HTML; HTML/code/text are shown as
    **escaped, syntax-highlighted source** (never executed) via `prism-api`. Snapshots are bounded
    (2&nbsp;MB each). Robot Framework files (`.robot` test suites, `.resource` resource files) are
    detected as source and highlighted with Prism's `robotframework` grammar (autoloaded from `prism-api`),
    like the other mapped languages; unmapped extensions still fall back to escaped plain text.
  - **Folders & dynamically-generated files** — beyond a single `file`, the step accepts an Ant-style
    `includes` glob (e.g. `reports/**/*.md`) with optional `excludes`, or a `dir` (sugar for `dir/**`).
    Each match becomes **one review** sharing a `groupId` + `reportName`, so a report folder whose exact
    filenames are only known at runtime is published in a single call. Bounded by a max file count (50)
    and an aggregate snapshot cap (8&nbsp;MB); `wait: true` stays single-file (a glob that resolves to
    more than one file is rejected with a clear error).
  - **Review vs informational mode** — `mode: 'review'` (default) shows the decision toolbar
    (approve / request changes / reject / acknowledge) and can block/notify; `mode: 'info'` is a
    read-only, still-commentable viewer with no decision. "Needs approval vs not" is expressed per call
    (different glob + `mode`/`notify`).
  - Reviewers can add **inline comments** — per source line in the Source view **and** per element in the
    Rendered view: every commentable element (heading, paragraph, list item, table row, …) carries its own
    `data-source-line`, so hovering it reveals a **+** that anchors the comment to that exact line
    directly — no line-number dropdown (both views map to the same source line, so they round-trip
    either way) — plus general comments, edit the durable
    review **copy** with **version history** (the original workspace file is never modified), and
    **approve / request changes / reject / acknowledge**. Non-blocking by default (publish and continue);
    `wait: true` pauses the pipeline until a decision (or the SLA), returning
    `{id, status, decidedBy, version, content, comments}` — none of the decisions abort the run (mirrors
    the durable, restart-safe `askInteractive` pattern). **Request changes** (`CHANGES_REQUESTED`) returns
    the inline `comments` (`{id, line, body, author, createdTs, resolved, parentId, authorLabel}`) so a
    generator — e.g. an AI agent — can course-correct the review (**edit it in place** while it is
    `CHANGES_REQUESTED`, or re-publish): a human-in-the-loop **regenerate loop**. The automation can post its result as a **threaded reply** nested under the
    reviewer's comment (`parentId`), shown with a configurable display label and an "automation" chip while
    the audit `author` stays the real, server-set Jenkins identity, and record an edit **summary note** in
    the version history. The label defaults to **Manage Jenkins → System → *Automation reply name*** (default
    "AI response", JCasC `unclassified.interactiveInput.automationReplyName`) with a per-reply override.
  - Surfaces: a dedicated **"Interactive View"** left-sidebar link (with a **live open-review count
    badge**, job- and build-scoped, updated without a page reload — and, in the experimental layout's
    **"more actions"** overflow menu, the pending count rendered inline via the action display name)
    opening a two-pane editor
    (`viewer.js`/`viewer.css`) and a per-job list page **grouped by report/folder** with
    **Needs-approval vs Informational** sections, a **Notified** badge, and **All / Notified /
    Needs-approval** filter chips + search; a build-history badge; an anchored console deep-link; and a
    **"Reviews"** section in the notification bell that routes clicks to the editor page. The
    sidebar/editor stay reachable after the build completes (any readable review), so decided reviews'
    comments and version history remain accessible.
  - **Notification scoping parity** — the review surfaces honour the same two **System** switches as
    questions: `userScopedNotifications` (a viewer sees only reviews for **builds they started**, plus
    ownerless trigger/SCM/timer builds) and `lockToBuildStarter` (non-starters may **see** but not
    contribute — comment/edit/decision — while `Jenkins.ADMINISTER` still overrides). Ownership resolves
    via `ReviewDocument.createdBy` + `CauseResolver`; the REST JSON carries a per-review `canContribute`
    flag and the editor locks its controls when it is `false`. Dashboard-cumulative vs per-pipeline counts
    flow through the same scoped store methods, so the bell and sidebar inherit scoping.
  - REST under `/interactive-input/api/v1/views`: `GET /views` (scoped `?job=`, `?job=&build=`,
    `?all=`), `GET /views/{id}`, `GET /views/{id}/raw?version=n`, and `@RequirePOST`
    `comments` (optional `parentId` to thread a reply; `authorLabel`/`automated` for a display-only label,
    length-bounded and rendered as text) / `edit` (optional `note` recorded in the version history) /
    `decision` / `resolveComment` — permission-checked (`Item.READ` to view, `Item.BUILD`/submitter to
    contribute, `Jenkins.ADMINISTER` for `?all=true`), CSRF-crumbed, 404 no-leak. The audit `author` is
    always server-set, never client-supplied.
- **`interactiveOutput` pipeline step + statistics widgets** — publish per-build / per-job statistics
  (e.g. cost report, carbon footprint, resource usage):
  - `interactiveOutput(reportName, metrics: [[label, value, unit, key], …], chartType, notify)` stores a
    `MetricReport` in the build's `InteractiveOutputBuildAction` (persisted in `build.xml`; no extra
    store). The build page shows **KPI cards + a table** (`summary.jelly`) and a dedicated details page.
  - **Selectable chart types** — `chartType` (`line` default | `bar` | `pie` | `timeseries`) is chosen per
    report in the script. `InteractiveOutputJobAction` emits **one chart model per report** via
    `echarts-api` (theme-aware, built in `output.js` from a server-provided JSON model): `line`/`bar` plot
    the numeric metrics **across recent builds** (stable `key` feeds the trend), `pie` shows the **latest
    build's** numeric metrics as slices, and `timeseries` plots the latest build's metrics **by date** when
    each metric's label is a date (`yyyy`, `yyyy-MM`, `yyyy-MM-dd`, `yyyy-MM-dd HH:mm`) — points are
    emitted sorted by date and re-bucketed client-side with a **Time / Day / Month / Year** granularity
    toggle. Several reports render several charts. All labels/tooltips are escaped (no HTML injection).
  - **Client-side table filter + sort** — every metrics table (build and job pages) gains a search box
    and click-to-sort column headers (`output.js`, no backend change); a column of dates sorts
    chronologically and gains a **"Group by date"** (Day / Month / Year) control that reorders the rows by
    the truncated date.
- **Feature flags** — `interactiveView` and `interactiveOutput` added to `features` (both default
  **on**; the surfaces appear only when the steps are actually called), toggleable under
  **Manage Jenkins → System → Interactive Input** and as code under `unclassified.interactiveInput.features`.
- **Dependencies** — `prism-api` (syntax highlighting for the review page) and `echarts-api` (the
  per-job trend chart) added; both are BOM-managed and ship their own JS/CSS as plugin dependencies
  (no bundled jars, so `strictBundledArtifacts` stays on).
- **Interactive View overview card (experimental build page)** — with the **new-build-page** flag on, a
  build's reviews now surface as a native **Interactive View** overview card + run tab, at parity with
  the Interactive Output card. A new `InteractiveViewRunTab` (`jenkins.model.Tab`, attached by a
  `TransientActionFactory<Run>` whenever the build has any review) renders a compact per-report list —
  each review's title (deep-linking to `interactive-view/?doc=<id>`), status pill, notified flag, and
  comment count — plus a "View all reviews" link to the full `interactive-view/` page, and a
  `doIndex` redirect from the tab's own `interactive-view-overview` route to that page. It is visible
  only when the viewer has the experimental layout enabled **and** can read at least one review
  (`getReviews()` is `ViewStore`-scoped); the classic layout and the existing per-job/per-build view
  pages, sidebar link, overflow `(N)`, and badge are unchanged. The card lists **all** of the build's
  readable reviews in a height-capped, scrollable panel (~10 rows before it scrolls, with the "View all
  reviews" link pinned below), shows each review's **file name** beneath its title, and omits the
  report-name heading when it would merely repeat a lone file's title — the common single-file publish,
  where the step defaults an unset `title` to the `reportName` (`InteractiveViewRunTab.OverviewGroup`
  decides this in Java so the Jelly stays trivial). It is also gated by the new `viewBuildCard`
  Appearance switch.

### Changed
- **Interactive View bell notifications now show "started by &lt;user&gt;" like question rows do.** The
  header-bell review row (`bell.js` `viewListItem`) previously showed only the comment count. The build
  starter is already carried on the review (`ReviewDocument.createdBy`, resolved via `CauseResolver` and
  present in the summary JSON), so the row now renders the same `ii-item-by` attribution used for
  `askInteractive` questions — no server change was required.
- **Under the experimental layout, our UI now renders natively instead of inside the "Legacy" card.**
  The experimental build page routes every action's `summary.jelly` into a hardcoded core "Legacy"
  card; previously our per-build Interactive Output KPIs and Interactive Input attention row landed
  there. Now, when the user's **new-build-page** flag is on, each renders as a **native overview card
  + run tab** via `jenkins.model.Tab` (`InteractiveOutputRunTab`, `InteractiveInputRunTab`, attached by
  a `TransientActionFactory<Run>` behind the same visibility gates, each with a `widget.jelly` card and
  a redirecting `doIndex` to the canonical page), and the classic `summary.jelly` rows are **suppressed**
  (new `classicSummaryVisible()` = `visible && !newBuildPage()`) so nothing of ours remains in "Legacy".
  On the experimental **job** page the large inline `ii-jobcard` box is hidden (new
  `isJobBoxVisibleClassic()` = `jobPageBox && !newJobPage()`); the invisible `ii-tasklink` controllers
  stay, and reachability is unchanged (native "more actions" overflow + bell + the dedicated page).
  Layout detection is a new null-safe `ExperimentalLayout` helper reading
  `UserExperimentalFlag.getFlagValueForCurrentUser(...)`; any error **fails safe to the classic layout**.
  Exactly one path renders per layout (the flag is the single switch), so there is no double-render, and
  the **classic layout is unchanged**. Trade-off: this depends on experimental core-UI internals (the
  `Tab` widget grid and the hardcoded flag IDs); if those drift, detection falls back to classic and the
  existing actions stay reachable via the overflow menu. (A native experimental *job* card is not
  possible today because `jenkins.widgets.WidgetFactory` is `@Restricted(NoExternalUse)`.)
- **The experimental "more actions" overflow now shows the pending count for Interactive Input too.** That
  menu is server-rendered from each action's display name, so `InteractiveInputJobAction.getDisplayName()`
  now suffixes the count as `Interactive Input (N)` when at least one question is pending — matching
  `Interactive View (N)` (core exposes no styled-badge slot in that menu). The classic sidebar is
  unchanged: `bell.js` (`mountTaskLink`) resets the label to the plain name and shows the count as a live
  `jenkins-badge` pill, so there is no double count. The `jobPageBox` Appearance help text now documents
  that the inline box is **classic-only** (the experimental job layout has no native job-card slot); the
  count still surfaces there via the header bell, the "more actions" menu, and the dedicated Interactive
  Input page.
- **README coverage of the newer steps.** The "How it works" diagram, "Why this plugin exists", the
  pause → approve → resume flow, and the "Human-in-the-loop scenarios" now describe `interactiveView`
  (review → request changes → regenerate, with `wait: true` returning the reviewer's inline comments) and
  `interactiveOutput` (non-blocking per-build stats + per-job trend charts), not just `askInteractive`.
- **Build-list badge is now an empty red pulsing dot that opens the answer modal in place.** It no
  longer renders the notification icon and no longer navigates to the per-build audit page; clicking it
  opens the same modal the header bell uses, on the current page. Handled by `bell.js` via
  `[data-ii-badge]` event delegation, so it also works for build rows the async build-history widget
  injects after the script runs; the `href` to the audit page remains a no-JS fallback.
- **Notification-surface settings moved from `features` to Appearance.** `navBarBell` (now
  `notificationCentre`) and `perProjectCentre` are no longer functional feature flags; they live under
  **Appearance** per Jenkins core guidance to separate look-and-feel from functional config. The
  functional `features` block keeps `askInteractiveStep`, `richModal`, `restApi`, `inputStepBridge`,
  `dashboardTile`.
- **Authorization switches moved from Appearance to System (review item B18).** `userScopedNotifications`
  and `lockToBuildStarter` govern *who* may see/answer a question, so they are functional config: they
  now live on `InteractiveInputGlobalConfig` under **Manage Jenkins → System → Interactive Input**, as
  code under `unclassified.interactiveInput` (previously `appearance.interactiveInputAppearance`). This
  is a breaking JCasC path change for anyone who set them as code.
- **Default notification icon is now `megaphone`** (was `chatbubble-ellipses`), conveying
  "needs attention / announcement" as the out-of-the-box choice.
- The global bell, when enabled, is anchored into the header controls (with a bottom-right floating
  fallback) so it no longer overlaps the settings gear.
- The rich modal's **context panel is expanded by default**.
- Notification icons now render via `ionicons-api` `<l:icon>` (theme-aware) instead of a hardcoded SVG.
- `bell.js` refactored into a shared client (helpers + modal + answer/preview) reused by the bell and
  the per-project job/audit widgets; the bell clones the operator-chosen icon and scopes its query to
  the current pipeline.
- **The left-sidebar "Interactive Input (N)" count is now live.** `jobMain.jelly` always renders a
  hidden `[data-ii-tasklink]` controller (even at zero) that `bell.js` uses to poll the scoped endpoint
  and re-label the sidebar row — and hide it at zero / re-show it when work arrives — so the number no
  longer stays stale until a full page reload.

### Fixed
- **Every surface now opens a build's multi-question "series" as one numbered stepper.** Observed failure
  (reported with screenshots, reproduced live on 2.568.1 with three `askInteractive` questions published in
  parallel on one build): clicking a question in the **global notification centre** — or the *"Open
  interactive input"* link in the **console log** — opened a lone single-question dialog, while the
  build-history dot on the same build correctly opened the numbered pager (`‹ Prev · 1 / 3 · Next ›` plus
  pips). Root cause: the "this build has more than one waiting question → open the pager" decision was
  re-implemented inside three call sites (the build-history badge, the run-page attention box and the
  console auto-open), so the surfaces that open a *named* question — the bell dropdown, the console
  `data-ii-open` link, the `?open=<id>` deep link, the inline job-page box and the per-build audit list —
  never reached it and always rendered one question. Fix (`bell.js`): a single shared decision
  (`openWaiting` for a build's waiting list, `openQuestionInSeries` for a named question, which resolves
  its build-mates with one scoped `GET` on click) that **every** entry point routes through, so the same
  series is reachable as a set from anywhere. The pager now also opens on the question that was actually
  clicked (new `startId`), rather than always on slide 1. Behaviour is unchanged for a build with a single
  waiting question, for settled (read-only) questions, and when the rich modal is switched off — that still
  navigates to the build's native input page.
- **A build's questions are listed in the order the pipeline asked them.** Observed failure (same
  reproduction): the notification centre listed a three-question series as *Q2, Q3, Q1*, so the stepper's
  slide 1 was not the first question asked. Root cause: every `QuestionStore` query iterated the backing
  `ConcurrentHashMap`, whose order is arbitrary. Fix: all list queries (`listAnswerable`, `listReadable`,
  `listAll`, `listAnswerableForJob`, `listReadableForJob`, `listForBuild` and the notification collector)
  now return oldest-first by `createdTs`, tie-broken by id, which also makes the REST list responses and the
  per-build audit page deterministic.
- **Interactive View now renders a pipe table that a generator wrapped in a bare code fence.** Observed
  failure (live doc `a2571a80…`): a GFM table nested inside a bullet was emitted inside an **un-languaged**
  fenced code block, so commonmark — correctly per spec — rendered it verbatim as a `<pre><code>`
  `|`-delimited block instead of a `<table>` (the document had 3 `<pre>` and only 1 `<table>`). Root cause:
  a fenced code block with no info string is code, not a table, by definition. Fix: `MarkdownRenderer`
  gains a conservative `unfenceGfmTables` pass (run before `normalizeGfmTables` in both the plain and
  source-line renders) that unwraps a fenced block **only** when it has no info string **and** its entire
  body is a pipe table (a header line, a delimiter row directly beneath it, and every other non-blank line
  containing a `|`), by blanking just the opening/closing fence lines so the total line count — and the
  `data-source-line` anchors — stay stable. A fence with a language tag (e.g. `python`) or any non-table
  content is left untouched, and commonmark still escapes every cell. Also confirmed to render a table
  nested inside a list item.
- **The inline-comment "+" no longer hides behind a list bullet.** Observed failure (screenshot from the
  reporter): on a rendered markdown list the per-line `+`/comment-count affordance, positioned in the
  left gutter at `-24px`, sat on top of the list bullet and was hard to find. Root cause: a list item is
  itself a comment-anchor host, so its affordance shared the negative-left zone the browser already uses
  for the bullet marker. Fix (`viewer.css`, presentation only): rendered lists get a deterministic indent
  and the list-item affordance is pushed further left, clear of the bullet; the affordance on every other
  block (paragraph, heading, table row) is unchanged, and no behaviour or markup changes.
- **Interactive View now renders GitHub-Flavored Markdown — tables, strikethrough and autolinks.**
  Observed failure: a markdown review containing a pipe table rendered as a literal `|`-delimited
  paragraph in the document viewer (and likewise in the `askInteractive` modal context panel), rather
  than an HTML `<table>`. Root cause: `MarkdownRenderer` built its commonmark `Parser`/`HtmlRenderer`
  with **no extensions**, and GFM tables/strikethrough/autolinks are not part of the CommonMark core
  spec. Fix: register `TablesExtension`, `StrikethroughExtension` and `AutolinkExtension` on every
  parser/renderer (both the plain and the source-line variants). The extensions ship with the
  `markdown-formatter` plugin dependency, so no extra jar is bundled, and the existing
  `escapeHtml`/`sanitizeUrls` safeguards still apply to their output (a `<script>` in a table cell stays
  escaped, `javascript:` autolinks stay sanitised). The emitted `<table>` is styled theme-aware
  (borders, header shading, zebra rows, per-column alignment) in the document viewer (`viewer.css`) and
  the modal/free-text preview (`bell.css`). Task-list items remain unsupported (that extension is not
  shipped by `markdown-formatter`, and bundling a standalone jar would break the packaging convention).
- **Interactive View now renders malformed GFM tables whose delimiter row is a column short.** Observed
  failure: a real ATLAS document table with a 21-column header but a 20-cell delimiter row degraded to a
  literal `|`-delimited paragraph, because GFM/commonmark requires the header and delimiter column counts
  to match. Root cause: the generator emitted a short delimiter row; commonmark then never recognised the
  block as a table. Fix: `MarkdownRenderer` gains a conservative `normalizeGfmTables` pass (run before both
  the plain and source-line renders) that, only when a pipe line is immediately followed by a delimiter
  row, rebuilds the delimiter to the header's column count — preserving `:--`/`:-:`/`--:` alignment and
  padding missing cells with `---`. It never fabricates a table from non-table text (a delimiter row must
  already be present), never rewrites content inside fenced code blocks, and emits no HTML — commonmark
  still escapes every cell, so a `<script>` in a repaired table stays escaped.
- **The regenerate loop can now edit a review in place after "Request changes".** Observed failure:
  `POST /views/{id}/edit` returned **409** once a reviewer clicked *Request changes*, so an automation
  (the `ii-view-regenerate-agent` sample) could not record its course-corrected version or summary note on
  the very review it was asked to fix. Root cause: `ViewStore.saveEdit` rejected every non-`OPEN` status
  via `ReviewStatus.isDecided()`, but `CHANGES_REQUESTED` exists precisely to invite edits. Fix: a new
  `ReviewStatus.allowsEdit()` (true for `OPEN` **and** `CHANGES_REQUESTED`) gates `saveEdit`, so the
  regenerate loop can version the copy and attach its `note` in place while every other terminal decision
  (`APPROVED`/`REJECTED`/`ACKNOWLEDGED`/`EXPIRED`/`ABORTED`) stays read-only; threaded automation replies
  were already allowed post-decision. Editing does not silently re-open the review (the status is
  unchanged).
- **Deleting a build now clears its interactive notifications and marks its review page.** Observed
  failure: after a build was deleted, its `askInteractive` question and its Interactive View review
  still counted toward the header-bell total and the sidebar badge, and the per-job Interactive View
  page kept a live link to the now-missing build. Root cause: nothing observed build/run deletion, and
  the notification predicates in `QuestionStore`/`ViewStore` only checked that the *job* still existed,
  never the *build*. Fix (`store/BuildLifecycleCleanup`): a `RunListener#onDeleted` purges that build's
  questions (`QuestionStore#removeForBuild`) and flags its reviews `buildDeleted`
  (`ViewStore#markBuildDeletedForBuild`) so they drop out of both notification queries while the review
  record is retained as history; an `ItemListener#onLoaded` reconcile self-heals orphans left by builds
  deleted before this release (`reconcileDeletedBuilds`). The per-job page (`InteractiveViewJobAction`
  + `index.jelly`) now renders a *build deleted* pill and drops the dead per-build link. The flag is a
  cheap boolean read on the poll hot-path (no per-notification `getBuildByNumber` lookup).
- **`interactiveView` / `interactiveOutput` now default *on* after an upgrade.** Found during live
  validation on 2.568.1: a controller that already had a saved System config (from a build predating
  these two flags) loaded them as `false`, silently hiding the new surfaces. XStream instantiates the
  `Features` object without running field initialisers, so plain `boolean` fields defaulted to `false`
  for any element absent from the persisted `<features>` block. Both new flags are now nullable
  `Boolean`, so "absent in the saved XML" means default-on via the getters, while an explicit
  `true`/`false` from the System form or JCasC is still honoured. (The five pre-existing flags are
  unaffected — they are always present in any saved config.)
- **Notification bell now appears inside a pipeline/job, not only on the dashboard.** The shared
  `bell.js` adjunct is emitted by `jobMain.jelly` in the job page's *main panel* — earlier in the
  document than the footer bell mount (`#interactive-input-bell`, a `PageDecorator`) and the sidebar
  `[data-ii-tasklink]` controller. The script collected its mounts at top-level execution, so on a job
  page those elements did not exist yet and neither the bell nor the live sidebar controller mounted
  (on the dashboard there is no `jobMain.jelly`, so it worked). Mount discovery + bootstrap now run on
  `DOMContentLoaded`, so every surface mounts regardless of where the adjunct is emitted.
- **Left-sidebar "Interactive Input (N)" count updates live.** Two causes: the poller
  (`mountTaskLink`) never ran on job pages (same bootstrap-timing bug above), and its href match was
  exact while core renders the link *without* a trailing slash (`…/interactive-input`) — so even when
  it ran it failed to find the existing link and cloned a duplicate. The match is now
  slash-insensitive (`normPath`), and the count re-labels / the row hides live on answer (via the poll
  and the `ii:answered` event) without a page reload.
- **Series modal no longer loses a half-typed answer.** Paging between questions re-fetched each one and
  rebuilt the form empty, discarding unsubmitted free text / the selected choice. The pager now snapshots
  a per-question **draft** before navigating and restores it, rendering each slide from the
  already-fetched list item instead of re-fetching.
- **Stale ("dummy") bridged notifications now clear promptly.** When a native `input` (surfaced by
  `inputStepBridge`) was answered through the built-in console/stage-view UI, our mirror stayed WAITING
  — showing a stale entry in the bell and a stale build-list badge — until the next 30s ticker
  reconciliation, which was the only cleanup path. `GET /questions?job=<name>` now calls a **scoped
  bridge reconcile** (`InputStepBridge#reconcile(String)`) before listing, so a pipeline's surfaces
  self-heal within one poll (≤15s) without disturbing other jobs. The 30s ticker still reconciles
  every job (`sync()` = `reconcile(null)`).
- **Answers refresh every surface immediately.** Answering in any modal dispatches an `ii:answered`
  DOM event; the bell, per-project box and build-list badge listen for it and refresh at once instead
  of waiting for their next poll (the answered build's badge is removed once nothing is left waiting).
- **Build-history badge blinks everywhere.** The "awaiting input" badge (`badge.jelly`) pulls in the
  shared style adjunct itself, so the slow red attention pulse renders in the build list even when both
  the header bell (`notificationCentre`) and the job-page box (`jobPageBox`) are off — previously the
  badge relied on one of those surfaces to have loaded `bell.css`. Adjunct includes are idempotent, so
  no double-load. This applies to every waiting build, including native `input` builds surfaced by
  `inputStepBridge`.
- **Inline job-page box now appears live, not only after a browser reload.** `jobMain.jelly` rendered
  the box's `[data-ii-widget]` mount only when `pendingCount > 0` at server-render time, so a question
  raised *after* the page loaded had no mount to poll and reveal it (the sidebar/bell updated live via
  their always-present controllers, but the box did not). The box is now always rendered (gated only on
  `jobPageBox`), starting hidden with `jenkins-hidden` when nothing is pending; `bell.js`
  (`mountJobWidget`) reveals/hides its `.ii-jobcard` wrapper as the polled count changes.
- **Browser-tab pending badge now shows with a cross-origin custom favicon.** A canvas may only read
  pixels from a **same-origin** image, so a dot cannot be composited onto a theme's cross-origin favicon
  (verified live: a Simple Theme plugin favicon on `nokia.com` returns `Access-Control-Allow-Origin:
  *.nokia.com`, which does not match the Jenkins origin, so the `crossOrigin="anonymous"` load fails; the
  controller cannot proxy it either — it gets HTTP 403). The notifier now chooses its presentation per
  poll from the active favicon: **same-origin** → a red dot painted on top of the favicon (title left
  clean); **cross-origin or missing** → a red-circle glyph (U+1F534 emoji) + `(N)` prefix on the tab
  **title**, with the favicon left exactly as the theme set it. It never replaces the site favicon. Gated
  by the `tabNotificationBadge` Appearance toggle (on by default).
- **Inline comments no longer blank the review pane.** Found during live validation: posting an inline
  comment (or toggling *resolved*) made the left pane disappear until a manual page reload. Root cause:
  the `comments` / `resolveComment` REST endpoints returned a **summary** JSON (no `content` /
  `renderedHtml`), and the editor replaced its detail state with that response — so the source/rendered
  view had nothing to draw. Both endpoints now return the **full** document (as `edit` / `decision`
  already did); `viewer.js` also keeps the prior `content`/`renderedHtml` defensively if any future
  response omits them.
- **Decided reviews' comments & history stay reachable after the build completes.** The job-level
  "Interactive View" sidebar link was gated on *open* reviews only (`getPendingCount() > 0`), so once a
  review was decided the cross-build listing — and the path to its comments/version history —
  disappeared. The link now shows whenever the job has **any readable review** (`isHasAnyReviews`,
  mirroring the output action's `hasAnyOutput`), while the live badge still counts only pending reviews.
- **Review-card "Notified" badge no longer overlaps the comment count.** The Interactive View list/card
  "Notified" pill used the class `iv-badge`, which also names a 10&nbsp;px circular build-history dot in the
  globally-loaded `bell.css`. On the review pages both stylesheets load, so the rules merged and forced the
  pill to a 10&nbsp;px circle — its label overflowed onto the adjacent "N comment(s)". The pill is renamed
  `iv-flag` (a distinct class), removing the collision regardless of stylesheet load order.
- **New surfaces are reachable under the experimental job/build layout — without a duplicate link.** The
  experimental layout removes the classic `#tasks` sidebar and routes action contributions into a "Legacy"
  card. Reachability is provided by core's native **"more actions" overflow menu**, which lists any action
  exposing an icon + display name (both job actions do, gated on having a readable review / output). An
  earlier hidden `data-ii-exp-link` fallback link (revealed by `bell.js`) has been **removed**: under the
  experimental layout it landed in the "Legacy" card and produced a **duplicate** "Interactive View" entry
  (`bell.js` could also duplicate its label text). All new UI uses design-system classes / theme variables
  and renders correctly in both classic and experimental layouts, light and dark.

### Security
- **Addressed the Jenkins Security Scan findings from hosting request review (#5166, reported by
  Kevin-CB).** Four findings against `ApiRootAction`, all in read-only `GET` endpoints:
  - `V1#doHealth` no longer discloses the instance-wide pending-question count to anonymous or
    unprivileged callers. The endpoint stays reachable without a permission check (it is an intentional,
    unauthenticated liveness probe for load balancers/uptime monitors that cannot present credentials,
    documented at `GET /interactive-input/api/v1/health`), but `pending` is now only populated when the
    caller already has `Jenkins.READ` — the same instance-wide count a `Jenkins.READ` holder could already
    obtain from `Questions#doIndex`. An anonymous or unprivileged caller now only ever learns liveness
    (`status: ok`).
  - `V1#doHealth`, `Questions#doIndex` and `QuestionEndpoint#doIndex` were flagged for a missing
    `@POST`/`@RequirePOST` (CSRF) annotation. All three are side-effect-free reads that are already
    permission-checked in-method (`Jenkins.READ`, or `QuestionStore#canView`) — confirmed false positives
    per the [Jenkins CodeQL guidance](https://github.com/jenkins-infra/jenkins-codeql/blob/main/src/WebMethodMissingPostAnnotation.md).
    Each now carries an explicit `@GET` annotation (Stapler-enforced GET-only, defense in depth beyond
    just silencing the scanner) plus an `lgtm[jenkins/csrf]` suppression with an inline explanation.

### Notes / trade-offs
- The durable audit record is the build console line; the per-build audit *page* is a live view of the
  store and shows an empty state after retention compaction.
- **Stage View / Pipeline Graph View "input required" cell** is owned by the core `input`/stage-view
  plumbing (keyed off `InputAction`). With `inputStepBridge` on, native `input` steps keep that cell
  *and* mirror into our surfaces. `askInteractive` advertises its pause through our own surfaces (box,
  pulsing badge, sidebar, bell, anchored console link) rather than drawing the native cell.

### Documentation
- **README scenario gallery + guided tour.** New *Human-in-the-loop scenarios* section with annotated
  screenshots of all six agent shapes (approve/deny, single option, multiple choice, multiple choice +
  user input, free text, series slider) plus a list of non-AI (human/CI-driven) uses; a *Drive it from
  an AI agent (Python)* section with the Cursor-SDK wiring and one argument snippet per shape; and a
  *Settings and screens* tour of the Appearance settings, the per-pipeline notification preview, and the
  per-build Interactive Input audit page (screenshots under `docs/screenshots/`).
- **Search / discoverability.** Refined the plugin `<description>` for plugin-site search, and documented
  the recommended Marketplace labels as GitHub topics (`ai`, `notification`, `ui`, `devops`) in
  `HOSTING.md` §1.6 — with the rationale and the topics deliberately excluded (`pipeline`, `agent`).
- **README install section removed; version references updated (review item B23).** Dropped the
  hand-rolled *Install* section (and its ToC entry) in favour of the plugin's Marketplace page, and
  updated the baseline references to Jenkins **2.568.1+** / Java **21** (the 2.568 baseline requires
  Java 21).

## [0.1.0] - 2026-07-19

Initial release.

### Added
- **`askInteractive` pipeline step** — a durable human-in-the-loop input step that surfaces in the
  notification bell and rich modal. Returns the chosen id (or free text), throws on abort, and times
  out on SLA. Parameters: `prompt`, `choices` (`[id, label, why]`), `allowFreeText`, `slaMinutes`,
  `submitterFilter`, `contextMarkdown` (`escalation` reserved for v0.2).
- **Notification bell** (`PageDecorator`) with an unread count scoped to what the current user may
  answer; proxy-friendly polling (no SSE/WebSocket), cadence configurable with a 5s floor.
- **Rich modal** (vanilla JS) — Markdown context panel, radio choices with per-choice rationale,
  optional free-text with a live server-sanitised preview, and full keyboard/focus-trap accessibility.
- **Versioned REST API** under `/interactive-input/api/v1/`: `health`, `questions`, `questions/{id}`,
  `questions/{id}/answer`, `questions/{id}/abort`, and `preview`. JSON envelope, permission-checked,
  CSRF-protected.
- **`inputStepBridge`** (opt-in) — mirrors pending native `input` steps into the bell/modal/API and
  forwards answers/aborts back to the native step (honours `submitterParameter`); drops stale mirrors.
- **Durable `QuestionStore`** — XStream-persisted question registry with concurrency-safe transitions
  and transient resolvers re-attached on step resume (survives controller restart).
- **SLA + retention** via an `AsyncPeriodicWork` ticker: expire overdue questions; compact terminal
  ones after `retentionDays`.
- **JCasC support** — full configuration under `unclassified.interactiveInput`
  (`features`, `polling`, `sla`, `retentionDays`); every capability is a feature flag.
- **Safe Markdown rendering** — commonmark configured to escape raw HTML and sanitise URLs.
- **Test suite** — 39 JUnit 5 tests across model, markdown, step, JCasC round-trip, permissions/SLA,
  REST, and the input bridge.

### Security
- All mutating endpoints are `@RequirePOST` (CSRF-protected) and enforce `pipeline-input-step`-parity
  permissions; `GET /questions/{id}` avoids existence leaks; `?all=true` is admin-gated.

### Compatibility
- Requires Jenkins **2.555.2+**, Java **17+**, and `pipeline-input-step` **≥ 560**.
- `commonmark` **0.24.0** is bundled; `configuration-as-code` is an optional runtime dependency.

[Unreleased]: https://github.com/jenkinsci/interactive-input-plugin/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/jenkinsci/interactive-input-plugin/releases/tag/v0.1.0
