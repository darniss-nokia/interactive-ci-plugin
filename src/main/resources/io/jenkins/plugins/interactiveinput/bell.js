/**
 * © 2026 Nokia
 * Licensed under the MIT License
 * SPDX-License-Identifier: MIT
*/

/*
 * Interactive Input — shared client (vanilla JS, no framework).
 *
 * One adjunct drives every surface:
 *   - the (opt-in) global nav bell  ................  #interactive-input-bell
 *   - per-project job widgets  ....................  [data-ii-widget]  (data-job)
 *   - per-build audit widgets  ....................  [data-ii-audit]   (data-job + data-build)
 *
 * The modal, answer submission and markdown preview are shared so the bell and the scoped widgets
 * behave identically. All user-supplied text is inserted via textContent; only server-sanitised HTML
 * (contextHtml / markdown preview) is inserted as innerHTML.
 */
(function () {
  "use strict";

  if (window.__interactiveInputLoaded) {
    return;
  }

  function toArray(nodeList) {
    return Array.prototype.slice.call(nodeList || []);
  }

  // Mounts are discovered on DOM ready (see boot()), NOT here. On a job/pipeline page this adjunct is
  // emitted by jobMain.jelly in the MAIN PANEL — i.e. BEFORE the sidebar [data-ii-tasklink] controller
  // (its next sibling) and the footer #interactive-input-bell are parsed. Querying at script-execution
  // time therefore misses them, which is why the bell was absent inside a job and the sidebar "(N)"
  // count never updated live. Discovering after the DOM is parsed fixes both without touching layout.
  let bellMount = null;
  let widgetMounts = [];
  let auditMounts = [];
  let taskLinkMounts = [];
  let runBoxMounts = [];
  let autoPopupMounts = [];
  // No early return when there are no mounts: build-history badges ([data-ii-badge]) are injected
  // lazily by the async build-history widget, so they may not exist yet at load. A delegated click
  // handler (wired at the bottom) covers them; the polling mounts are still set up conditionally.
  window.__interactiveInputLoaded = true;

  // ----- shared config (all mounts share the same Jenkins origin) -----
  function attr(node, name, dflt) {
    const v = node ? node.getAttribute(name) : null;
    return v == null ? dflt : v;
  }
  // Shared config, (re)computed from the first mount present once the DOM is ready (see discover()).
  // Safe defaults keep the delegated badge handler usable on a badge-only page before discovery runs.
  let cfgSrc = null;
  let rootUrl = "";
  let apiBase = rootUrl + "/interactive-input/api/v1";
  let richModalDefault = true;
  let pollSeconds = 15;

  // When the only surface on the page is a build-history badge (no mount to read config from), adopt
  // the origin from the clicked badge's data-root-url so API calls resolve under any context path.
  function adoptRootUrl(node) {
    if (cfgSrc) {
      return;
    }
    const ru = node && node.getAttribute ? node.getAttribute("data-root-url") : null;
    if (ru != null) {
      rootUrl = ru.replace(/\/$/, "");
      apiBase = rootUrl + "/interactive-input/api/v1";
    }
  }

  // ----- small DOM helpers -----
  function el(tag, opts) {
    const e = document.createElement(tag);
    opts = opts || {};
    if (opts.cls) e.className = opts.cls;
    if (opts.text != null) e.textContent = opts.text;
    if (opts.html != null) e.innerHTML = opts.html;
    if (opts.attrs) {
      Object.keys(opts.attrs).forEach(function (k) {
        e.setAttribute(k, opts.attrs[k]);
      });
    }
    return e;
  }

  function noop() {}

  // Let every surface on the page (bell, per-project widgets, build-list badges) update immediately
  // when a question is answered from any modal here, instead of waiting for the next poll.
  function announceAnswered(q) {
    try {
      document.dispatchEvent(
        new CustomEvent("ii:answered", { detail: { id: q.id, job: q.jobFullName, build: q.buildNumber } })
      );
    } catch (e) {
      /* CustomEvent unsupported: surfaces still refresh on their next poll. */
    }
  }

  function fetchJson(url, options) {
    options = options || {};
    options.headers = options.headers || {};
    options.headers["Accept"] = "application/json";
    options.credentials = "same-origin";
    return fetch(url, options).then(function (resp) {
      const ct = resp.headers.get("content-type") || "";
      const parse = ct.indexOf("application/json") >= 0 ? resp.json() : resp.text();
      return parse.then(function (body) {
        return { ok: resp.ok, status: resp.status, body: body };
      });
    });
  }

  // B15: Jenkins publishes a global `crumb` object (from hudson-behavior.js, present on every page)
  // whose wrap() adds the CSRF request header — so there is no need to fetch /crumbIssuer ourselves.
  // Accessed via window.crumb so a page/test harness without the core script (or with CSRF disabled)
  // degrades gracefully instead of throwing on an undefined identifier.
  function postJson(url, payload) {
    const base = { "Content-Type": "application/json" };
    const headers =
      window.crumb && typeof window.crumb.wrap === "function" ? window.crumb.wrap(base) : base;
    return fetchJson(url, { method: "POST", headers: headers, body: JSON.stringify(payload || {}) });
  }

  // ----- shared labels/formatters -----
  function refLabel(q) {
    return q.jobFullName + " #" + q.buildNumber;
  }

  // ----- live SLA countdown + progress bar (shared by the box rows and the open dialog) -----
  // The step supports an SLA/timeout (slaMinutes -> Question.expiresAt); the REST payload carries
  // slaMs / expiresAt / remainingMs. We show a running counter and, in the job box, a progress bar that
  // "ticks" as time runs out. Values are recomputed locally from the remaining time captured at fetch
  // (not from the absolute expiresAt) so a server/client clock skew never makes the counter wrong.
  function fmtCountdown(ms) {
    const totalSec = Math.max(0, Math.floor(ms / 1000));
    const h = Math.floor(totalSec / 3600);
    const m = Math.floor((totalSec % 3600) / 60);
    const s = totalSec % 60;
    const two = function (n) {
      return n < 10 ? "0" + n : String(n);
    };
    return h > 0 ? h + ":" + two(m) + ":" + two(s) : m + ":" + two(s);
  }

  function remainingNow(node) {
    const rem0 = parseInt(node.getAttribute("data-ii-remaining"), 10);
    const fetched = parseInt(node.getAttribute("data-ii-fetched"), 10);
    if (isNaN(rem0) || isNaN(fetched)) {
      return -1;
    }
    return Math.max(0, rem0 - (Date.now() - fetched));
  }

  // Threshold (ms) under which the counter/bar turn red to signal urgency.
  const SLA_URGENT_MS = 60000;

  function updateCountdownEl(node) {
    const sla = parseInt(node.getAttribute("data-ii-sla"), 10) || 0;
    const rem = remainingNow(node);
    if (rem < 0) {
      return;
    }
    const text = node.querySelector(".ii-countdown-text");
    if (text) {
      text.textContent = rem > 0 ? "Time left " + fmtCountdown(rem) : "Time is up";
    }
    const bar = node.querySelector(".app-progress-bar");
    if (bar) {
      const fill = bar.querySelector("span");
      if (fill && sla > 0) {
        fill.style.width = Math.max(0, Math.min(100, (rem / sla) * 100)) + "%";
      }
      // core's own modifier draws the bar in --error-color; theme-aware for free.
      bar.classList.toggle("app-progress-bar--error", rem <= SLA_URGENT_MS);
    }
    node.classList.toggle("ii-countdown-urgent", rem <= SLA_URGENT_MS);
  }

  // Build a live countdown element for a question with an SLA. `withBar` adds the native progress bar
  // (used in the job box); the bell dropdown and dialog show the counter text only. Returns null when
  // the question has no SLA.
  function buildCountdown(q, withBar) {
    if (!(q.slaMs > 0) || q.remainingMs < 0) {
      return null;
    }
    const wrap = el("span", {
      cls: "ii-countdown",
      attrs: {
        "data-ii-remaining": String(q.remainingMs),
        "data-ii-sla": String(q.slaMs),
        "data-ii-fetched": String(Date.now())
      }
    });
    wrap.appendChild(el("span", { cls: "ii-countdown-text" }));
    if (withBar) {
      // Replicate core's <t:progressBar> markup (app-progress-bar + an inner span sized by width%) so
      // the bar is fully theme-aware. The inner span carries core's --animate modifier so it shows the
      // native moving "ticking" stripes, while the shared ticker keeps its width in sync with the time
      // left (updated every second — see updateCountdownEl).
      const bar = el("div", {
        cls: "app-progress-bar ii-sla-bar",
        attrs: { role: "progressbar", "aria-label": "Time remaining to answer" }
      });
      bar.appendChild(el("span", { cls: "app-progress-bar--animate" }));
      wrap.appendChild(bar);
    }
    updateCountdownEl(wrap);
    return wrap;
  }

  function tickCountdowns() {
    toArray(document.querySelectorAll(".ii-countdown")).forEach(updateCountdownEl);
  }
  // One visibility-aware 1s ticker drives every live countdown/bar on the page (box rows + open dialog)
  // with no extra network traffic.
  setInterval(function () {
    if (!document.hidden) {
      tickCountdowns();
    }
  }, 1000);

  function fmtTime(ts) {
    if (!ts) return "";
    try {
      return " on " + new Date(ts).toLocaleString();
    } catch (e) {
      return "";
    }
  }

  function choiceLabelOf(q, choiceId) {
    if (q.choices) {
      for (let i = 0; i < q.choices.length; i++) {
        if (q.choices[i].id === choiceId) return q.choices[i].label;
      }
    }
    return choiceId;
  }

  function outcomeText(q) {
    const a = q.answer;
    if (q.status === "ANSWERED" && a) {
      const who = a.answeredBy || "unknown";
      if (a.choiceId === "__deny__") return "Denied by " + who + " — continued" + fmtTime(a.answeredTs);
      if (a.choiceId === "__skip__") return "Skipped by " + who + fmtTime(a.answeredTs);
      if (a.choiceId) return "Answered by " + who + ": " + choiceLabelOf(q, a.choiceId) + fmtTime(a.answeredTs);
      if (a.freeText) return "Answered by " + who + ": " + a.freeText + fmtTime(a.answeredTs);
      if (a.parameters) {
        const n = Object.keys(a.parameters).length;
        return "Answered by " + who + ": " + n + (n === 1 ? " parameter" : " parameters") + fmtTime(a.answeredTs);
      }
      return "Answered by " + who + fmtTime(a.answeredTs);
    }
    if (q.status === "ABORTED") {
      return "Aborted" + (a && a.answeredBy ? " by " + a.answeredBy : "") + (a ? fmtTime(a.answeredTs) : "");
    }
    if (q.status === "EXPIRED") return "Expired: SLA elapsed with no answer";
    if (q.status === "WAITING") return "Still waiting for an answer.";
    return q.status || "";
  }

  function shortOutcome(q) {
    const t = outcomeText(q);
    return t.length > 80 ? t.slice(0, 80) + "…" : t;
  }

  function statusPill(status) {
    return el("span", { cls: "ii-pill ii-pill-" + (status || "").toLowerCase(), text: status || "" });
  }

  // ----- shared list rows -----
  function questionListItem(q, onClick, opts) {
    opts = opts || {};
    const link = el("button", { cls: "ii-item", attrs: { type: "button", role: "menuitem" } });
    link.appendChild(el("span", { cls: "ii-item-prompt", text: q.prompt }));
    link.appendChild(el("span", { cls: "ii-item-ref", text: refLabel(q) }));
    if (q.startedBy) {
      link.appendChild(el("span", { cls: "ii-item-by", text: "started by " + q.startedBy }));
    }
    // Live SLA counter; the job box additionally shows the ticking progress bar (opts.withBar).
    const countdown = buildCountdown(q, !!opts.withBar);
    if (countdown) {
      link.appendChild(countdown);
    }
    link.addEventListener("click", onClick);
    return link;
  }

  function auditRow(q, onClick) {
    const link = el("button", { cls: "ii-item", attrs: { type: "button" } });
    link.appendChild(el("span", { cls: "ii-item-prompt", text: q.prompt }));
    const meta = el("span", { cls: "ii-item-ref" });
    meta.appendChild(statusPill(q.status));
    if (q.startedBy) {
      meta.appendChild(el("span", { cls: "ii-item-by", text: " started by " + q.startedBy }));
    }
    link.appendChild(meta);
    link.appendChild(el("span", { cls: "ii-item-outcome", text: shortOutcome(q) }));
    link.addEventListener("click", onClick);
    return link;
  }

  // A dropdown row for an interactiveView review. Unlike questions (which open a dialog in place),
  // clicking a review NAVIGATES to its editor page (v.url), satisfying "route it to a new page".
  function viewListItem(v, onClick) {
    const link = el("button", { cls: "ii-item", attrs: { type: "button", role: "menuitem" } });
    link.appendChild(el("span", { cls: "ii-item-prompt", text: v.title || v.reportName || "Review" }));
    const meta = el("span", { cls: "ii-item-ref" });
    meta.appendChild(statusPill(v.status));
    meta.appendChild(el("span", { text: " " + v.jobFullName + " #" + v.buildNumber }));
    link.appendChild(meta);
    // Attribute the review to whoever started the triggering build, mirroring the question rows above
    // (the server sets createdBy from the build's cause, so it reads "started by <user|scm|timer|…>").
    if (v.createdBy) {
      link.appendChild(el("span", { cls: "ii-item-by", text: "started by " + v.createdBy }));
    }
    const c = v.commentCount || 0;
    link.appendChild(el("span", { cls: "ii-item-by", text: c + " comment" + (c === 1 ? "" : "s") }));
    link.addEventListener("click", onClick);
    return link;
  }

  // ================================ shared modal ================================
  let activeModal = null;
  let lastFocused = null;

  function teardownDialog(dialog) {
    if (dialog && dialog.parentNode) {
      dialog.parentNode.removeChild(dialog);
    }
    if (lastFocused && lastFocused.focus) {
      lastFocused.focus();
    }
  }

  // Close the shared dialog. Native <dialog>.close() drops the modal/top-layer + backdrop; we then
  // remove the element and restore focus. Escape (cancel) and backdrop-click are routed here too (see
  // openDialog) for a single teardown path. Focus-trapping is provided natively by showModal().
  function closeModal() {
    if (!activeModal) {
      return;
    }
    const dialog = activeModal;
    activeModal = null;
    try {
      if (dialog.open && typeof dialog.close === "function") {
        dialog.close();
      }
    } catch (e) {
      /* not an open native dialog */
    }
    teardownDialog(dialog);
  }

  function navigateToInput(q) {
    window.location.href =
      rootUrl + "/job/" + q.jobFullName.split("/").join("/job/") + "/" + q.buildNumber + "/input/";
  }

  /**
   * Open a question. opts: { readOnly:bool, richModal:bool, onDone:fn }.
   * Read-only (audit) rows already carry full detail (contextHtml + answer); interactive opens fetch
   * the freshest detail first.
   */
  function openQuestion(q, opts) {
    opts = opts || {};
    const readOnly = !!opts.readOnly;
    const richModal = opts.richModal != null ? opts.richModal : richModalDefault;
    if (!readOnly && !richModal) {
      navigateToInput(q);
      return;
    }
    if (readOnly && q.contextHtml != null) {
      showModal(q, opts);
      return;
    }
    fetchJson(apiBase + "/questions/" + encodeURIComponent(q.id))
      .then(function (r) {
        showModal(r.ok ? r.body : q, opts);
      })
      .catch(function () {
        showModal(q, opts);
      });
  }

  // Build the shared dialog as core's native <dialog class="jenkins-dialog"> (B8, Jenkins >= 2.560):
  // the shell, backdrop, elevation, focus-trap, Escape handling and light/dark theming come from core.
  // We render the rich body (context, choices, free-text preview, series pager) into
  // .jenkins-dialog__contents ourselves; the buttons use the native design-library button classes.
  function buildModalShell(q, readOnly) {
    const titleId = "ii-modal-title-" + q.id;
    const dialog = el("dialog", {
      cls: "jenkins-dialog ii-dialog",
      attrs: { "aria-labelledby": titleId }
    });

    const titleBar = el("div", { cls: "jenkins-dialog__title" });
    titleBar.appendChild(el("span", { text: q.prompt, attrs: { id: titleId } }));
    // Native close button = jenkins-button (theme-aware background/hover/colour) + __title__button (the
    // round 2rem icon-button shape core defines) + __title__close-button (margin-left:auto, trailing
    // edge). Using only __title__close-button dropped the shape and native hover, so it looked off.
    const closeBtn = el("button", {
      cls: "jenkins-button jenkins-dialog__title__button jenkins-dialog__title__close-button",
      attrs: { type: "button", "aria-label": "Close" }
    });
    closeBtn.innerHTML =
      '<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" focusable="false">' +
      '<path d="M6 6l12 12M18 6L6 18" stroke="currentColor" stroke-width="2" ' +
      'stroke-linecap="round" fill="none"/></svg>';
    closeBtn.addEventListener("click", closeModal);
    titleBar.appendChild(closeBtn);
    dialog.appendChild(titleBar);

    // Body container: core gives it the 1.25rem side padding + scroll. The subtitle is the first row
    // INSIDE it — core's .jenkins-dialog__subtitle has padding:0, so as a direct child of the padless
    // <dialog> it sat flush against the edge; inside __contents it aligns with the title and body.
    const contents = el("div", { cls: "jenkins-dialog__contents" });

    const sub = el("div", { cls: "jenkins-dialog__subtitle" });
    sub.appendChild(el("span", { text: refLabel(q) }));
    if (q.startedBy) {
      sub.appendChild(el("span", { cls: "ii-sub-by", text: " · started by " + q.startedBy }));
    }
    if (readOnly && q.status) {
      sub.appendChild(statusPill(q.status));
    }
    contents.appendChild(sub);

    // Live SLA counter in the dialog while the question is still waiting (item 3).
    if (!q.status || q.status === "WAITING") {
      const countdown = buildCountdown(q, false);
      if (countdown) {
        countdown.classList.add("ii-countdown-dialog");
        contents.appendChild(countdown);
      }
    }

    if (q.contextHtml) {
      // Expanded by default so reviewers see the context without an extra click.
      const details = el("details", { cls: "ii-context", attrs: { open: "open" } });
      details.appendChild(el("summary", { text: "Context" }));
      details.appendChild(el("div", { cls: "ii-context-body", html: q.contextHtml }));
      contents.appendChild(details);
    }
    dialog.appendChild(contents);
    return dialog;
  }

  // The scrollable body container that renderForm / renderAudit / the series pager append into.
  function dialogBody(dialog) {
    return dialog.querySelector(".jenkins-dialog__contents") || dialog;
  }

  function renderAudit(modal, q) {
    const body = dialogBody(modal);
    if (q.choices && q.choices.length) {
      const chosen = q.answer && q.answer.choiceId;
      const ul = el("ul", { cls: "ii-audit-choices" });
      q.choices.forEach(function (c) {
        const li = el("li", { cls: "ii-audit-choice" + (c.id === chosen ? " ii-chosen" : "") });
        li.appendChild(el("span", { cls: "ii-choice-label", text: c.label + (c.id === chosen ? "  ✓" : "") }));
        if (c.why) li.appendChild(el("span", { cls: "ii-choice-why", text: c.why }));
        ul.appendChild(li);
      });
      body.appendChild(ul);
    }
    // B24: for a parameterized answer, list the submitted name/value pairs (secrets already redacted
    // server-side). Values arrive as strings/booleans, so String() is safe.
    if (q.answer && q.answer.parameters && typeof q.answer.parameters === "object") {
      const pul = el("ul", { cls: "ii-audit-params" });
      Object.keys(q.answer.parameters).forEach(function (name) {
        const li = el("li", { cls: "ii-audit-param" });
        li.appendChild(el("span", { cls: "ii-choice-label", text: name }));
        li.appendChild(el("span", { cls: "ii-choice-why", text: String(q.answer.parameters[name]) }));
        pul.appendChild(li);
      });
      body.appendChild(pul);
    }
    const outcome = el("div", { cls: "ii-audit-outcome" });
    outcome.appendChild(el("div", { cls: "ii-audit-outcome-title", text: "Outcome" }));
    outcome.appendChild(el("div", { text: outcomeText(q) }));
    body.appendChild(outcome);

    const actions = el("div", { cls: "ii-actions jenkins-dialog__buttons" });
    const closeBtn = el("button", { cls: "jenkins-button", text: "Close", attrs: { type: "button" } });
    closeBtn.addEventListener("click", closeModal);
    actions.appendChild(closeBtn);
    body.appendChild(actions);
  }

  // B24: render the native input-style parameter controls (string / text / boolean / choice /
  // password) and return a reader that collects { name: value } for the answer POST. A type the
  // dialog cannot render is surfaced read-only and flagged via `unsupported` so the caller can disable
  // submit rather than silently sending a wrong value. All labels/values go in via textContent.
  function renderParameters(container, params, initial) {
    const fieldset = el("fieldset", { cls: "ii-params" });
    fieldset.appendChild(el("legend", { text: "Provide the requested values" }));
    const readers = [];
    let unsupported = false;
    params.forEach(function (p, idx) {
      const row = el("div", { cls: "ii-param ii-param-" + p.type });
      const id = "ii-param-" + idx;
      const seed = initial && initial[p.name] != null ? initial[p.name] : p.default;

      // Boolean uses the native design-library checkbox: the real <input> is visually hidden and the
      // control is drawn by core CSS on the adjacent <label> (see core lib/form/checkbox.jelly). The
      // label — linked via `for` so a click toggles it without core JS — carries the parameter name, so
      // booleans skip the separate top label the other types get.
      if (p.type === "boolean") {
        const wrap = el("span", { cls: "jenkins-checkbox" });
        const control = el("input", { attrs: { type: "checkbox", id: id } });
        if (seed === true || seed === "true") {
          control.checked = true;
        }
        wrap.appendChild(control);
        wrap.appendChild(el("label", { cls: "attach-previous", text: p.name, attrs: { for: id } }));
        row.appendChild(wrap);
        if (p.description) {
          row.appendChild(el("div", { cls: "jenkins-checkbox__description", text: p.description }));
        }
        readers.push(function () {
          return [p.name, control.checked];
        });
        fieldset.appendChild(row);
        return;
      }

      row.appendChild(el("label", { cls: "ii-param-label", text: p.name, attrs: { for: id } }));
      if (p.description) {
        row.appendChild(el("span", { cls: "ii-param-desc", text: p.description }));
      }
      if (p.type === "choice") {
        // Native design-library select: <div class="jenkins-select"><select class="jenkins-select__input">.
        const selectWrap = el("div", { cls: "jenkins-select" });
        const control = el("select", { cls: "jenkins-select__input", attrs: { id: id } });
        (p.choices || []).forEach(function (c) {
          const opt = el("option", { text: c, attrs: { value: c } });
          if (seed != null && String(seed) === String(c)) {
            opt.selected = true;
          }
          control.appendChild(opt);
        });
        selectWrap.appendChild(control);
        row.appendChild(selectWrap);
        readers.push(function () {
          return [p.name, control.value];
        });
      } else if (p.type === "text") {
        const control = el("textarea", { cls: "jenkins-input", attrs: { id: id, rows: "3" } });
        if (seed != null) {
          control.value = String(seed);
        }
        row.appendChild(control);
        readers.push(function () {
          return [p.name, control.value];
        });
      } else if (p.type === "password" || p.type === "string") {
        const control = el("input", {
          cls: "jenkins-input",
          attrs: { type: p.type === "password" ? "password" : "text", id: id }
        });
        if (p.type === "password") {
          control.setAttribute("autocomplete", "new-password");
        }
        if (seed != null) {
          control.value = String(seed);
        }
        row.appendChild(control);
        readers.push(function () {
          return [p.name, control.value];
        });
      } else {
        unsupported = true;
        row.appendChild(
          el("div", {
            cls: "ii-param-unsupported",
            text: "This parameter type isn't supported in the dialog yet — open the build's input page."
          })
        );
      }
      fieldset.appendChild(row);
    });
    container.appendChild(fieldset);
    return {
      unsupported: unsupported,
      read: function () {
        const out = {};
        readers.forEach(function (r) {
          const kv = r();
          out[kv[0]] = kv[1];
        });
        return out;
      }
    };
  }

  function renderForm(modal, q, opts) {
    const onDone = opts.onDone || noop;
    const body = dialogBody(modal);
    const form = el("form", { cls: "ii-form" });
    const selectedChoice = { id: null };

    // B24: when the question declares input-style parameters, the human fills those in instead of
    // picking a choice / typing free text; the submitted values are returned to the pipeline.
    const params = Array.isArray(q.parameters) ? q.parameters : [];
    const paramsMode = params.length > 0;
    let paramForm = null;
    if (paramsMode) {
      paramForm = renderParameters(form, params, opts.initial ? opts.initial.parameters : null);
    }

    // In a series the caller passes opts.initial to restore a half-finished answer (draft) when the
    // user pages back to this question; otherwise the first choice is pre-selected as before.
    const initialChoiceId = opts.initial ? opts.initial.choiceId : null;
    let hasInitialChoice = false;
    if (initialChoiceId && q.choices) {
      hasInitialChoice = q.choices.some(function (c) {
        return c.id === initialChoiceId;
      });
    }

    if (!paramsMode && q.choices && q.choices.length) {
      const fieldset = el("fieldset", { cls: "ii-choices" });
      fieldset.appendChild(el("legend", { text: "Choose an option" }));
      q.choices.forEach(function (c, idx) {
        const row = el("label", { cls: "ii-choice" });
        const radio = el("input", { attrs: { type: "radio", name: "ii-choice", value: c.id } });
        if (hasInitialChoice ? c.id === initialChoiceId : idx === 0) {
          radio.checked = true;
          selectedChoice.id = c.id;
        }
        radio.addEventListener("change", function () {
          selectedChoice.id = c.id;
        });
        const textWrap = el("span", { cls: "ii-choice-text" });
        textWrap.appendChild(el("span", { cls: "ii-choice-label", text: c.label }));
        if (c.why) {
          textWrap.appendChild(el("span", { cls: "ii-choice-why", text: c.why }));
        }
        row.appendChild(radio);
        row.appendChild(textWrap);
        fieldset.appendChild(row);
      });
      form.appendChild(fieldset);
    }

    let freeTextArea = null;
    if (!paramsMode && q.allowFreeText) {
      const ftWrap = el("div", { cls: "ii-freetext" });
      const ftLabel = el("label", {
        text: "Or type an answer (markdown supported)",
        attrs: { for: "ii-ft-" + q.id }
      });
      freeTextArea = el("textarea", {
        attrs: { id: "ii-ft-" + q.id, rows: "3", "aria-label": "Free-text answer" }
      });
      // Restore a series draft so text typed before paging away is not lost.
      if (opts.initial && opts.initial.freeText) {
        freeTextArea.value = opts.initial.freeText;
      }
      const preview = el("div", { cls: "ii-preview", attrs: { "aria-live": "polite" } });
      let previewTimer = null;
      freeTextArea.addEventListener("input", function () {
        if (previewTimer) clearTimeout(previewTimer);
        previewTimer = setTimeout(function () {
          const val = freeTextArea.value;
          if (!val) {
            preview.innerHTML = "";
            return;
          }
          postJson(apiBase + "/preview", { markdown: val }).then(function (r) {
            if (r.ok && r.body && typeof r.body.html === "string") {
              preview.innerHTML = r.body.html; // server-sanitised
            }
          });
        }, 300);
      });
      ftWrap.appendChild(ftLabel);
      ftWrap.appendChild(freeTextArea);
      ftWrap.appendChild(el("div", { cls: "ii-preview-label", text: "Preview" }));
      ftWrap.appendChild(preview);
      form.appendChild(ftWrap);
    }

    const errBox = el("div", { cls: "ii-error", attrs: { role: "alert" } });
    form.appendChild(errBox);

    // Locked (Point 3): when the server reports the viewer may not answer this question (lock-to-
    // build-starter is on and they are not the owner), they can still read it but the controls are
    // disabled with an explanation. `canAnswer` is only present when the server computes it, so this
    // is a no-op for older payloads.
    const locked = q.canAnswer === false;
    const cannotAbort = q.canAbort === false;

    const actions = el("div", { cls: "ii-actions jenkins-dialog__buttons" });
    const answerBtn = el("button", {
      cls: "jenkins-button jenkins-button--primary",
      text: paramsMode ? "Submit" : "Answer",
      attrs: { type: "submit" }
    });
    // A rejection offers two explicit outcomes (defaulting to abort) so a human — or an AI via REST —
    // has a choice:
    //   * Deny (destructive) -> POST /abort -> the run is ABORTED, like the built-in input step;
    //   * Skip  (neutral)     -> answer with the "__skip__" sentinel -> the run RESUMES, so the pipeline
    //     can branch on it. Renamed from "Continue": "Skip" matches the returned "__skip__" marker and the
    //     "Skipped by …" audit line (the legacy "__deny__" marker still resolves identically for REST
    //     clients). "Skip" is meaningless for a bridged native input (proceed/abort only), so it is hidden
    //     there and when the answer is a set of parameter values (B24).
    const denyBtn = el("button", {
      cls: "jenkins-button jenkins-!-destructive-color",
      text: "Deny",
      attrs: { type: "button", tooltip: "Reject and abort the build" }
    });
    const skipBtn = el("button", {
      cls: "jenkins-button",
      text: "Skip",
      attrs: { type: "button", tooltip: "Skip without approving — the pipeline continues" }
    });
    const showSkip = q.bridged !== true && !paramsMode;
    // B27: a bridged native input that declares parameters is mirrored with no choices and no free
    // text, so it cannot be answered here. The server provides its build's input page URL as
    // forwardUrl; offer a link to that page (plus Deny, which aborts the native input) instead of an
    // Answer button that could only dead-end on "Pick a choice or type an answer".
    const canAnswerInModal = paramsMode || (q.choices && q.choices.length) || q.allowFreeText;
    const forwardUrl = typeof q.forwardUrl === "string" ? q.forwardUrl : "";
    const forwardMode = !canAnswerInModal && forwardUrl !== "";
    const forwardBtn = el("a", {
      cls: "jenkins-button jenkins-button--primary",
      text: "Open the build's input page",
      attrs: { href: forwardUrl, tooltip: "This input needs parameters — open the build to answer it" }
    });
    const cancelBtn = el("button", {
      cls: "jenkins-button",
      text: locked ? "Close" : "Cancel",
      attrs: { type: "button" }
    });
    if (locked) {
      const owner = q.startedBy ? " Only " + q.startedBy + " (the build starter) can answer it." : "";
      errBox.textContent = "Locked." + owner;
      [answerBtn, denyBtn, skipBtn].forEach(function (b) {
        b.disabled = true;
        b.setAttribute("aria-disabled", "true");
      });
    } else {
      if (cannotAbort) {
        denyBtn.disabled = true;
        denyBtn.setAttribute("aria-disabled", "true");
        denyBtn.setAttribute("tooltip", "Job/Cancel permission required to abort the build");
      }
      if (forwardMode) {
        actions.appendChild(forwardBtn);
        actions.appendChild(denyBtn); // Deny still aborts the underlying native input
      } else {
        actions.appendChild(answerBtn);
        if (showSkip) {
          actions.appendChild(skipBtn);
        }
        actions.appendChild(denyBtn);
      }
    }
    actions.appendChild(cancelBtn);
    // Item 4: spell out the consequence of a rejection so "Deny" is not mistaken for a soft dismiss — it
    // aborts the run (Result.ABORTED), mirroring the built-in input step. Shown whenever Deny is offered.
    if (!locked) {
      // Item 1: wrap the consequence note in parentheses, e.g.
      //   "(Deny will abort the build. Skip resumes the pipeline without approving.)"
      //   "(Deny will abort the build.)"
      const denyNote = el("div", { cls: "ii-deny-note", attrs: { role: "note" } });
      denyNote.appendChild(el("span", { text: "(" }));
      denyNote.appendChild(el("span", { cls: "ii-deny-note-strong", text: "Deny will abort the build." }));
      if (showSkip && !forwardMode) {
        denyNote.appendChild(el("span", { text: " Skip resumes the pipeline without approving." }));
      }
      denyNote.appendChild(el("span", { text: ")" }));
      form.appendChild(denyNote);
    }
    form.appendChild(actions);
    body.appendChild(form);

    // B24: a parameter type the dialog can't render (e.g. file/credentials) can't be answered here yet.
    if (!locked && paramsMode && paramForm.unsupported) {
      answerBtn.disabled = true;
      answerBtn.setAttribute("aria-disabled", "true");
      errBox.textContent =
        "Some parameter types aren't supported in the dialog yet — open the build's input page to answer.";
    }

    function busy(on) {
      answerBtn.disabled = on;
      denyBtn.disabled = on;
      skipBtn.disabled = on;
    }
    function fail(msg) {
      errBox.textContent = msg;
      busy(false);
    }
    function submit(payload) {
      busy(true);
      errBox.textContent = "";
      postJson(apiBase + "/questions/" + encodeURIComponent(q.id) + "/answer", payload)
        .then(function (r) {
          if (r.ok) {
            // In a series the pager keeps the overlay open and moves to the next question; a single
            // modal closes itself. announceAnswered fires either way so other surfaces refresh.
            if (!opts.keepOpen) {
              closeModal();
            }
            announceAnswered(q);
            onDone(payload);
          } else {
            fail((r.body && r.body.message) || "Failed (HTTP " + r.status + ")");
          }
        })
        .catch(function () {
          fail("Network error submitting the answer.");
        });
    }
    // B26 (Deny): POST /abort. The run is aborting, so always close — even inside a series — and let the
    // other surfaces refresh off the ii:answered event; we deliberately do not advance the series pager.
    function abort() {
      busy(true);
      errBox.textContent = "";
      postJson(apiBase + "/questions/" + encodeURIComponent(q.id) + "/abort", {})
        .then(function (r) {
          if (r.ok) {
            closeModal();
            announceAnswered(q);
          } else {
            fail((r.body && r.body.message) || "Failed to abort (HTTP " + r.status + ")");
          }
        })
        .catch(function () {
          fail("Network error aborting the input.");
        });
    }

    form.addEventListener("submit", function (e) {
      e.preventDefault();
      if (locked || forwardMode) {
        return; // forwardMode: the only action is the "Open the build's input page" link (B27)
      }
      if (paramsMode) {
        if (paramForm.unsupported) {
          fail("Some parameter types aren't supported in the dialog yet — open the build's input page.");
          return;
        }
        submit({ parameters: paramForm.read() });
        return;
      }
      const freeText = freeTextArea ? freeTextArea.value.trim() : "";
      if (freeText) {
        submit({ freeText: freeText });
      } else if (selectedChoice.id) {
        submit({ choiceId: selectedChoice.id });
      } else {
        fail("Pick a choice or type an answer.");
      }
    });
    denyBtn.addEventListener("click", function () {
      if (locked) {
        return;
      }
      abort();
    });
    skipBtn.addEventListener("click", function () {
      if (locked) {
        return;
      }
      submit({ choiceId: "__skip__" });
    });
    cancelBtn.addEventListener("click", closeModal);
  }

  // Open a fresh native dialog. showModal() provides the modal state, backdrop, focus-trap and Escape;
  // we route Escape (cancel) and backdrop-click through closeModal so there is one teardown path.
  function openDialog(dialog) {
    closeModal();
    lastFocused = document.activeElement;
    document.body.appendChild(dialog);
    activeModal = dialog;
    dialog.addEventListener("cancel", function (e) {
      e.preventDefault(); // control teardown ourselves (remove node + restore focus)
      closeModal();
    });
    dialog.addEventListener("click", function (e) {
      if (e.target === dialog) closeModal(); // click on the backdrop area
    });
    try {
      if (typeof dialog.showModal === "function") {
        dialog.showModal();
      }
    } catch (e) {
      /* fall through to the attribute fallback below */
    }
    if (!dialog.open) {
      // Environments without full <dialog> top-layer support (older engines / HtmlUnit) still need the
      // body displayed; showModal() already sets this in real browsers, so we never toggle a live modal.
      dialog.setAttribute("open", "open");
    }
    focusFirst(dialog);
    return dialog;
  }

  function focusFirst(dialog) {
    const scope = dialog.querySelector(".jenkins-dialog__contents") || dialog;
    const focusTarget = scope.querySelector("button, [href], input, textarea, select");
    if (focusTarget) focusTarget.focus();
  }

  // Swap the dialog body inside the currently-open native dialog (used by the series pager to move
  // between questions without tearing down/reopening, so there is no flicker). Falls back to opening a
  // fresh dialog when nothing is open yet.
  function replaceModal(dialog) {
    if (!activeModal) {
      return openDialog(dialog);
    }
    while (activeModal.firstChild) {
      activeModal.removeChild(activeModal.firstChild);
    }
    while (dialog.firstChild) {
      activeModal.appendChild(dialog.firstChild);
    }
    focusFirst(activeModal);
    return activeModal;
  }

  function showModal(q, opts) {
    opts = opts || {};
    const readOnly = !!opts.readOnly;
    const modal = buildModalShell(q, readOnly);
    if (readOnly) {
      renderAudit(modal, q);
    } else {
      renderForm(modal, q, opts);
    }
    openDialog(modal);
  }

  // ================================ series pager ================================
  // A single modal that pages through a *series* of questions with numbered navigation (‹ 2 / 5 ›
  // plus clickable numbered pips). Answering advances to the next still-waiting question in place;
  // already-answered ones render read-only so you can review what was chosen. A series is a build's
  // set of waiting questions (several published at once, e.g. by an agent); every surface reaches it
  // through openWaiting / openQuestionInSeries below.
  function buildSeriesNav(ctx) {
    const nav = el("div", { cls: "ii-series-nav" });
    const row = el("div", { cls: "ii-series-row" });
    const prev = el("button", { cls: "jenkins-button ii-series-prev", text: "‹ Prev", attrs: { type: "button" } });
    const pos = el("span", { cls: "ii-series-pos", text: ctx.index + 1 + " / " + ctx.list.length });
    const next = el("button", { cls: "jenkins-button ii-series-next", text: "Next ›", attrs: { type: "button" } });
    prev.disabled = ctx.index <= 0;
    next.disabled = ctx.index >= ctx.list.length - 1;
    prev.addEventListener("click", function () {
      ctx.goTo(ctx.index - 1);
    });
    next.addEventListener("click", function () {
      ctx.goTo(ctx.index + 1);
    });
    row.appendChild(prev);
    row.appendChild(pos);
    row.appendChild(next);
    nav.appendChild(row);

    const pips = el("div", { cls: "ii-series-pips" });
    ctx.list.forEach(function (q, i) {
      let cls = "ii-pip";
      if (i === ctx.index) cls += " ii-current";
      if (ctx.answered[q.id]) cls += " ii-done";
      const pip = el("button", {
        cls: cls,
        text: String(i + 1),
        attrs: { type: "button", "aria-label": "Go to question " + (i + 1) }
      });
      pip.addEventListener("click", function () {
        ctx.goTo(i);
      });
      pips.appendChild(pip);
    });
    nav.appendChild(pips);
    return nav;
  }

  function openSeries(questions, opts) {
    opts = opts || {};
    const list = (questions || []).filter(function (q) {
      return !q.status || q.status === "WAITING";
    });
    if (list.length <= 1) {
      if (list.length === 1) {
        openQuestion(list[0], opts);
      }
      return;
    }
    const answered = {};
    // Per-question drafts (unsubmitted free text / selected choice), keyed by question id, so paging
    // between slides no longer discards what the user typed. The list items already carry full detail
    // (prompt/choices/allowFreeText/contextHtml/canAnswer) from the list endpoint, so slides render
    // straight from them — the previous per-navigation re-fetch is what rebuilt the form empty and
    // dropped the draft.
    const drafts = {};
    // Surfaces that open a named question (a bell row, a console link, a deep link) pass its id as
    // startId so the pager opens on the slide the user actually asked for; a surface that opens a whole
    // build's list passes none and starts at the first question.
    let start = 0;
    if (opts.startId) {
      for (let i = 0; i < list.length; i++) {
        if (list[i].id === opts.startId) {
          start = i;
          break;
        }
      }
    }
    const ctx = { list: list, index: start, answered: answered };

    // Snapshot the current slide's in-progress answer before we navigate away from it.
    function captureDraft() {
      const q = list[ctx.index];
      if (!q || answered[q.id] || !activeModal) {
        return;
      }
      const ta = activeModal.querySelector(".ii-freetext textarea");
      const radio = activeModal.querySelector('input[name="ii-choice"]:checked');
      drafts[q.id] = { freeText: ta ? ta.value : "", choiceId: radio ? radio.value : null };
    }

    function render(q) {
      const isDone = !!answered[q.id] || (q.status && q.status !== "WAITING");
      const modal = buildModalShell(q, isDone);
      dialogBody(modal).appendChild(buildSeriesNav(ctx));
      if (isDone) {
        renderAudit(modal, q);
      } else {
        renderForm(modal, q, {
          keepOpen: true,
          richModal: opts.richModal,
          initial: drafts[q.id],
          onDone: function (payload) {
            answered[q.id] = true;
            delete drafts[q.id];
            // Reflect the just-submitted answer on the cached item so paging back to this slide shows
            // its outcome read-only without another round-trip.
            q.status = "ANSWERED";
            q.answer = { answeredBy: "you", answeredTs: Date.now() };
            if (payload && payload.choiceId) q.answer.choiceId = payload.choiceId;
            if (payload && payload.freeText) q.answer.freeText = payload.freeText;
            ctx.next();
          }
        });
      }
      replaceModal(modal);
    }

    ctx.goTo = function (i) {
      if (i < 0 || i >= list.length) return;
      captureDraft();
      ctx.index = i;
      render(list[i]);
    };
    ctx.next = function () {
      for (let i = ctx.index + 1; i < list.length; i++) {
        if (!answered[list[i].id]) {
          ctx.goTo(i);
          return;
        }
      }
      for (let j = 0; j < list.length; j++) {
        if (!answered[list[j].id]) {
          ctx.goTo(j);
          return;
        }
      }
      // Nothing left waiting — close and let the surface refresh.
      closeModal();
      if (opts.onDone) opts.onDone();
    };

    ctx.goTo(start);
  }

  // Open a build's waiting questions: the numbered pager for a series, the plain dialog for a single
  // one. This is the ONE place that decision is made, so every surface behaves the same — previously
  // each caller re-implemented it and the surfaces that opened a *named* question (bell row, console
  // link, deep link, job box, audit row) silently bypassed the pager and offered one question at a time.
  function openWaiting(waiting, opts) {
    if (waiting.length > 1) {
      openSeries(waiting, opts);
    } else if (waiting.length === 1) {
      openQuestion(waiting[0], opts);
    }
  }

  // Open one named question in the context of its build's series, paged to that question. Used by the
  // surfaces that start from a single question and so have to look its build-mates up first: one scoped
  // GET on click (no extra polling). Every failure path — a question with no build reference, a failed
  // lookup, or a build with nothing else waiting — falls back to the plain single-question dialog, so a
  // click can never dead-end.
  function openQuestionInSeries(q, opts) {
    opts = opts || {};
    const richModal = opts.richModal != null ? opts.richModal : richModalDefault;
    // With the rich modal off, opening a question NAVIGATES to the build's native input page (see
    // openQuestion). Keep that contract instead of pulling the operator into a dialog they turned off.
    if (!richModal || !q || !q.jobFullName || q.buildNumber == null) {
      openQuestion(q, opts);
      return;
    }
    fetchJson(apiBase + "/questions?job=" + encodeURIComponent(q.jobFullName))
      .then(function (r) {
        const waiting = r.ok ? waitingForBuild(r.body, q.buildNumber) : [];
        if (waiting.length > 1) {
          openSeries(waiting, { richModal: richModal, onDone: opts.onDone, startId: q.id });
        } else {
          openQuestion(q, opts);
        }
      })
      .catch(function () {
        openQuestion(q, opts);
      });
  }

  // ----- shared visibility-aware polling loop -----
  function scheduleLoop(fn) {
    let timer = null;
    function tick() {
      if (timer) clearTimeout(timer);
      if (document.hidden) return;
      timer = setTimeout(function () {
        fn().then(tick);
      }, pollSeconds * 1000);
    }
    document.addEventListener("visibilitychange", function () {
      if (!document.hidden) fn().then(tick);
    });
    tick();
  }

  // ============================ browser-tab notifier ============================
  // Mirror the viewer's pending-question count in the browser tab so a backgrounded/other tab still shows
  // there is something to answer. Driven by the bell's existing poll (no extra traffic) and gated by the
  // Appearance "browser-tab badge" toggle (data-tab-badge). Everything is wrapped in try/catch — a
  // cosmetic tab update must never break the page.
  //
  // Two presentation modes, re-chosen on every paint from the CURRENT favicon (a theme such as the Simple
  // Theme plugin swaps the icon on load — it removes every rel~="icon" link and appends its own):
  //   * SAME-ORIGIN favicon  -> paint a small red dot ON TOP of it and leave the tab TITLE untouched. A
  //     <canvas> may only read pixels from a same-origin image, so this is the only case where a real dot
  //     on the favicon is technically possible.
  //   * CROSS-ORIGIN or missing favicon -> the browser forbids reading its pixels into a canvas (verified
  //     live: a custom nokia.com favicon returns Access-Control-Allow-Origin: *.nokia.com, which does not
  //     match the Jenkins origin, so the crossOrigin load fails). A dot therefore cannot be composited, so
  //     we fall back to a text badge on the tab TITLE: a red-circle glyph (U+1F534 emoji) + "(N) " + the
  //     original title. The red comes from the emoji itself because tab-title text cannot be CSS-coloured.
  //     The favicon is left EXACTLY as the theme set it.
  // We NEVER replace the site's favicon with a different image (doing so previously clobbered custom icons).
  let tabBadgeEnabled = true;
  let tabBaseTitle = null;
  // { link, baseHref } for the link we last badged, so we can restore it and re-badge without mistaking
  // our own generated data: URL for the clean base.
  let faviconState = null;

  function activeFaviconLink() {
    // The primary icon the browser renders: prefer an exact rel="icon", else any rel~="icon".
    return document.querySelector("link[rel='icon']") || document.querySelector("link[rel~='icon']");
  }

  function stripTabBadge(title) {
    // Recover the clean base title by removing a badge prefix we may have added — the red-circle-emoji
    // form ("\uD83D\uDD34 (N) ") or a legacy "(N) " — so prefixes never stack up across polls/navigations.
    return (title || "").replace(/^\uD83D\uDD34\s*\(\d+\+?\)\s+/, "").replace(/^\(\d+\+?\)\s+/, "");
  }

  function faviconBase() {
    // The clean (non-data:) href of the active icon link, resolving our own composited data: URL back to
    // the base we recorded. Returns null when there is no icon link to work from.
    const link = activeFaviconLink();
    if (!link) return null;
    let href = link.getAttribute("href") || "";
    if (href.indexOf("data:") === 0) {
      href = faviconState && faviconState.baseHref ? faviconState.baseHref : "";
    }
    return href ? { link: link, href: href } : null;
  }

  function faviconIsSameOrigin() {
    // Only a same-origin favicon can be read into a canvas; a theme's absolute cross-origin URL cannot.
    // Relative/root-relative hrefs resolve to our own origin and count as same-origin.
    const base = faviconBase();
    if (!base) return false;
    try {
      return new URL(base.href, location.href).origin === location.origin;
    } catch (e) {
      return false;
    }
  }

  function restoreFavicon() {
    if (faviconState && faviconState.link && faviconState.link.parentNode && faviconState.baseHref != null) {
      faviconState.link.setAttribute("href", faviconState.baseHref);
    }
  }

  function paintFavicon(n) {
    const link = activeFaviconLink();
    if (!link) {
      return; // no favicon link at all — do NOT create one (that would override the theme's own icon)
    }
    if (n <= 0) {
      restoreFavicon();
      return;
    }
    const href = link.getAttribute("href") || "";
    if (href.indexOf("data:") === 0) {
      // The active link already holds a data: URL — either our badge (already showing) or one we did not
      // create and must not clobber. Either way there is nothing safe to re-capture; leave it.
      return;
    }
    // A fresh, readable base href. Record it (so a later theme swap that replaces the link is picked up
    // on the next paint from the NEW link's clean href) and composite the dot.
    faviconState = { link: link, baseHref: href };
    const src = href;
    const img = new Image();
    img.crossOrigin = "anonymous"; // needed to read cross-origin pixels into the canvas; harmless same-origin
    img.onload = function () {
      try {
        const size = 32;
        const canvas = document.createElement("canvas");
        canvas.width = size;
        canvas.height = size;
        const ctx = canvas.getContext("2d");
        ctx.drawImage(img, 0, 0, size, size);
        const r = 9;
        ctx.beginPath();
        ctx.arc(size - r, r, r, 0, 2 * Math.PI);
        ctx.fillStyle = "#e60000";
        ctx.fill();
        ctx.lineWidth = 2;
        ctx.strokeStyle = "#fff";
        ctx.stroke();
        // Only mutate the link if it is still the active one and still shows the clean base, so we never
        // stomp a favicon the theme swapped in between the load starting and this callback firing.
        if (activeFaviconLink() === link && (link.getAttribute("href") || "") === src) {
          link.setAttribute("href", canvas.toDataURL("image/png"));
        }
      } catch (e) {
        /* tainted (cross-origin without CORS) or unsupported canvas — keep the current favicon as-is */
      }
    };
    img.onerror = noop; // cross-origin without CORS, or a missing image -> leave the favicon untouched
    try {
      img.src = src;
    } catch (e) {
      /* ignore */
    }
  }

  function updateTabIndicator(n) {
    try {
      if (tabBaseTitle == null) {
        tabBaseTitle = stripTabBadge(document.title || "Jenkins");
      }
      if (faviconIsSameOrigin()) {
        // Preferred: red dot on the readable favicon; keep the tab title clean.
        document.title = tabBaseTitle;
        paintFavicon(n);
      } else {
        // Fallback: a cross-origin/missing favicon can't be badged — mark the TITLE, leave the icon alone.
        // "\uD83D\uDD34" is the U+1F534 red-circle emoji (renders red via the client OS emoji font).
        const capped = n > 99 ? "99+" : String(n);
        document.title = n > 0 ? "\uD83D\uDD34 (" + capped + ") " + tabBaseTitle : tabBaseTitle;
      }
    } catch (e) {
      /* never let a cosmetic tab update break the page */
    }
  }

  // ================================ global nav bell ================================
  function anchorBell(container) {
    // Prefer inline placement among the header controls so the bell never overlaps the settings gear.
    const selectors = [
      ".jenkins-header__actions",
      "#page-header .page-header__hyperlinks",
      "header#page-header .jenkins-header__actions"
    ];
    for (let i = 0; i < selectors.length; i++) {
      const host = document.querySelector(selectors[i]);
      if (host) {
        container.classList.add("ii-bell-inline");
        host.insertBefore(container, host.firstChild);
        return;
      }
    }
    // Fallback: a floating control anchored bottom-right, clear of the header icons.
    container.classList.add("ii-bell-fixed");
    document.body.appendChild(container);
  }

  // Use the server-rendered <l:icon> (the operator's chosen Ionicon) if present, else a default bell.
  function setBellIcon(bellBtn, mount) {
    const tpl = mount.querySelector(".ii-icon-template svg");
    if (tpl) {
      bellBtn.appendChild(tpl.cloneNode(true));
    } else {
      bellBtn.innerHTML =
        '<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" focusable="false">' +
        '<path fill="currentColor" d="M12 22a2.5 2.5 0 0 0 2.45-2h-4.9A2.5 2.5 0 0 0 12 22Zm6-6v-5a6 6 0 0 0-4.5-5.8V4a1.5 1.5 0 0 0-3 0v1.2A6 6 0 0 0 6 11v5l-1.7 1.7a1 1 0 0 0 .7 1.7h14a1 1 0 0 0 .7-1.7L18 16Z"/>' +
        "</svg>";
    }
  }

  function mountBell(mount) {
    const richModal = attr(mount, "data-rich-modal", "true") === "true";
    const initialCount = parseInt(attr(mount, "data-initial-count", "0"), 10) || 0;
    const job = attr(mount, "data-job", "") || "";
    // Appearance toggle: whether to mirror the count in the browser tab (title + favicon dot). On by
    // default; drives the shared updateTabIndicator below. Default true keeps older mounts working.
    tabBadgeEnabled = attr(mount, "data-tab-badge", "true") === "true";
    // Dashboard => every answerable question; inside a pipeline => only that pipeline's questions.
    const listUrl = job ? apiBase + "/questions?job=" + encodeURIComponent(job) : apiBase + "/questions";
    // Parallel interactiveView reviews list (additive; empty/ignored when the feature or API is off).
    const viewsUrl = job ? apiBase + "/views?job=" + encodeURIComponent(job) : apiBase + "/views";
    const headerText = job ? "Pending for this pipeline" : "Pending questions";
    const viewsHeaderText = job ? "Reviews for this pipeline" : "Reviews";

    const bellBtn = el("button", {
      cls: "ii-bell-btn",
      attrs: {
        type: "button",
        "aria-label": "Pending interactive input questions",
        "aria-haspopup": "true",
        "aria-expanded": "false",
        tooltip: "Interactive Input"
      }
    });
    setBellIcon(bellBtn, mount);
    // B14: start hidden via the core jenkins-hidden class and toggle it in setCount, so we never
    // hard-code the "shown" display value (the badge's shown layout lives in bell.css).
    const badge = el("span", { cls: "ii-bell-badge jenkins-hidden", attrs: { "aria-hidden": "false" } });
    bellBtn.appendChild(badge);

    const dropdown = el("div", {
      cls: "ii-dropdown",
      attrs: { role: "menu", "aria-label": "Pending questions", hidden: "hidden" }
    });

    const container = el("div", { cls: "ii-bell-container" });
    container.appendChild(bellBtn);
    container.appendChild(dropdown);
    anchorBell(container);
    if (mount.parentNode) mount.parentNode.removeChild(mount);

    let questionsCache = [];
    let viewsCache = [];

    function setCount(n) {
      if (n > 0) {
        badge.textContent = n > 99 ? "99+" : String(n);
        badge.classList.remove("jenkins-hidden");
      } else {
        badge.textContent = "";
        badge.classList.add("jenkins-hidden");
      }
      // Mirror the pending count in the browser tab (title + favicon dot) when the toggle is on.
      if (tabBadgeEnabled) {
        updateTabIndicator(n);
      }
    }
    setCount(initialCount);

    function toggleDropdown(force) {
      const show = force != null ? force : dropdown.hasAttribute("hidden");
      if (show) {
        renderDropdown();
        dropdown.removeAttribute("hidden");
        bellBtn.setAttribute("aria-expanded", "true");
      } else {
        dropdown.setAttribute("hidden", "hidden");
        bellBtn.setAttribute("aria-expanded", "false");
      }
    }

    function renderDropdown() {
      dropdown.innerHTML = "";
      const hasQ = questionsCache.length > 0;
      const hasV = viewsCache.length > 0;
      if (!hasQ && !hasV) {
        dropdown.appendChild(el("div", { cls: "ii-dropdown-header", text: headerText }));
        dropdown.appendChild(el("div", { cls: "ii-empty", text: "Nothing waiting for you right now." }));
        return;
      }
      if (hasQ) {
        dropdown.appendChild(el("div", { cls: "ii-dropdown-header", text: headerText }));
        const list = el("ul", { cls: "ii-list", attrs: { role: "none" } });
        questionsCache.slice(0, 10).forEach(function (q) {
          const item = el("li", { attrs: { role: "none" } });
          item.appendChild(
            questionListItem(q, function () {
              toggleDropdown(false);
              openQuestionInSeries(q, { richModal: richModal, onDone: refresh });
            })
          );
          list.appendChild(item);
        });
        dropdown.appendChild(list);
      }
      if (hasV) {
        dropdown.appendChild(el("div", { cls: "ii-dropdown-header", text: viewsHeaderText }));
        const vlist = el("ul", { cls: "ii-list", attrs: { role: "none" } });
        viewsCache.slice(0, 10).forEach(function (v) {
          const item = el("li", { attrs: { role: "none" } });
          item.appendChild(
            viewListItem(v, function () {
              toggleDropdown(false);
              if (v.url) {
                window.location.assign(v.url);
              }
            })
          );
          vlist.appendChild(item);
        });
        dropdown.appendChild(vlist);
      }
    }

    function refresh() {
      // Fetch questions and reviews together; the badge sums both. Each fetch is isolated so a
      // disabled/absent reviews API (feature off) never affects the questions list, and vice versa.
      return Promise.all([
        fetchJson(listUrl).catch(function () {
          return { ok: false };
        }),
        fetchJson(viewsUrl).catch(function () {
          return { ok: false };
        }),
      ])
        .then(function (results) {
          const rq = results[0];
          const rv = results[1];
          let qCount = questionsCache.length;
          if (rq.ok && rq.body && Array.isArray(rq.body.questions)) {
            questionsCache = rq.body.questions;
            qCount = rq.body.count != null ? rq.body.count : questionsCache.length;
          }
          if (rv.ok && rv.body && Array.isArray(rv.body.views)) {
            viewsCache = rv.body.views;
          }
          setCount((qCount || 0) + viewsCache.length);
          if (!dropdown.hasAttribute("hidden")) {
            renderDropdown();
          }
        })
        .catch(noop);
    }

    bellBtn.addEventListener("click", function () {
      toggleDropdown();
    });
    document.addEventListener("click", function (e) {
      if (!container.contains(e.target) && !dropdown.hasAttribute("hidden")) {
        toggleDropdown(false);
      }
    });
    document.addEventListener("ii:answered", function () {
      refresh();
    });

    refresh().then(function () {
      scheduleLoop(refresh);
    });
  }

  // ================================ per-project widgets ================================
  function mountListWidget(mount, url, rowFactory, emptyText, opts) {
    opts = opts || {};
    const onData = opts.onData || noop;
    const listWrap = el("div", { cls: "ii-widget" });
    mount.appendChild(listWrap);

    function render(questions) {
      listWrap.innerHTML = "";
      if (!questions.length) {
        listWrap.appendChild(el("div", { cls: "ii-empty", text: emptyText }));
        return;
      }
      const ul = el("ul", { cls: "ii-list" });
      questions.forEach(function (q) {
        const li = el("li");
        li.appendChild(rowFactory(q, refresh));
        ul.appendChild(li);
      });
      listWrap.appendChild(ul);
    }

    function refresh() {
      return fetchJson(url)
        .then(function (r) {
          if (r.ok && r.body && Array.isArray(r.body.questions)) {
            render(r.body.questions);
            onData(r.body.questions);
          }
        })
        .catch(noop);
    }

    document.addEventListener("ii:answered", function () {
      refresh();
    });

    refresh().then(function () {
      scheduleLoop(refresh);
    });
  }

  function mountJobWidget(mount) {
    const job = attr(mount, "data-job", "");
    const richModal = attr(mount, "data-rich-modal", "true") === "true";
    const url = apiBase + "/questions?job=" + encodeURIComponent(job);
    // The inline job-page box (jobMain.jelly) wraps this mount in a .ii-jobcard that is always in the
    // DOM but starts hidden (jenkins-hidden) when nothing is pending, so the box can appear on the very
    // next poll instead of only after a full page reload. Toggle its visibility with the scoped count.
    // The action's own page (index.jelly) has no such wrapper, so it always shows (empty text included).
    const card = mount.closest ? mount.closest(".ii-jobcard") : null;
    mountListWidget(
      mount,
      url,
      function (q, refresh) {
        return questionListItem(
          q,
          function () {
            openQuestionInSeries(q, { richModal: richModal, onDone: refresh });
          },
          { withBar: true }
        );
      },
      "All caught up — nothing waiting.",
      {
        onData: function (questions) {
          if (card) {
            card.classList.toggle("jenkins-hidden", questions.length === 0);
          }
        }
      }
    );
  }

  function mountAuditWidget(mount) {
    const job = attr(mount, "data-job", "");
    const build = attr(mount, "data-build", "");
    const richModal = attr(mount, "data-rich-modal", "true") === "true";
    const url =
      apiBase + "/questions?job=" + encodeURIComponent(job) + "&build=" + encodeURIComponent(build);
    mountListWidget(
      mount,
      url,
      function (q, refresh) {
        return auditRow(q, function () {
          // B1: a build/run-page question that is still WAITING must open the *actionable* dialog (the
          // same one the job page opens), not the read-only audit view. Settled questions stay
          // read-only. This is what makes Approve/Deny work from the run page.
          if (!q.status || q.status === "WAITING") {
            openQuestionInSeries(q, { richModal: richModal, onDone: refresh });
          } else {
            openQuestion(q, { readOnly: true });
          }
        });
      },
      "No interactive-input records for this build (they may have been compacted — see the build console)."
    );
  }

  // ============================ live sidebar task-link count ============================
  // Keeps a left-sidebar link's count badge live (Point 2). Core renders these links server-side once
  // per page load, so without this the count only refreshes on reload. The always-present
  // [data-ii-tasklink] controller polls the scoped count, renders it as a native jenkins-badge pill next
  // to the label (not "(N)" text), hides the row at zero, and best-effort reveals it when the first item
  // appears. One implementation serves both the "Interactive Input" link (questions; kinds job/build)
  // and the "Interactive View" link (reviews; kinds view-job/view-build) — see the kind switch below.
  function mountTaskLink(mount) {
    const job = attr(mount, "data-job", "");
    if (!job) {
      return;
    }
    // The same controller drives two sidebar links: "Interactive Input" (questions, kinds job/build) and
    // "Interactive View" (reviews, kinds view-job/view-build). Only the link segment, label, endpoint and
    // count extraction differ; the inject/find/badge machinery is shared. The questions path is unchanged.
    const kind = attr(mount, "data-ii-tasklink", "job");
    const isView = kind.indexOf("view") === 0; // "view-job" | "view-build"
    const linkSeg = isView ? "interactive-view" : "interactive-input";
    const LABEL = isView ? "Interactive View" : "Interactive Input";
    // Build scope (data-build present, emitted on run sub-pages by the page decorator) targets the run's
    // side link (…/<n>/<seg>); job scope (jobMain.jelly) targets the job link and uses the server count.
    const build = attr(mount, "data-build", "");
    const jobUrl = rootUrl + "/job/" + job.split("/").join("/job/") + "/";
    const expectedHref = build ? jobUrl + build + "/" + linkSeg + "/" : jobUrl + linkSeg + "/";
    // Both links poll the job-scoped notification endpoint (server already applies the notify flag and
    // the "own build's notifications" user-scope). Build scope then filters that same list to the current
    // build client-side, so the per-build badge matches the bell and the setting instead of counting
    // every reader's reviews. (Questions have always been job-only + client-filtered by build.)
    let url = apiBase + (isView ? "/views" : "/questions") + "?job=" + encodeURIComponent(job);

    // Compare hrefs by path only, ignoring the origin and any trailing slash. Core renders this link
    // WITHOUT a trailing slash (…/interactive-input) while we build expectedHref WITH one; an exact
    // match therefore fails and would make apply() clone a duplicate sidebar row. Normalising both
    // sides fixes the match without matching a build's link (…/<n>/interactive-input).
    function normPath(href) {
      return href.replace(/^https?:\/\/[^/]+/, "").replace(/\/+$/, "");
    }
    const expectedPath = normPath(expectedHref);

    function findLink() {
      // Classic layout only: the server-rendered link in the #tasks sidebar. In the experimental layout the
      // sidebar is gone and reachability is provided by core's native "more actions" overflow menu (the
      // JobAction/RunAction exposes an icon + display name), so there is no fallback link for us to badge.
      const anchors = document.querySelectorAll("#tasks a[href], #side-panel a[href], .task a[href]");
      for (let i = 0; i < anchors.length; i++) {
        if (normPath(anchors[i].getAttribute("href") || "") === expectedPath) {
          return anchors[i];
        }
      }
      return null;
    }

    // The row to show/hide is the sidebar link's enclosing .task (fallback to its parent).
    function rowOf(link) {
      return (link.closest && link.closest(".task")) || link.parentNode || link;
    }

    function setLabel(link, text) {
      const span = link.querySelector(".task-link-text");
      if (span) {
        span.textContent = text;
        return;
      }
      // Fallback: rewrite the last non-empty text node so the icon (if any) is preserved.
      for (let i = link.childNodes.length - 1; i >= 0; i--) {
        const node = link.childNodes[i];
        if (node.nodeType === 3 && node.textContent.trim()) {
          node.textContent = text;
          return;
        }
      }
      link.appendChild(document.createTextNode(text));
    }

    // Render the pending count as a native Jenkins pill (jenkins-badge), like the "Updates N" badge
    // on the Plugins page, instead of appending "(N)" to the label. Visibility is toggled via the
    // core jenkins-hidden class so we never hard-code a display value.
    function setBadge(link, n) {
      let badge = link.querySelector(".ii-task-badge");
      if (n > 0) {
        if (!badge) {
          badge = el("span", { cls: "ii-task-badge jenkins-badge jenkins-!-danger-color" });
          link.appendChild(badge);
        }
        badge.textContent = n > 99 ? "99+" : String(n);
        badge.setAttribute("aria-label", n + " pending");
        badge.classList.remove("jenkins-hidden");
      } else if (badge) {
        badge.classList.add("jenkins-hidden");
      }
    }

    function injectLink() {
      const tasks = document.querySelector("#tasks");
      if (!tasks) {
        return null;
      }
      const sample = tasks.querySelector(".task");
      if (!sample) {
        return null;
      }
      const clone = sample.cloneNode(true);
      clone.setAttribute("data-ii-injected", "true");
      const a = clone.querySelector("a[href]");
      if (!a) {
        return null;
      }
      a.setAttribute("href", expectedHref);
      a.removeAttribute("id");
      setLabel(a, LABEL);
      tasks.appendChild(clone);
      return a;
    }

    // B14: show/hide the sidebar row by toggling the core jenkins-hidden class rather than writing an
    // inline display value, so we don't hard-code what the row's shown display should be.
    function apply(n) {
      let link = findLink();
      if (n > 0) {
        if (!link) {
          link = injectLink();
          if (!link) {
            return; // sidebar shape unknown — nothing safe to do; reload will render it server-side
          }
        } else {
          setLabel(link, LABEL);
        }
        setBadge(link, n);
        rowOf(link).classList.remove("jenkins-hidden");
      } else if (link) {
        setBadge(link, 0);
        // Job scope is a pending-count notification link, so it hides at zero. Build scope is the
        // per-build AUDIT link — server-rendered whenever the build EVER had interactive input (any
        // status) — and must stay visible after the question settles so operators can still review past
        // inputs. Only drop its badge.
        if (!build) {
          rowOf(link).classList.add("jenkins-hidden");
        }
      }
    }

    apply(parseInt(attr(mount, "data-initial-count", "0"), 10) || 0);

    function refresh() {
      return fetchJson(url)
        .then(function (r) {
          if (!r.ok || !r.body) {
            return;
          }
          if (isView) {
            // Job scope: server returns the scoped notification count. Build scope: filter that same
            // scoped list (already OPEN + notify + user-scope) down to the current build so the badge
            // matches the bell; the audit link itself stays visible at zero (see apply()).
            if (build) {
              const views = Array.isArray(r.body.views) ? r.body.views : [];
              apply(
                views.filter(function (v) {
                  return v && String(v.buildNumber) === String(build);
                }).length
              );
            } else if (typeof r.body.count === "number") {
              apply(r.body.count);
            }
          } else if (build) {
            apply(waitingForBuild(r.body, build).length);
          } else if (typeof r.body.count === "number") {
            apply(r.body.count);
          }
        })
        .catch(noop);
    }

    document.addEventListener("ii:answered", function () {
      refresh();
    });
    refresh().then(function () {
      scheduleLoop(refresh);
    });
  }

  // ============================ build-history badge (delegated) ============================
  // The empty red dot in the build list. Clicking it opens the answer modal in place (like the bell),
  // rather than navigating to the audit page. Delegation is used so it also works for build rows the
  // async build-history widget injects after this script runs.
  function closestBadge(node) {
    while (node && node.nodeType === 1) {
      if (node.hasAttribute && node.hasAttribute("data-ii-badge")) {
        return node;
      }
      node = node.parentNode;
    }
    return null;
  }

  function removeBadge(badge) {
    if (badge && badge.parentNode) {
      badge.parentNode.removeChild(badge);
    }
  }

  function buildQuestionsUrl(job, build) {
    return apiBase + "/questions?job=" + encodeURIComponent(job) + "&build=" + encodeURIComponent(build);
  }

  function waitingFrom(body) {
    const qs = body && Array.isArray(body.questions) ? body.questions : [];
    return qs.filter(function (q) {
      return q.status === "WAITING";
    });
  }

  // Re-check a build and drop its badge once nothing is left WAITING (handles builds with more than
  // one pending question — the dot stays until the last one is answered).
  function refreshBadge(badge, job, build) {
    fetchJson(buildQuestionsUrl(job, build))
      .then(function (r) {
        if (r.ok && !waitingFrom(r.body).length) {
          removeBadge(badge);
        }
      })
      .catch(noop);
  }

  function openBadge(badge) {
    adoptRootUrl(badge);
    const job = badge.getAttribute("data-job") || "";
    const build = badge.getAttribute("data-build") || "";
    fetchJson(buildQuestionsUrl(job, build))
      .then(function (r) {
        const waiting = r.ok ? waitingFrom(r.body) : [];
        if (!waiting.length) {
          removeBadge(badge); // already settled elsewhere — clear the stale dot
          return;
        }
        const onDone = function () {
          refreshBadge(badge, job, build);
        };
        openWaiting(waiting, { richModal: richModalDefault, onDone: onDone });
      })
      .catch(noop);
  }

  document.addEventListener("click", function (e) {
    const badge = closestBadge(e.target);
    if (!badge) {
      return;
    }
    e.preventDefault();
    openBadge(badge);
  });

  // A question answered from any other surface on the page (bell / job box) should also clear the
  // matching build's badge.
  document.addEventListener("ii:answered", function (e) {
    const d = e && e.detail;
    toArray(document.querySelectorAll("[data-ii-badge]")).forEach(function (badge) {
      const job = badge.getAttribute("data-job");
      const build = badge.getAttribute("data-build");
      if (d && d.job && d.build != null && (job !== d.job || build !== String(d.build))) {
        return; // unrelated build — leave it alone
      }
      refreshBadge(badge, job, build);
    });
  });

  // ============================ console "open" link (delegated) ============================
  // The step logs a console link (see OpenInteractiveInputNote) carrying data-ii-open=<questionId> and
  // data-root-url=<contextPath>. Intercept clicks on it so the question's dialog opens IN PLACE on
  // whatever page shows the link — the build's Console Output above all — instead of navigating to the
  // audit page first (B6). Delegated (like the build badge) so it works even when this link is the only
  // interactive-input surface on the page. If the fetch fails (unknown/compacted question, REST disabled
  // or no permission) we fall back to the link's real href, which navigates to the auto-opening audit page.
  function closestOpenLink(node) {
    while (node && node.nodeType === 1) {
      if (node.hasAttribute && node.hasAttribute("data-ii-open")) {
        return node;
      }
      node = node.parentNode;
    }
    return null;
  }

  function openConsoleLink(link) {
    adoptRootUrl(link); // resolve apiBase from the link's data-root-url when no mount discovered one
    const id = link.getAttribute("data-ii-open") || "";
    const href = link.getAttribute("href");
    fetchJson(apiBase + "/questions/" + encodeURIComponent(id))
      .then(function (r) {
        if (!r || !r.ok || !r.body || !r.body.id) {
          if (href) window.location.href = href; // fall back to the deep-link (audit page auto-opens)
          return;
        }
        const q = r.body;
        if (!q.status || q.status === "WAITING") {
          openQuestionInSeries(q, { richModal: richModalDefault });
        } else {
          openQuestion(q, { readOnly: true });
        }
      })
      .catch(function () {
        if (href) window.location.href = href;
      });
  }

  document.addEventListener("click", function (e) {
    const link = closestOpenLink(e.target);
    if (!link) {
      return;
    }
    e.preventDefault();
    openConsoleLink(link);
  });

  // ====================== run-page attention box + auto-open (B5) ======================
  // Both surfaces live in the build's summary.jelly and use the JOB-scoped notification list
  // (?job=<fullName>) filtered to this build, NOT the per-build audit list (?job=&build=). The
  // job-scoped list is the set of WAITING questions the viewer should act on (honouring the
  // user-scoped-notifications switch), so the box/auto-open match the build-history badge rather than
  // raw readability.
  function waitingForBuild(body, build) {
    const qs = body && Array.isArray(body.questions) ? body.questions : [];
    return qs.filter(function (q) {
      return q.status === "WAITING" && String(q.buildNumber) === String(build);
    });
  }

  // The attention indicator: a native summary row (summary.jelly), gated by the per-pipeline "alert
  // user on run page" property. It starts hidden unless the build is already waiting (server-rendered
  // jenkins-hidden) and is revealed/hidden live here as this build's waiting count changes — the same
  // no-reload behaviour as the inline job-page box. Clicking it opens the waiting question(s).
  function mountRunBox(mount) {
    const job = attr(mount, "data-job", "");
    const build = attr(mount, "data-build", "");
    if (!job || !build) {
      return;
    }
    const richModal = attr(mount, "data-rich-modal", "true") === "true";
    const url = apiBase + "/questions?job=" + encodeURIComponent(job);
    const row = mount.closest ? mount.closest(".ii-runbox-row") : null;

    function reveal(waiting) {
      if (row) {
        row.classList.toggle("jenkins-hidden", waiting.length === 0);
      }
    }

    mount.addEventListener("click", function (e) {
      // The href (the per-build audit page) is a no-JS fallback; with JS we open in place instead.
      e.preventDefault();
      fetchJson(url)
        .then(function (r) {
          const waiting = r.ok ? waitingForBuild(r.body, build) : [];
          reveal(waiting);
          openWaiting(waiting, { richModal: richModal, onDone: refresh });
        })
        .catch(noop);
    });

    function refresh() {
      return fetchJson(url)
        .then(function (r) {
          if (r.ok && r.body) {
            reveal(waitingForBuild(r.body, build));
          }
        })
        .catch(noop);
    }

    document.addEventListener("ii:answered", function () {
      refresh();
    });
    refresh().then(function () {
      scheduleLoop(refresh);
    });
  }

  // Auto-open the answer dialog on a build's Console Output page when that build is waiting for input
  // (the [data-ii-autopopup] controller is emitted only on the console page by the page decorator).
  // Modes are chosen by the System setting carried in data-every:
  //   * "false" (default, mode A): open ONCE per browser session per build (dismissible). A
  //     sessionStorage marker stops it re-opening on later refreshes in the same session.
  //   * "true"  (mode B): open on EVERY page load / refresh until it is answered.
  // Guards (all necessary): only when the rich modal is enabled — otherwise openQuestion would NAVIGATE
  // to the native input page, which must never happen automatically on load; never when a ?open=
  // deep-link is already handling an open, or a dialog is already open.
  function autoPopupKey(job, build) {
    return "ii-autopopup:" + job + "#" + build;
  }

  function alreadyPoppedThisSession(job, build) {
    try {
      return !!window.sessionStorage && sessionStorage.getItem(autoPopupKey(job, build)) === "1";
    } catch (e) {
      return false; // sessionStorage blocked (privacy mode / sandbox) — treat as not-yet-popped
    }
  }

  function markPoppedThisSession(job, build) {
    try {
      if (window.sessionStorage) {
        sessionStorage.setItem(autoPopupKey(job, build), "1");
      }
    } catch (e) {
      /* a missing marker only means mode A may re-pop next load — never a crash */
    }
  }

  function maybeAutoPopup(mount) {
    const job = attr(mount, "data-job", "");
    const build = attr(mount, "data-build", "");
    const richModal = attr(mount, "data-rich-modal", "true") === "true";
    const everyVisit = attr(mount, "data-every", "false") === "true";
    if (!job || !build || !richModal) {
      return; // never auto-navigate to /input/ when the rich modal is off
    }
    if (getQueryParam("open") || activeModal) {
      return; // a deep-link or an already-open dialog takes precedence
    }
    if (!everyVisit && alreadyPoppedThisSession(job, build)) {
      return; // mode A: already shown once this session for this build
    }
    fetchJson(apiBase + "/questions?job=" + encodeURIComponent(job))
      .then(function (r) {
        const waiting = r.ok ? waitingForBuild(r.body, build) : [];
        if (!waiting.length || activeModal) {
          return;
        }
        markPoppedThisSession(job, build);
        openWaiting(waiting, { richModal: richModal });
      })
      .catch(noop);
  }

  // ----- bootstrap -----
  // Discover the mounts + shared config from the DOM, then wire the polling surfaces. Deferred to DOM
  // ready because on a job page this adjunct is emitted in the main panel, BEFORE the footer bell and
  // the sidebar tasklink controller exist (see the note near the top). Running before they are parsed
  // is exactly what left the bell missing inside a job and the sidebar count stale. Badge clicks are
  // handled by delegation above and need no mount.
  function discover() {
    bellMount = document.getElementById("interactive-input-bell");
    widgetMounts = toArray(document.querySelectorAll("[data-ii-widget]"));
    auditMounts = toArray(document.querySelectorAll("[data-ii-audit]"));
    taskLinkMounts = toArray(document.querySelectorAll("[data-ii-tasklink]"));
    runBoxMounts = toArray(document.querySelectorAll("[data-ii-runbox]"));
    autoPopupMounts = toArray(document.querySelectorAll("[data-ii-autopopup]"));
    cfgSrc =
      bellMount ||
      widgetMounts[0] ||
      auditMounts[0] ||
      taskLinkMounts[0] ||
      runBoxMounts[0] ||
      autoPopupMounts[0];
    rootUrl = (attr(cfgSrc, "data-root-url", "") || "").replace(/\/$/, "");
    apiBase = rootUrl + "/interactive-input/api/v1";
    richModalDefault = attr(cfgSrc, "data-rich-modal", "true") === "true";
    pollSeconds = Math.max(5, parseInt(attr(cfgSrc, "data-poll-seconds", "15"), 10) || 15);
  }

  // B6 console deep-link: the step logs .../interactive-input/?open=<questionId>. When that param is
  // present, open the question's shared dialog directly (actionable while WAITING, read-only once
  // settled) so the console link lands the operator on the answer form in one click. Permission to
  // answer stays enforced server-side on submit.
  function getQueryParam(name) {
    const re = new RegExp("[?&]" + name + "=([^&#]*)");
    const m = re.exec(window.location.search || "");
    return m ? decodeURIComponent(m[1].replace(/\+/g, " ")) : null;
  }

  function maybeOpenDeepLink() {
    const id = getQueryParam("open");
    if (!id) {
      return;
    }
    fetchJson(apiBase + "/questions/" + encodeURIComponent(id))
      .then(function (r) {
        if (!r || !r.ok || !r.body || !r.body.id) {
          return; // unknown/compacted question, or no permission — leave the page as-is
        }
        const q = r.body;
        if (!q.status || q.status === "WAITING") {
          openQuestionInSeries(q, { richModal: richModalDefault });
        } else {
          openQuestion(q, { readOnly: true });
        }
      })
      .catch(noop);
  }

  function boot() {
    discover();
    if (
      bellMount ||
      widgetMounts.length ||
      auditMounts.length ||
      taskLinkMounts.length ||
      runBoxMounts.length ||
      autoPopupMounts.length
    ) {
      if (bellMount) mountBell(bellMount);
      widgetMounts.forEach(mountJobWidget);
      auditMounts.forEach(mountAuditWidget);
      taskLinkMounts.forEach(mountTaskLink);
      runBoxMounts.forEach(mountRunBox);
      maybeOpenDeepLink();
      autoPopupMounts.forEach(maybeAutoPopup);
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
