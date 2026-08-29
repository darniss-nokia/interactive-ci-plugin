/**
 * © 2026 Nokia
 * Licensed under the MIT License
 * SPDX-License-Identifier: MIT
*/

/*
 * interactive-input — review editor ("Interactive View").
 *
 * Loaded only on the review editor page (InteractiveViewRunAction/index.jelly), kept separate from
 * bell.js so the notification client stays lean. Renders a Confluence-style two-pane review UI over the
 * permission-checked REST API: rendered markdown / escaped, Prism-highlighted source on the left; a
 * general comment thread plus inline (per-line) comments on the right. Inline comments can be added from
 * BOTH the Source view (per line) and the Rendered view (per element — heading, paragraph, list item,
 * table row, ... — anchored directly to that element's exact source line, no line picker). Comments can
 * be threaded: a reply (parentId) renders nested under its parent, and a display-only authorLabel (e.g. an
 * automation's "AI response") shows with an "automation" chip while the real Jenkins author is preserved.
 * Also an edit-copy mode with version history (edits may carry a summary note); and Approve / Request
 * changes / Reject / Acknowledge — "Request changes" hands the inline comments back to the pipeline
 * (regenerate loop).
 *
 * Security: file content is ALWAYS inserted via textContent (escaped, never executed). Only
 * server-sanitised HTML — rendered markdown (detail.renderedHtml) and rendered comment bodies
 * (comment.bodyHtml), both produced by MarkdownRenderer server-side — is ever assigned to innerHTML.
 * Every mutating request is a JSON POST carrying the Jenkins CSRF crumb (window.crumb.wrap).
 */
(function () {
  "use strict";

  var MAX_LINES_FOR_INLINE = 5000; // beyond this, fall back to a single block (general comments only)
  var MAX_QUOTE_CHARS = 500; // client cap for the highlighted-selection snippet (server re-bounds to match)

  // ---------------------------------------------------------------- DOM + HTTP helpers
  function el(tag, opts) {
    var e = document.createElement(tag);
    opts = opts || {};
    if (opts.cls) e.className = opts.cls;
    if (opts.text != null) e.textContent = opts.text;
    if (opts.html != null) e.innerHTML = opts.html; // ONLY ever server-sanitised HTML
    if (opts.attrs) {
      Object.keys(opts.attrs).forEach(function (k) {
        e.setAttribute(k, opts.attrs[k]);
      });
    }
    return e;
  }

  function clear(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
  }

  function fetchJson(url, options) {
    options = options || {};
    options.headers = options.headers || {};
    options.headers["Accept"] = "application/json";
    options.credentials = "same-origin";
    return fetch(url, options).then(function (resp) {
      var ct = resp.headers.get("content-type") || "";
      var parse = ct.indexOf("application/json") >= 0 ? resp.json() : resp.text();
      return parse.then(function (body) {
        return { ok: resp.ok, status: resp.status, body: body };
      });
    });
  }

  function postJson(url, payload) {
    var base = { "Content-Type": "application/json" };
    var headers = window.crumb && typeof window.crumb.wrap === "function" ? window.crumb.wrap(base) : base;
    return fetchJson(url, { method: "POST", headers: headers, body: JSON.stringify(payload || {}) });
  }

  function fmtTime(ms) {
    if (!ms) return "";
    try {
      return new Date(ms).toLocaleString();
    } catch (e) {
      return "";
    }
  }

  function errorMessage(res) {
    if (res && res.body && typeof res.body === "object" && res.body.message) return res.body.message;
    if (res && res.status) return "Request failed (" + res.status + ")";
    return "Request failed";
  }

  // Inline-comment interaction mode (item #4), persisted per-user in localStorage. "highlight" (default) =
  // select text then click a floating "Add comment"; "plus" = the original hover-"+" affordance. Reads are
  // defensive so a browser with localStorage disabled (privacy mode) still works (falls back to highlight).
  var COMMENT_MODE_KEY = "ii-view-comment-mode";

  function getCommentMode() {
    try {
      return window.localStorage.getItem(COMMENT_MODE_KEY) === "plus" ? "plus" : "highlight";
    } catch (e) {
      return "highlight";
    }
  }

  function setCommentMode(mode) {
    try {
      window.localStorage.setItem(COMMENT_MODE_KEY, mode === "plus" ? "plus" : "highlight");
    } catch (e) {
      /* localStorage unavailable (e.g. privacy mode): the choice just will not persist across reloads */
    }
  }

  // ---------------------------------------------------------------- per-mount controller
  function mount(root) {
    var rootUrl = (root.getAttribute("data-root-url") || "").replace(/\/$/, "");
    var apiBase = rootUrl + "/interactive-input/api/v1";
    var job = root.getAttribute("data-job") || "";
    var build = root.getAttribute("data-build") || "";
    var docId = new URLSearchParams(window.location.search).get("doc");

    var state = {
      detail: null,
      mode: "rendered", // "rendered" | "source" | "edit"
      selectedLine: 0, // 0 = none; the EXACT source line a new comment anchors to (Source row or Rendered element)
      // The verbatim text the reviewer highlighted for the pending (not-yet-posted) comment on selectedLine,
      // or "" when the line thread was opened via the "+" affordance. Sent with the comment as `quote` and
      // shown back verbatim, so a sub-phrase or a multi-line selection is preserved instead of the whole line.
      pendingQuote: "",
      viewingVersion: 0, // 0 = latest
      versionContent: null, // content of a non-latest version being viewed
    };

    // Highlight-mode "Add comment" popup (#4): a single reused button shown at a text selection. Global
    // listeners hide it on any new mousedown (except on the button itself) or on scroll; onPaneMouseUp
    // re-shows it after a drag-select. Appended to <body> so it survives the per-render rebuild of the panes.
    var floatBtn = null;
    var floatLine = 0;
    var floatQuote = ""; // verbatim text of the current selection, captured for the floating "Add comment"
    document.addEventListener(
      "mousedown",
      function (e) {
        if (floatBtn && e.target === floatBtn) return; // clicking the popup: let its own click handler run
        hideFloatBtn();
      },
      true
    );
    document.addEventListener("scroll", hideFloatBtn, true);

    function viewUrl(id) {
      return apiBase + "/views/" + encodeURIComponent(id);
    }

    if (!docId) {
      loadList();
      return;
    }
    loadDetail();

    // ---- list mode (no ?doc): show this build's reviews ----
    function loadList() {
      var url = apiBase + "/views?job=" + encodeURIComponent(job) + "&build=" + encodeURIComponent(build);
      fetchJson(url)
        .then(function (res) {
          clear(root);
          if (!res.ok) {
            root.appendChild(el("div", { cls: "iv-error", text: errorMessage(res) }));
            return;
          }
          renderList(res.body.views || []);
        })
        .catch(function () {
          clear(root);
          root.appendChild(el("div", { cls: "iv-error", text: "Could not load reviews." }));
        });
    }

    // Reviews published by a glob/dir share a groupId + reportName; a lone file is its own group. The
    // listing groups cards by report/folder, tags each as "Needs approval" (review + OPEN) or "Info",
    // shows a "Notified" badge, and offers All / Notified / Needs-approval filter chips.
    function needsApproval(v) {
      return v.mode !== "info" && v.status === "OPEN";
    }

    function renderList(views) {
      root.appendChild(el("h1", { text: "Interactive View" }));
      if (!views.length) {
        root.appendChild(el("div", { cls: "iv-empty", text: "No reviews for this build." }));
        return;
      }

      var filter = { value: "all" };
      var chipsBar = el("div", { cls: "iv-filter-chips" });
      var listWrap = el("div", { cls: "iv-groups" });

      function passes(v) {
        if (filter.value === "notified") return !!v.notify;
        if (filter.value === "needs") return needsApproval(v);
        return true;
      }

      var notifiedCount = 0;
      var needsCount = 0;
      views.forEach(function (v) {
        if (v.notify) notifiedCount++;
        if (needsApproval(v)) needsCount++;
      });

      function chip(label, value) {
        var b = el("button", {
          cls: "iv-chip-btn" + (filter.value === value ? " active" : ""),
          attrs: { type: "button" },
          text: label,
        });
        b.addEventListener("click", function () {
          filter.value = value;
          var all = chipsBar.querySelectorAll(".iv-chip-btn");
          for (var i = 0; i < all.length; i++) all[i].classList.remove("active");
          b.classList.add("active");
          drawGroups();
        });
        return b;
      }
      chipsBar.appendChild(chip("All (" + views.length + ")", "all"));
      chipsBar.appendChild(chip("Notified (" + notifiedCount + ")", "notified"));
      chipsBar.appendChild(chip("Needs approval (" + needsCount + ")", "needs"));
      root.appendChild(chipsBar);
      root.appendChild(listWrap);

      function drawGroups() {
        clear(listWrap);
        var order = [];
        var groups = {};
        views.forEach(function (v) {
          if (!passes(v)) return;
          var key = v.groupId || v.id;
          if (!groups[key]) {
            groups[key] = {
              label: v.reportName || v.title || key,
              items: [],
              notify: false,
              grouped: !!v.grouped,
            };
            order.push(key);
          }
          groups[key].items.push(v);
          if (v.notify) groups[key].notify = true;
        });
        if (!order.length) {
          listWrap.appendChild(el("div", { cls: "iv-empty", text: "No reviews match this filter." }));
          return;
        }
        order.forEach(function (key) {
          var g = groups[key];
          var section = el("div", { cls: "iv-group" });
          var head = el("div", { cls: "iv-group-head" });
          head.appendChild(el("span", { cls: "iv-group-title", text: g.label }));
          head.appendChild(el("span", {
            cls: "iv-group-count",
            text: g.grouped ? g.items.length + " files" : g.items.length + " item",
          }));
          if (g.notify) head.appendChild(el("span", { cls: "iv-flag iv-flag-notified", text: "Notified" }));
          // "Download all (.zip)" for a real multi-file group; a lone file uses its per-card Download below.
          if (g.grouped) {
            head.appendChild(el("a", {
              cls: "iv-group-download",
              text: "Download all (.zip)",
              attrs: {
                href: apiBase + "/views/" + encodeURIComponent(g.items[0].id) + "/downloadGroup",
                download: "",
                title: "Download all files in this group as a zip",
              },
            }));
          }
          section.appendChild(head);
          var list = el("div", { cls: "iv-list" });
          g.items.forEach(function (v) {
            // The card is an <a>; keep the per-file download as a SIBLING (an <a> nested in an <a> is
            // invalid HTML and would break navigation).
            var wrap = el("div", { cls: "iv-list-card-wrap" });
            wrap.appendChild(listCard(v));
            wrap.appendChild(el("a", {
              cls: "iv-list-download",
              text: "Download",
              attrs: {
                href: apiBase + "/views/" + encodeURIComponent(v.id) + "/download",
                download: "",
                title: "Download this file",
              },
            }));
            list.appendChild(wrap);
          });
          section.appendChild(list);
          listWrap.appendChild(section);
        });
      }
      drawGroups();
    }

    function listCard(v) {
      var card = el("a", { cls: "iv-list-card", attrs: { href: "?doc=" + encodeURIComponent(v.id) } });
      var titleRow = el("div", { cls: "iv-list-title-row" });
      titleRow.appendChild(el("div", { cls: "iv-list-title", text: v.title || v.reportName || v.id }));
      if (v.mode === "info") {
        titleRow.appendChild(el("span", { cls: "iv-tag iv-tag-info", text: "Info" }));
      } else if (v.status === "OPEN") {
        titleRow.appendChild(el("span", { cls: "iv-tag iv-tag-review", text: "Needs approval" }));
      }
      card.appendChild(titleRow);
      var meta = el("div", { cls: "iv-list-meta" });
      meta.appendChild(el("span", { cls: "iv-status iv-status-" + v.status, text: v.status }));
      if (v.notify) meta.appendChild(el("span", { cls: "iv-flag iv-flag-notified", text: "Notified" }));
      meta.appendChild(el("span", { text: (v.commentCount || 0) + " comment(s)" }));
      if (v.fileName) meta.appendChild(el("span", { cls: "iv-list-file", text: v.fileName }));
      card.appendChild(meta);
      return card;
    }

    // ---- detail mode ----
    function loadDetail(preserveLine) {
      var keepLine = preserveLine ? state.selectedLine : 0;
      fetchJson(viewUrl(docId))
        .then(function (res) {
          if (!res.ok) {
            clear(root);
            root.appendChild(el("div", { cls: "iv-error", text: errorMessage(res) }));
            return;
          }
          state.detail = res.body;
          state.selectedLine = keepLine;
          if (state.mode !== "source" && state.mode !== "edit") {
            state.mode = hasRenderedView(res.body) ? "rendered" : "source";
          }
          render();
        })
        .catch(function () {
          clear(root);
          root.appendChild(el("div", { cls: "iv-error", text: "Could not load this review." }));
        });
    }

    function isLatest() {
      return state.viewingVersion === 0 || state.viewingVersion === state.detail.currentVersion;
    }

    // Whether this document has a "Rendered" view at all: markdown carries sanitised HTML inline, while
    // an HTML snapshot is loaded into the sandboxed frame from the server (see renderHtmlFrame).
    function hasRenderedView(d) {
      return d != null && (d.renderedHtml != null || d.htmlRenderable === true);
    }

    function currentContent() {
      if (!isLatest() && state.versionContent != null) return state.versionContent;
      return state.detail.content || "";
    }

    // A short, whitespace-collapsed, length-bounded snippet of source line n, for comment context (#5).
    // Always rendered via textContent / an attribute (never innerHTML), so a line can never inject markup.
    function lineSnippet(n) {
      if (!(n >= 1)) return "";
      var raw = currentContent().split(/\r\n|\r|\n/)[n - 1];
      if (raw == null) return "";
      var s = raw.replace(/\s+/g, " ").trim();
      if (!s) return "";
      return s.length > 50 ? s.slice(0, 50).trim() + "\u2026" : s;
    }

    // Apply a mutation response to state.detail WITHOUT dropping the heavy content/renderedHtml fields
    // if a response omits them. The server now always returns the full document for mutations, but this
    // keeps the left pane from blanking should any endpoint ever return a summary-only payload (the
    // root cause of the earlier inline-comment "crash").
    function applyDetail(body) {
      if (!body || typeof body !== "object") {
        return;
      }
      var prev = state.detail || {};
      if (body.content == null && prev.content != null) {
        body.content = prev.content;
      }
      if (body.renderedHtml == null && prev.renderedHtml != null) {
        body.renderedHtml = prev.renderedHtml;
      }
      state.detail = body;
    }

    function render() {
      var d = state.detail;
      clear(root);
      hideFloatBtn();
      // Highlight mode hides the "+" affordances via CSS (count markers stay); plus mode restores them.
      root.classList.toggle("iv-mode-highlight", getCommentMode() === "highlight");
      root.appendChild(buildHeader(d));
      root.appendChild(buildToolbar(d));

      var body = el("div", { cls: "iv-body" });
      var left = el("div", { cls: "iv-pane iv-pane-content" });
      var right = el("div", { cls: "iv-pane iv-pane-comments" });
      body.appendChild(left);
      body.appendChild(right);
      root.appendChild(body);

      renderContent(left);
      renderComments(right);
      // Highlight-select commenting listens on the content pane; guarded by mode/permissions inside.
      left.addEventListener("mouseup", onPaneMouseUp);
    }

    function buildHeader(d) {
      var head = el("div", { cls: "iv-header" });
      var back = el("a", { cls: "iv-back", text: "\u2039 All reviews for this build", attrs: { href: "?" } });
      head.appendChild(back);

      var titleRow = el("div", { cls: "iv-title-row" });
      titleRow.appendChild(el("h1", { cls: "iv-title", text: d.title || d.reportName || d.id }));
      titleRow.appendChild(el("span", { cls: "iv-status iv-status-" + d.status, text: d.status }));
      if (d.blocking && d.status === "OPEN") {
        titleRow.appendChild(el("span", { cls: "iv-chip iv-chip-blocking", text: "Pipeline waiting" }));
      }
      head.appendChild(titleRow);

      var meta = el("div", { cls: "iv-meta" });
      meta.appendChild(el("span", { text: d.fileName || "" }));
      meta.appendChild(el("span", { text: "Published by " + (d.createdBy || "system") + " on " + fmtTime(d.createdTs) }));
      if (d.status !== "OPEN" && d.decidedBy) {
        meta.appendChild(el("span", { text: d.status.toLowerCase() + " by " + d.decidedBy + " on " + fmtTime(d.decidedTs) }));
      }
      head.appendChild(meta);
      return head;
    }

    function buildToolbar(d) {
      var bar = el("div", { cls: "iv-toolbar" });
      var leftGrp = el("div", { cls: "iv-toolbar-grp" });

      // Rendered / Source toggle: markdown supplies rendered HTML inline, an HTML snapshot is rendered
      // in the sandboxed frame (htmlRenderable). Everything else is source-only, so no toggle.
      if (hasRenderedView(d)) {
        var seg = el("div", { cls: "iv-seg" });
        seg.appendChild(segBtn("Rendered", state.mode === "rendered", function () {
          if (state.mode === "edit") return;
          state.mode = "rendered";
          render();
        }));
        seg.appendChild(segBtn("Source", state.mode === "source", function () {
          if (state.mode === "edit") return;
          state.mode = "source";
          render();
        }));
        leftGrp.appendChild(seg);
      }

      // Inline-comment mode toggle (#4): Highlight (select text) vs Plus (hover +). Shown whenever
      // commenting is possible; the choice persists per-user in localStorage and re-renders to apply.
      if (d.commentable && d.canContribute && isLatest()) {
        var modeSeg = el("div", { cls: "iv-seg iv-comment-mode" });
        modeSeg.appendChild(
          segBtn("Highlight", getCommentMode() === "highlight", function () {
            if (getCommentMode() !== "highlight") {
              setCommentMode("highlight");
              render();
            }
          })
        );
        modeSeg.appendChild(
          segBtn("Plus", getCommentMode() === "plus", function () {
            if (getCommentMode() !== "plus") {
              setCommentMode("plus");
              render();
            }
          })
        );
        leftGrp.appendChild(modeSeg);
      }

      // Version selector
      if (d.versions && d.versions.length > 1) {
        var sel = el("select", { cls: "jenkins-select__input iv-version" });
        d.versions.forEach(function (v) {
          var latest = v.index === d.currentVersion;
          var label = "v" + v.index + (latest ? " (latest)" : "") + (v.editedBy ? " \u2014 " + v.editedBy : "");
          // Surface a meaningful edit summary (e.g. an AI course-correction note) but skip the generic
          // defaults so ordinary edits stay compact. text: (textContent) keeps it injection-safe.
          if (v.note && v.note !== "edited" && v.note !== "original snapshot") {
            label += ": " + (v.note.length > 60 ? v.note.slice(0, 60) + "\u2026" : v.note);
          }
          var opt = el("option", { text: label, attrs: { value: String(v.index) } });
          if ((state.viewingVersion === 0 && latest) || state.viewingVersion === v.index) opt.selected = true;
          sel.appendChild(opt);
        });
        sel.addEventListener("change", function () {
          var v = parseInt(sel.value, 10);
          if (v === d.currentVersion) {
            state.viewingVersion = 0;
            state.versionContent = null;
            if (state.mode === "edit") state.mode = "source";
            render();
          } else {
            viewVersion(v);
          }
        });
        leftGrp.appendChild(sel);
      }

      // Downloads (item #1): this file, and — for a multi-file group (glob/dir) — the whole group as a zip.
      // Shown for any status/mode (a read-only GET). The <a download> plus the server's Content-Disposition
      // header drives the browser save; permissions are enforced server-side (Item.READ, else 404).
      leftGrp.appendChild(el("a", {
        cls: "jenkins-button iv-download",
        text: "Download",
        attrs: { href: viewUrl(docId) + "/download", download: "", title: "Download this file" },
      }));
      if (d.grouped) {
        leftGrp.appendChild(el("a", {
          cls: "jenkins-button iv-download",
          text: "Download all (.zip)",
          attrs: {
            href: viewUrl(docId) + "/downloadGroup",
            download: "",
            title: "Download every file in this group as a zip",
          },
        }));
      }
      bar.appendChild(leftGrp);

      var rightGrp = el("div", { cls: "iv-toolbar-grp" });

      // Edit toggle (never on an informational, read-only view)
      if (d.editable && d.canContribute && d.status === "OPEN" && d.mode !== "info" && isLatest()) {
        if (state.mode === "edit") {
          var save = el("button", { cls: "jenkins-button jenkins-button--primary", text: "Save changes" });
          save.addEventListener("click", saveEdit);
          var cancel = el("button", { cls: "jenkins-button", text: "Cancel" });
          cancel.addEventListener("click", function () {
            state.mode = hasRenderedView(d) ? "rendered" : "source";
            render();
          });
          rightGrp.appendChild(save);
          rightGrp.appendChild(cancel);
        } else {
          var edit = el("button", { cls: "jenkins-button", text: "Edit" });
          edit.addEventListener("click", function () {
            state.mode = "edit";
            render();
          });
          rightGrp.appendChild(edit);
        }
      }

      // Decision buttons. Skipped for an informational (mode:info) view, which is read-only with no
      // decision. "Request changes" resolves a blocking review as CHANGES_REQUESTED and hands the inline
      // comments back to the pipeline so a generator can regenerate (the course-correction loop).
      if (d.canContribute && d.status === "OPEN" && d.mode !== "info" && state.mode !== "edit") {
        rightGrp.appendChild(decisionBtn("Approve", "approve", "jenkins-button--primary"));
        rightGrp.appendChild(decisionBtn("Request changes", "request-changes", ""));
        rightGrp.appendChild(decisionBtn("Reject", "reject", ""));
        rightGrp.appendChild(decisionBtn("Acknowledge", "acknowledge", ""));
      } else if (d.mode === "info") {
        rightGrp.appendChild(el("span", { cls: "iv-info-note", text: "Informational — no decision required" }));
      }
      bar.appendChild(rightGrp);

      var note = el("div", { cls: "iv-toolbar-note" });
      if (!isLatest()) {
        note.textContent = "Viewing version " + state.viewingVersion + " (read-only). Switch to latest to edit or comment on lines.";
        bar.appendChild(note);
      }
      return bar;
    }

    function segBtn(label, active, onClick) {
      var b = el("button", { cls: "iv-seg-btn" + (active ? " active" : ""), text: label });
      b.addEventListener("click", onClick);
      return b;
    }

    function decisionBtn(label, verb, extraCls) {
      var b = el("button", { cls: "jenkins-button " + extraCls, text: label });
      b.addEventListener("click", function () {
        b.disabled = true;
        postJson(viewUrl(docId) + "/decision", { decision: verb })
          .then(function (res) {
            if (!res.ok) {
              b.disabled = false;
              flash(errorMessage(res), true);
              return;
            }
            applyDetail(res.body);
            render();
          })
          .catch(function () {
            b.disabled = false;
            flash("Could not record decision.", true);
          });
      });
      return b;
    }

    function viewVersion(v) {
      fetchJson(viewUrl(docId) + "/raw?version=" + encodeURIComponent(v)).then(function (res) {
        if (!res.ok) {
          flash(errorMessage(res), true);
          return;
        }
        state.viewingVersion = v;
        state.versionContent = res.body.content || "";
        state.mode = "source";
        render();
      });
    }

    function saveEdit() {
      var textarea = root.querySelector(".iv-edit-area");
      if (!textarea) return;
      var content = textarea.value;
      postJson(viewUrl(docId) + "/edit", { content: content })
        .then(function (res) {
          if (!res.ok) {
            flash(errorMessage(res), true);
            return;
          }
          applyDetail(res.body);
          state.viewingVersion = 0;
          state.versionContent = null;
          state.mode = hasRenderedView(state.detail) ? "rendered" : "source";
          render();
          flash("Saved new version v" + state.detail.currentVersion + ".", false);
        })
        .catch(function () {
          flash("Could not save changes.", true);
        });
    }

    // ---- highlight-select commenting (#4) ----
    // In highlight mode the reviewer selects text in a line and clicks a floating "Add comment" button,
    // which resolves the selection's source line (a Source row via .iv-line[data-line], or the innermost
    // rendered element via [data-source-line]) and opens that line's thread — reusing the exact same
    // per-line comment model as the "+" affordance, so a comment round-trips to the pipeline unchanged.
    function ensureFloatBtn() {
      if (floatBtn) return floatBtn;
      floatBtn = el("button", { cls: "iv-float-add", text: "Add comment", attrs: { type: "button" } });
      floatBtn.style.display = "none";
      // mousedown preventDefault keeps the text selection from collapsing before the click handler runs.
      floatBtn.addEventListener("mousedown", function (e) {
        e.preventDefault();
      });
      floatBtn.addEventListener("click", function () {
        var line = floatLine;
        var quote = floatQuote;
        hideFloatBtn();
        var sel = window.getSelection && window.getSelection();
        if (sel && sel.removeAllRanges) sel.removeAllRanges();
        // Anchor at the selection's FIRST line and carry the exact highlighted text so the comment reflects
        // whatever was selected (a sub-phrase, or text spanning several lines) rather than the whole line.
        if (line >= 1) selectLine(line, quote);
      });
      document.body.appendChild(floatBtn);
      return floatBtn;
    }

    function hideFloatBtn() {
      if (floatBtn) floatBtn.style.display = "none";
      floatLine = 0;
      floatQuote = "";
    }

    // Resolve the 1-based source line a DOM node sits on (a Source row via .iv-line[data-line], or the
    // innermost rendered element via [data-source-line]); 0 if none/unresolvable.
    function lineOfNode(node) {
      var eln = node && node.nodeType === 1 ? node : node && node.parentElement;
      if (!eln || typeof eln.closest !== "function") return 0;
      var srcRow = eln.closest(".iv-line[data-line]");
      if (srcRow) return parseInt(srcRow.getAttribute("data-line"), 10) || 0;
      var anchor = eln.closest("[data-source-line]");
      return anchor ? parseInt(anchor.getAttribute("data-source-line"), 10) || 0 : 0;
    }

    // Describe a non-empty text selection: its anchor line (the FIRST source line it touches, so a top-down
    // or bottom-up drag resolves the same way) and its verbatim text (whitespace-collapsed and bounded).
    // Returns { line: 0, text: "" } when there is nothing usable to comment on.
    function selectionInfo(sel) {
      if (!sel || sel.isCollapsed || !sel.rangeCount) return { line: 0, text: "" };
      var text = String(sel).replace(/\s+/g, " ").trim();
      if (!text) return { line: 0, text: "" };
      var r = sel.getRangeAt(0);
      var startLine = lineOfNode(r.startContainer);
      var endLine = lineOfNode(r.endContainer);
      var line = startLine >= 1 && endLine >= 1 ? Math.min(startLine, endLine) : startLine || endLine;
      if (text.length > MAX_QUOTE_CHARS) text = text.slice(0, MAX_QUOTE_CHARS).trim() + "\u2026";
      return { line: line, text: text };
    }

    function onPaneMouseUp() {
      var d = state.detail;
      if (getCommentMode() !== "highlight" || !d || !d.commentable || !d.canContribute || !isLatest()) {
        return;
      }
      var sel = window.getSelection && window.getSelection();
      var info = selectionInfo(sel);
      if (info.line < 1) {
        hideFloatBtn();
        return;
      }
      var rect = sel.getRangeAt(0).getBoundingClientRect();
      if (!rect || (rect.width === 0 && rect.height === 0)) {
        hideFloatBtn();
        return;
      }
      var btn = ensureFloatBtn();
      floatLine = info.line;
      floatQuote = info.text;
      btn.style.display = "block";
      // position:fixed -> viewport coordinates; place just above the selection, or below if near the top.
      var top = rect.top - 34;
      btn.style.top = (top < 4 ? rect.bottom + 6 : top) + "px";
      btn.style.left = Math.max(4, rect.left) + "px";
    }

    // ---- content pane ----
    function renderContent(left) {
      var d = state.detail;
      if (state.mode === "edit") {
        var wrap = el("div", { cls: "iv-edit-wrap" });
        var ta = el("textarea", { cls: "iv-edit-area", attrs: { spellcheck: "false" } });
        ta.value = d.content || "";
        wrap.appendChild(ta);
        left.appendChild(wrap);
        return;
      }
      if (state.mode === "rendered" && d.renderedHtml != null) {
        // Server-sanitised markdown HTML (MarkdownRenderer) — safe to insert.
        var rendered = el("div", { cls: "iv-rendered", html: d.renderedHtml });
        left.appendChild(rendered);
        decorateRenderedBlocks(rendered);
        return;
      }
      if (state.mode === "rendered" && d.htmlRenderable === true) {
        renderHtmlFrame(left);
        return;
      }
      renderSource(left);
    }

    // Render an HTML snapshot in a sandboxed frame. The document is pipeline-generated and therefore
    // untrusted, so it is NEVER inserted into this page: it is loaded from /rendered, which serves it
    // under "Content-Security-Policy: sandbox allow-scripts". The sandbox attribute repeats that policy
    // here (two independent enforcement points). Withholding allow-same-origin is the crucial part — the
    // frame gets a unique opaque origin, so its scripts can run (a generated report is usually 100%
    // script-driven and shows nothing without them) yet cannot read this page, the session cookie or a
    // CSRF crumb. allow-forms / allow-popups / allow-top-navigation are withheld too, so it cannot post
    // a form, open a window or navigate the reviewer away.
    //
    // Because the frame is cross-origin to us we cannot inject the inline-comment affordances into it, so
    // this view is read-only; per-line commenting stays on the Source view and the decision buttons are
    // outside the frame, so both are unaffected.
    function renderHtmlFrame(left) {
      var d = state.detail;
      var wrap = el("div", { cls: "iv-htmlframe-wrap" });
      var src = viewUrl(docId) + "/rendered";
      if (!isLatest()) {
        src += "?version=" + encodeURIComponent(state.viewingVersion);
      }
      var frame = el("iframe", {
        cls: "iv-htmlframe",
        attrs: {
          src: src,
          sandbox: "allow-scripts",
          // The report may fetch outward; don't hand it the review URL (it names the job and build).
          referrerpolicy: "no-referrer",
          title: (d.title || d.fileName || "Review document") + " (rendered)",
        },
      });
      wrap.appendChild(frame);
      left.appendChild(wrap);
      left.appendChild(
        el("div", {
          cls: "iv-note",
          text:
            "Rendered in an isolated frame, so its scripts cannot reach Jenkins. Switch to Source to " +
            "read the markup or to comment on a line.",
        })
      );
    }

    // Add inline-comment affordances to the rendered markdown. MarkdownRenderer stamps EVERY commentable
    // element (heading, paragraph, list item, table row, ...) with data-source-line; we decorate the
    // INNERMOST anchored element (so a table row, not the whole table) in place — a hover "+" to comment
    // that EXACT line and a count marker for existing comments — and clicking either opens that line's
    // thread. A <tr>/<li> cannot be wrapped in a <div> (invalid HTML), so the controls are hosted on the
    // element itself (or its first cell for a row) and placed in a left gutter via CSS. Comments use the
    // same per-line model as the Source view, so they round-trip to the pipeline unchanged.
    function decorateRenderedBlocks(rendered) {
      var d = state.detail;
      var all = Array.prototype.slice.call(rendered.querySelectorAll("[data-source-line]"));
      if (!all.length) return;
      // Keep only the innermost anchors: a container (e.g. <table>, <ul>) that holds a more specific
      // anchored element (<tr>, <li>) is skipped, so each visible line carries exactly one affordance.
      var blocks = all.filter(function (node) {
        return node.querySelector("[data-source-line]") == null;
      });
      var canComment = d.commentable && d.canContribute && isLatest();
      var commentsByLine = groupLineComments();

      blocks.forEach(function (node) {
        var line = parseInt(node.getAttribute("data-source-line"), 10) || 0;
        if (line < 1) return;
        node.classList.add("iv-anchor");
        if (state.selectedLine === line) node.classList.add("selected");
        // A table row hosts its gutter controls inside its first cell (a control placed directly under a
        // <tr> would be invalid HTML); every other element hosts them directly.
        var host = node.tagName === "TR" ? node.firstElementChild || node : node;
        host.classList.add("iv-anchor-host");

        var lineComments = commentsByLine[line];
        if (lineComments && lineComments.length) {
          node.classList.add("has-comments");
          var marker = el("span", {
            cls: "iv-rblock-marker",
            text: String(lineComments.length),
            attrs: { title: lineComments.length + " comment(s) on line " + line + " \u2014 click to view" },
          });
          marker.addEventListener("click", function (e) {
            e.preventDefault();
            selectLine(line);
          });
          host.appendChild(marker);
        } else if (canComment) {
          var add = el("button", {
            cls: "iv-rblock-add",
            text: "+",
            attrs: { type: "button", title: "Comment on line " + line, "aria-label": "Comment on line " + line },
          });
          add.addEventListener("click", function (e) {
            e.preventDefault();
            selectLine(line);
          });
          host.appendChild(add);
        }
      });
    }

    function renderSource(left) {
      var d = state.detail;
      var content = currentContent();
      var lang = d.language && d.language !== "none" ? d.language : "";
      var lines = content.split(/\r\n|\r|\n/);
      var commentsByLine = groupLineComments();

      if (!isLatest() || lines.length > MAX_LINES_FOR_INLINE) {
        // Single block: correct multi-line highlighting, but no per-line commenting.
        var pre = el("pre", { cls: "iv-code-block line-numbers" });
        var code = el("code", { cls: lang ? "language-" + lang : "" });
        code.textContent = content;
        pre.appendChild(code);
        left.appendChild(pre);
        highlight(left);
        if (isLatest() && lines.length > MAX_LINES_FOR_INLINE) {
          left.appendChild(el("div", { cls: "iv-note", text: "File is large (" + lines.length + " lines); inline line comments are disabled. Use general comments." }));
        }
        return;
      }

      var container = el("div", { cls: "iv-code" });
      lines.forEach(function (text, i) {
        var n = i + 1;
        var row = el("div", { cls: "iv-line", attrs: { "data-line": String(n) } });
        if (state.selectedLine === n) row.classList.add("selected");
        if (commentsByLine[n]) row.classList.add("has-comments");

        var add = el("button", { cls: "iv-line-add", text: "+", attrs: { title: "Comment on line " + n, "aria-label": "Comment on line " + n } });
        add.addEventListener("click", function () {
          selectLine(n);
        });
        row.appendChild(add);
        row.appendChild(el("span", { cls: "iv-lnum", text: String(n) }));

        var code = el("code", { cls: "iv-lcode" + (lang ? " language-" + lang : "") });
        code.textContent = text.length ? text : "\u00a0";
        row.appendChild(code);

        if (commentsByLine[n]) {
          var marker = el("span", { cls: "iv-line-marker", text: String(commentsByLine[n].length) });
          marker.addEventListener("click", function () {
            selectLine(n);
          });
          row.appendChild(marker);
        }
        container.appendChild(row);
      });
      left.appendChild(container);
      highlight(left);
    }

    function highlight(scope) {
      if (window.Prism && typeof window.Prism.highlightAllUnder === "function") {
        try {
          window.Prism.highlightAllUnder(scope);
        } catch (e) {
          /* highlighting is best-effort; escaped source is already shown */
        }
      }
    }

    // Open the thread for source line n. `quote` (optional) is the verbatim highlighted text to attach to the
    // next comment; callers that open a line via the "+"/marker/nav paths pass none, which clears any stale
    // pending quote so those comments stay whole-line as before.
    function selectLine(n, quote) {
      state.selectedLine = n;
      state.pendingQuote = quote || "";
      // Update the selection highlight in whichever view is showing (Source rows and/or Rendered
      // anchors), without a full re-render.
      var prev = root.querySelectorAll(".iv-line.selected, .iv-anchor.selected");
      for (var i = 0; i < prev.length; i++) prev[i].classList.remove("selected");
      var row = root.querySelector('.iv-line[data-line="' + n + '"]');
      if (row) row.classList.add("selected");
      var anchors = root.querySelectorAll('.iv-anchor[data-source-line="' + n + '"]');
      for (var j = 0; j < anchors.length; j++) anchors[j].classList.add("selected");
      renderComments(root.querySelector(".iv-pane-comments"));
      var composer = root.querySelector(".iv-line-thread .iv-composer textarea");
      if (composer) composer.focus();
    }

    // ---- comments pane ----
    function groupLineComments() {
      var map = {};
      (state.detail.comments || []).forEach(function (c) {
        if (c.line >= 1) {
          (map[c.line] = map[c.line] || []).push(c);
        }
      });
      return map;
    }

    function renderComments(right) {
      if (!right) return;
      clear(right);
      var d = state.detail;
      var canComment = d.commentable && d.canContribute && isLatest();

      // General thread
      var gen = el("div", { cls: "iv-thread iv-general-thread" });
      gen.appendChild(el("h2", { cls: "iv-thread-title", text: "General comments" }));
      var generals = (d.comments || []).filter(function (c) {
        return c.line == null || c.line < 1;
      });
      appendCommentList(gen, generals);
      if (canComment) gen.appendChild(buildComposer(-1));
      else if (!d.commentable) gen.appendChild(el("div", { cls: "iv-hint", text: "Comments are disabled for this review." }));
      right.appendChild(gen);

      // Line thread (selected): the exact source line the reviewer clicked, in either view. Shows that
      // line's comments (c.line === selectedLine) plus a composer anchored to the same line, so the
      // comment round-trips to the pipeline unchanged.
      var lineThread = el("div", { cls: "iv-thread iv-line-thread" });
      if (state.selectedLine >= 1) {
        var ln = state.selectedLine;
        var titleRow = el("div", { cls: "iv-line-thread-head" });
        var headTitle = el("h2", { cls: "iv-thread-title", text: "Line " + ln });
        var headSnip = lineSnippet(ln);
        if (headSnip) {
          headTitle.appendChild(el("span", { cls: "iv-line-snippet", text: " \u00b7 \u201c" + headSnip + "\u201d" }));
        }
        titleRow.appendChild(headTitle);
        var clearBtn = el("button", { cls: "iv-clear-line", text: "\u00d7", attrs: { type: "button", title: "Clear selection" } });
        clearBtn.addEventListener("click", function () {
          state.selectedLine = 0;
          state.pendingQuote = "";
          var selRows = root.querySelectorAll(".iv-line.selected, .iv-anchor.selected");
          for (var i = 0; i < selRows.length; i++) selRows[i].classList.remove("selected");
          renderComments(right);
        });
        titleRow.appendChild(clearBtn);
        lineThread.appendChild(titleRow);
        var lineComments = (d.comments || []).filter(function (c) {
          return c.line === ln;
        });
        appendCommentList(lineThread, lineComments);
        if (canComment) lineThread.appendChild(buildComposer(ln));
      } else {
        var hintText;
        if (getCommentMode() === "highlight") {
          hintText = "Select any text in the document, then click \u201cAdd comment\u201d.";
        } else if (state.mode === "rendered") {
          hintText = "Hover any line and click + to comment on it.";
        } else {
          hintText = "Click the + on any line to comment on it.";
        }
        lineThread.appendChild(el("div", { cls: "iv-hint", text: hintText }));
      }
      right.appendChild(lineThread);

      // All line comments (navigation)
      var byLine = groupLineComments();
      var lineNums = Object.keys(byLine).map(Number).sort(function (a, b) {
        return a - b;
      });
      if (lineNums.length) {
        var nav = el("div", { cls: "iv-thread iv-line-nav" });
        nav.appendChild(el("h2", { cls: "iv-thread-title", text: "Line comments (" + lineNums.length + ")" }));
        lineNums.forEach(function (n) {
          var item = el("button", { cls: "iv-line-nav-item" });
          item.appendChild(el("span", { cls: "iv-chip", text: "L" + n }));
          var navSnip = lineSnippet(n);
          item.appendChild(el("span", { cls: "iv-line-nav-text", text: navSnip || "line " + n }));
          item.appendChild(el("span", { cls: "iv-line-nav-count", text: String(byLine[n].length) }));
          item.title = "Line " + n + (navSnip ? ": " + navSnip : "") + " \u2014 " + byLine[n].length + " comment(s)";
          item.addEventListener("click", function () {
            if (state.mode !== "source") {
              state.mode = "source";
              render();
            }
            selectLine(n);
            var row = root.querySelector('.iv-line[data-line="' + n + '"]');
            if (row && row.scrollIntoView) row.scrollIntoView({ block: "center" });
          });
          nav.appendChild(item);
        });
        right.appendChild(nav);
      }
    }

    // Map parentId -> ordered replies, computed across ALL comments so a reply always renders under its
    // parent — even if the parent lives in a different thread (general vs a specific line). The store keeps
    // threads one level deep (a reply-to-a-reply is re-parented to its root), so this is a simple two-level tree.
    function repliesByParent() {
      var map = {};
      (state.detail.comments || []).forEach(function (c) {
        if (c.parentId) {
          (map[c.parentId] = map[c.parentId] || []).push(c);
        }
      });
      return map;
    }

    function appendCommentList(container, comments) {
      // Only roots are listed at the top level of a thread; replies hang off their parent (below). This also
      // prevents a reply from showing twice when it happens to share the parent's anchor line.
      var roots = comments.filter(function (c) {
        return !c.parentId;
      });
      if (!roots.length) {
        container.appendChild(el("div", { cls: "iv-hint", text: "No comments yet." }));
        return;
      }
      var replies = repliesByParent();
      var list = el("div", { cls: "iv-comment-list" });
      roots.forEach(function (c) {
        list.appendChild(renderCommentItem(c, false));
        var kids = replies[c.id];
        if (kids && kids.length) {
          var thread = el("div", { cls: "iv-reply-list" });
          kids.forEach(function (r) {
            thread.appendChild(renderCommentItem(r, true));
          });
          list.appendChild(thread);
        }
      });
      container.appendChild(list);
    }

    // Render one comment. Replies (isReply) are indented under their parent. When a comment carries a
    // display-only authorLabel (e.g. an automation's "AI response"), that label is shown as the author with
    // an "automation" chip, while the real server-set author stays visible via the head's title for audit.
    function renderCommentItem(c, isReply) {
      var item = el("div", { cls: "iv-comment" + (isReply ? " iv-reply" : "") + (c.resolved ? " resolved" : "") });
      var head = el("div", { cls: "iv-comment-head" });
      var labelled = typeof c.authorLabel === "string" && c.authorLabel.length > 0;
      // text: (textContent) — never innerHTML — so a label can never inject markup.
      var authorSpan = el("span", { cls: "iv-comment-author", text: labelled ? c.authorLabel : (c.author || "unknown") });
      if (labelled) {
        authorSpan.title = "by " + (c.author || "unknown"); // the true, server-set identity is never hidden
      }
      head.appendChild(authorSpan);
      if (labelled) {
        head.appendChild(el("span", { cls: "iv-chip iv-chip-auto", text: "automation" }));
      }
      head.appendChild(el("span", { cls: "iv-comment-time", text: fmtTime(c.createdTs) }));
      // A reply inherits its parent's location, so only root comments carry the clickable line chip.
      if (!isReply && c.line >= 1) {
        var chip = el("button", { cls: "iv-chip iv-chip-line", text: "L" + c.line });
        var chipSnip = lineSnippet(c.line);
        chip.title = chipSnip ? "Line " + c.line + ": " + chipSnip : "Line " + c.line;
        chip.addEventListener("click", function () {
          if (state.mode !== "source") {
            state.mode = "source";
            render();
          }
          selectLine(c.line);
        });
        head.appendChild(chip);
      }
      item.appendChild(head);
      // The verbatim text this comment was highlighted on (highlight mode), shown back as quoted context
      // above the body so a sub-phrase or multi-line selection is preserved. textContent (via el's `text`) —
      // never innerHTML — so the quoted source can never inject markup.
      if (typeof c.quote === "string" && c.quote.length > 0) {
        item.appendChild(el("div", { cls: "iv-comment-quote", text: "\u201c" + c.quote + "\u201d" }));
      }
      // bodyHtml is server-sanitised markdown (MarkdownRenderer) — safe to insert.
      item.appendChild(el("div", { cls: "iv-comment-body", html: c.bodyHtml || "" }));

      if (state.detail.canContribute) {
        var foot = el("div", { cls: "iv-comment-foot" });
        var toggle = el("button", { cls: "iv-link-btn", text: c.resolved ? "Reopen" : "Resolve" });
        toggle.addEventListener("click", function () {
          postJson(viewUrl(docId) + "/resolveComment", { commentId: c.id, resolved: !c.resolved }).then(function (res) {
            if (!res.ok) {
              flash(errorMessage(res), true);
              return;
            }
            applyDetail(res.body);
            renderComments(root.querySelector(".iv-pane-comments"));
          });
        });
        foot.appendChild(toggle);
        item.appendChild(foot);
      }
      return item;
    }

    // line is the fixed source line the comment anchors to (a Source row or a Rendered element), or -1 for
    // a general (unanchored) comment.
    function buildComposer(line) {
      var wrap = el("div", { cls: "iv-composer" });
      // The verbatim highlighted text for this pending comment (highlight mode only). Shown back above the
      // box and sent as `quote` so the comment reflects exactly what was selected — a sub-phrase, or text
      // spanning several lines — instead of the whole anchor line.
      var quote = line >= 1 ? state.pendingQuote || "" : "";
      var placeholder;
      if (quote) {
        var qbox = el("div", { cls: "iv-composer-quote" });
        qbox.appendChild(el("span", { cls: "iv-quote-label", text: "Commenting on your selection:" }));
        // textContent (via el's `text`) — never innerHTML — so highlighted markup/markdown can never inject.
        qbox.appendChild(el("span", { cls: "iv-quote-text", text: "\u201c" + quote + "\u201d" }));
        wrap.appendChild(qbox);
        placeholder = "Add your comment on the highlighted text (markdown)\u2026";
      } else if (line >= 1) {
        var snip = lineSnippet(line);
        placeholder = snip
          ? "Comment on line " + line + " \u201c" + snip + "\u201d (markdown)\u2026"
          : "Comment on line " + line + " (markdown)\u2026";
      } else {
        placeholder = "Add a general comment (markdown)\u2026";
      }
      var ta = el("textarea", { attrs: { rows: "3", placeholder: placeholder } });
      wrap.appendChild(ta);
      var row = el("div", { cls: "iv-composer-row" });
      var err = el("span", { cls: "iv-composer-err" });
      var submit = el("button", { cls: "jenkins-button jenkins-button--primary", text: "Add comment" });
      submit.addEventListener("click", function () {
        var body = ta.value.trim();
        if (!body) {
          err.textContent = "Please enter a comment.";
          return;
        }
        err.textContent = "";
        submit.disabled = true;
        var payload = { body: body };
        if (line >= 1) payload.line = line;
        if (quote) payload.quote = quote;
        postJson(viewUrl(docId) + "/comments", payload)
          .then(function (res) {
            submit.disabled = false;
            if (!res.ok) {
              err.textContent = errorMessage(res);
              return;
            }
            applyDetail(res.body);
            // The quote belongs to this one comment; clear it so a follow-up comment on the same line is
            // whole-line again unless the reviewer highlights afresh.
            state.pendingQuote = "";
            // A line comment adds a gutter marker on the content pane, so re-render both panes (the
            // selection persists via state.selectedLine); a general comment only touches the right.
            if (line >= 1) {
              render();
            } else {
              renderComments(root.querySelector(".iv-pane-comments"));
            }
          })
          .catch(function () {
            submit.disabled = false;
            err.textContent = "Could not add comment.";
          });
      });
      row.appendChild(submit);
      row.appendChild(err);
      wrap.appendChild(row);
      return wrap;
    }

    function flash(msg, isError) {
      var f = root.querySelector(".iv-flash");
      if (!f) {
        f = el("div", { cls: "iv-flash" });
        root.insertBefore(f, root.firstChild);
      }
      f.textContent = msg;
      f.className = "iv-flash" + (isError ? " iv-flash-error" : " iv-flash-ok");
      window.setTimeout(function () {
        if (f && f.parentNode) f.parentNode.removeChild(f);
      }, 4000);
    }
  }

  // Client-side filter for the server-rendered per-job review table (grouped by report, one <tbody> per
  // group with a data-iv-group-row header). Narrows rows by free text and All / Notified / Needs-approval,
  // and hides a group heading when none of its rows match. No backend calls — pure DOM filtering.
  function mountTableFilter(container) {
    var tableId = container.getAttribute("data-iv-table-filter");
    var table = tableId ? document.getElementById(tableId) : null;
    if (!table) return;
    var search = container.querySelector(".iv-filter-search");
    var chips = container.querySelectorAll(".iv-chip-btn");
    var st = { q: "", filter: "all" };

    function rowMatches(row) {
      var text = (row.getAttribute("data-iv-text") || "").toLowerCase();
      var status = row.getAttribute("data-iv-status") || "";
      var notify = row.getAttribute("data-iv-notify") === "true";
      var mode = row.getAttribute("data-iv-mode") || "review";
      if (st.q && text.indexOf(st.q) < 0) return false;
      if (st.filter === "notified" && !notify) return false;
      if (st.filter === "needs" && !(mode !== "info" && status === "OPEN")) return false;
      return true;
    }

    function apply() {
      st.q = search ? search.value.toLowerCase() : "";
      var bodies = table.tBodies;
      for (var b = 0; b < bodies.length; b++) {
        var rows = bodies[b].rows;
        var headers = [];
        var anyVisible = false;
        for (var i = 0; i < rows.length; i++) {
          var row = rows[i];
          if (row.getAttribute("data-iv-group-row") === "true") {
            headers.push(row);
            continue;
          }
          var ok = rowMatches(row);
          row.style.display = ok ? "" : "none";
          if (ok) anyVisible = true;
        }
        for (var h = 0; h < headers.length; h++) headers[h].style.display = anyVisible ? "" : "none";
      }
    }

    if (search) {
      search.addEventListener("input", apply);
    }
    for (var i = 0; i < chips.length; i++) {
      (function (chip) {
        chip.addEventListener("click", function () {
          st.filter = chip.getAttribute("data-filter") || "all";
          for (var j = 0; j < chips.length; j++) chips[j].classList.remove("active");
          chip.classList.add("active");
          apply();
        });
      })(chips[i]);
    }
    apply();
  }

  function init() {
    var roots = document.querySelectorAll(".iv-app[data-iv-root]");
    for (var i = 0; i < roots.length; i++) mount(roots[i]);
    var filters = document.querySelectorAll("[data-iv-table-filter]");
    for (var k = 0; k < filters.length; k++) mountTableFilter(filters[k]);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
