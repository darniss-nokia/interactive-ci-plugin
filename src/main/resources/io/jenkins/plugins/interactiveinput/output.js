/**
 * © 2026 Nokia
 * Licensed under the MIT License
 * SPDX-License-Identifier: MIT
*/

/*
 * interactive-input — Interactive Output charts + table tools.
 *
 * Renders the per-report JSON models emitted by InteractiveOutputJobAction/index.jelly (each in an
 * escaped data-io-model attribute) as theme-aware ECharts line / bar / pie charts, using the global
 * `echarts` provided by the echarts-api adjunct. Colours are resolved from Jenkins CSS custom properties
 * at render time so charts match the active (light/dark) theme. Also wires a pure client-side filter +
 * column sort for the build page's metric tables (no backend calls). Degrades gracefully: if echarts is
 * unavailable or a model is empty/invalid, the container shows a readable message instead of throwing.
 */
(function () {
  "use strict";

  // Jenkins design-system colour variables, with safe hex fallbacks, used for the series palette.
  var PALETTE_VARS = [
    ["--blue", "#4285f4"],
    ["--green", "#1ea672"],
    ["--orange", "#f5a623"],
    ["--purple", "#8e5bd1"],
    ["--red", "#e6492d"],
    ["--cyan", "#22a0b8"],
    ["--pink", "#d6409f"],
    ["--yellow", "#e8b923"]
  ];

  function cssVar(name, fallback) {
    try {
      var v = getComputedStyle(document.documentElement).getPropertyValue(name);
      v = v ? v.trim() : "";
      return v || fallback;
    } catch (e) {
      return fallback;
    }
  }

  function palette() {
    return PALETTE_VARS.map(function (p) {
      return cssVar(p[0], p[1]);
    });
  }

  // ---- date helpers (time-series charts + date-aware table sorting) ----
  // Parses the supported label formats — yyyy, yyyy-MM, yyyy-MM-dd, yyyy-MM-dd[ T]HH:mm[:ss] — into date
  // parts, or null when the text is not a recognised date. Missing parts default to the start of the
  // period so truncation/sorting is well-defined at every granularity.
  var DATE_RE = /^(\d{4})(?:-(\d{2})(?:-(\d{2})(?:[ T](\d{2}):(\d{2})(?::(\d{2}))?)?)?)?$/;

  function parseDateParts(text) {
    if (text == null) return null;
    var m = DATE_RE.exec(String(text).trim());
    if (!m) return null;
    var y = parseInt(m[1], 10);
    var mo = m[2] ? parseInt(m[2], 10) : 1;
    var d = m[3] ? parseInt(m[3], 10) : 1;
    var hh = m[4] ? parseInt(m[4], 10) : 0;
    var mm = m[5] ? parseInt(m[5], 10) : 0;
    var ss = m[6] ? parseInt(m[6], 10) : 0;
    if (mo < 1 || mo > 12 || d < 1 || d > 31 || hh > 23 || mm > 59 || ss > 59) return null;
    // Reject impossible days (e.g. Feb 30) via a UTC round-trip.
    var ms = Date.UTC(y, mo - 1, d, hh, mm, ss);
    var back = new Date(ms);
    if (back.getUTCFullYear() !== y || back.getUTCMonth() !== mo - 1 || back.getUTCDate() !== d) return null;
    return { y: y, mo: mo, d: d, hh: hh, mm: mm, ss: ss, ms: ms };
  }

  // UTC epoch-ms for a label, or NaN when it is not a date.
  function dateMs(text) {
    var p = parseDateParts(text);
    return p ? p.ms : NaN;
  }

  // Epoch-ms of the period START a label falls in, truncated to the granularity (day/month/year); NaN when
  // not a date. "time" keeps full precision.
  function bucketMs(text, gran) {
    var p = parseDateParts(text);
    if (!p) return NaN;
    if (gran === "year") return Date.UTC(p.y, 0, 1);
    if (gran === "month") return Date.UTC(p.y, p.mo - 1, 1);
    if (gran === "day") return Date.UTC(p.y, p.mo - 1, p.d);
    return p.ms;
  }

  function pad2(n) {
    return (n < 10 ? "0" : "") + n;
  }

  // Human-readable bucket label for a granularity (used as the time-series x-axis category).
  function bucketLabel(parts, gran) {
    if (gran === "year") return "" + parts.y;
    if (gran === "month") return parts.y + "-" + pad2(parts.mo);
    if (gran === "day") return parts.y + "-" + pad2(parts.mo) + "-" + pad2(parts.d);
    return parts.y + "-" + pad2(parts.mo) + "-" + pad2(parts.d) + " " + pad2(parts.hh) + ":" + pad2(parts.mm);
  }

  function parseModel(root) {
    var raw = root.getAttribute("data-io-model") || root.getAttribute("data-io-trend");
    if (!raw) {
      return null;
    }
    try {
      return JSON.parse(raw);
    } catch (e) {
      return null;
    }
  }

  function showMessage(canvas, text) {
    canvas.textContent = "";
    var p = document.createElement("div");
    p.className = "io-empty";
    p.textContent = text;
    canvas.appendChild(p);
  }

  function commonStyle() {
    return {
      textColor: cssVar("--text-color", "#333"),
      subColor: cssVar("--text-color-secondary", "#888"),
      lineColor: cssVar("--panel-border-color", "rgba(128,128,128,0.25)"),
      colors: palette()
    };
  }

  function buildPieOption(model, s) {
    return {
      color: s.colors,
      textStyle: { color: s.textColor },
      tooltip: { trigger: "item", confine: true, appendToBody: true, formatter: "{b}: {c} ({d}%)" },
      legend: { top: 0, type: "scroll", textStyle: { color: s.textColor } },
      series: [
        {
          name: model.report || "",
          type: "pie",
          radius: ["40%", "70%"],
          center: ["50%", "56%"],
          avoidLabelOverlap: true,
          itemStyle: { borderColor: cssVar("--background", "#fff"), borderWidth: 2 },
          label: { color: s.textColor },
          data: Array.isArray(model.slices) ? model.slices : []
        }
      ]
    };
  }

  function buildAxisOption(model, s) {
    var isBar = model.type === "bar";
    var series = (model.series || []).map(function (ser, i) {
      var common = {
        name: ser.name,
        type: isBar ? "bar" : "line",
        data: ser.data,
        itemStyle: { color: s.colors[i % s.colors.length] },
        emphasis: { focus: "series" }
      };
      if (isBar) {
        common.barMaxWidth = 28;
      } else {
        common.connectNulls = false;
        common.showSymbol = true;
        common.symbolSize = 6;
        common.smooth = false;
        common.lineStyle = { width: 2 };
      }
      return common;
    });

    return {
      color: s.colors,
      textStyle: { color: s.textColor },
      tooltip: { trigger: "axis", confine: true, appendToBody: true },
      legend: { top: 0, type: "scroll", textStyle: { color: s.textColor } },
      grid: { left: 12, right: 24, bottom: 12, top: series.length > 1 ? 40 : 24, containLabel: true },
      xAxis: {
        type: "category",
        boundaryGap: isBar,
        data: model.builds,
        axisLabel: { color: s.subColor },
        axisLine: { lineStyle: { color: s.lineColor } },
        axisTick: { show: false }
      },
      yAxis: {
        type: "value",
        axisLabel: { color: s.subColor },
        axisLine: { show: false },
        splitLine: { lineStyle: { color: s.lineColor } }
      },
      series: series
    };
  }

  // Time-series line chart of the latest build's date-labelled points (backend-sorted by date). When a
  // granularity other than "time" is chosen, points are bucketed to the period and their values summed
  // (sensible for additive metrics like cost/count; "time" always shows the exact per-point values).
  function buildTimeseriesOption(model, granularity, s) {
    var pts = Array.isArray(model.points) ? model.points : [];
    var labels = [];
    var values = [];
    if (granularity && granularity !== "time") {
      var order = [];
      var acc = {};
      pts.forEach(function (p) {
        var parts = parseDateParts(p.label);
        var key = parts ? bucketLabel(parts, granularity) : String(p.label);
        if (!Object.prototype.hasOwnProperty.call(acc, key)) {
          acc[key] = 0;
          order.push(key);
        }
        var v = typeof p.value === "number" ? p.value : Number(p.value);
        acc[key] += isNaN(v) ? 0 : v;
      });
      order.forEach(function (k) {
        labels.push(k);
        values.push(acc[k]);
      });
    } else {
      pts.forEach(function (p) {
        labels.push(p.label);
        var v = typeof p.value === "number" ? p.value : Number(p.value);
        values.push(isNaN(v) ? null : v);
      });
    }
    return {
      color: s.colors,
      textStyle: { color: s.textColor },
      tooltip: { trigger: "axis", confine: true, appendToBody: true },
      grid: { left: 12, right: 24, bottom: 12, top: 24, containLabel: true },
      xAxis: {
        type: "category",
        boundaryGap: false,
        data: labels,
        axisLabel: { color: s.subColor },
        axisLine: { lineStyle: { color: s.lineColor } },
        axisTick: { show: false }
      },
      yAxis: {
        type: "value",
        axisLabel: { color: s.subColor },
        axisLine: { show: false },
        splitLine: { lineStyle: { color: s.lineColor } }
      },
      series: [
        {
          name: model.report || "",
          type: "line",
          data: values,
          itemStyle: { color: s.colors[0] },
          showSymbol: true,
          symbolSize: 6,
          smooth: false,
          lineStyle: { width: 2 },
          emphasis: { focus: "series" }
        }
      ]
    };
  }

  function buildOption(model) {
    var s = commonStyle();
    if (model.type === "pie") {
      return buildPieOption(model, s);
    }
    if (model.type === "timeseries") {
      return buildTimeseriesOption(model, "time", s);
    }
    return buildAxisOption(model, s);
  }

  function isEmptyModel(model) {
    if (!model) return true;
    if (model.type === "pie") {
      return !Array.isArray(model.slices) || model.slices.length === 0;
    }
    if (model.type === "timeseries") {
      return !Array.isArray(model.points) || model.points.length === 0;
    }
    return (
      !Array.isArray(model.series) ||
      model.series.length === 0 ||
      !Array.isArray(model.builds) ||
      model.builds.length === 0
    );
  }

  // Segmented Time/Day/Month/Year control inserted above a chart canvas; calls onChange(gran) on select.
  function mountGranularityBar(canvas, onChange) {
    var bar = document.createElement("div");
    bar.className = "io-gran";
    var label = document.createElement("span");
    label.className = "io-gran-label";
    label.textContent = "Granularity:";
    bar.appendChild(label);
    var current = "time";
    var btns = [];
    [["time", "Time"], ["day", "Day"], ["month", "Month"], ["year", "Year"]].forEach(function (o) {
      var b = document.createElement("button");
      b.type = "button";
      b.className = "io-gran-btn" + (o[0] === current ? " active" : "");
      b.textContent = o[1];
      b.setAttribute("data-gran", o[0]);
      b.addEventListener("click", function () {
        if (current === o[0]) return;
        current = o[0];
        btns.forEach(function (x) {
          x.classList.toggle("active", x.getAttribute("data-gran") === current);
        });
        onChange(current);
      });
      btns.push(b);
      bar.appendChild(b);
    });
    if (canvas.parentNode) {
      canvas.parentNode.insertBefore(bar, canvas);
    }
  }

  function mountChart(root) {
    var canvas = root.querySelector("[data-io-chart]") || root;
    if (!window.echarts || typeof window.echarts.init !== "function") {
      showMessage(canvas, "Chart unavailable (echarts-api plugin not loaded).");
      return;
    }
    var model = parseModel(root);
    if (isEmptyModel(model)) {
      showMessage(canvas, "No data to display.");
      return;
    }

    var chart;
    try {
      chart = window.echarts.init(canvas);
      chart.setOption(buildOption(model));
      // A time-series chart gets a Time/Day/Month/Year granularity toolbar above the canvas; re-bucketing
      // is client-side (no reload). Other chart types render once.
      if (model.type === "timeseries") {
        mountGranularityBar(canvas, function (gran) {
          chart.setOption(buildTimeseriesOption(model, gran, commonStyle()), true);
        });
      }
    } catch (e) {
      showMessage(canvas, "Could not render the chart.");
      return;
    }

    // Click to open the relevant build's Interactive Output page.
    var rootUrl = (root.getAttribute("data-root-url") || "").replace(/\/$/, "");
    chart.on("click", function (params) {
      var target = "";
      if (model.type === "pie" || model.type === "timeseries") {
        target = model.url || "";
      } else {
        var urls = Array.isArray(model.urls) ? model.urls : [];
        var idx = params && typeof params.dataIndex === "number" ? params.dataIndex : -1;
        target = idx >= 0 && idx < urls.length ? urls[idx] : "";
      }
      if (target) {
        window.location.assign(rootUrl + "/" + target);
      }
    });

    window.addEventListener("resize", function () {
      try {
        chart.resize();
      } catch (e) {
        /* ignore */
      }
    });
  }

  // ---- client-side table filter + column sort (build page) ----
  function cellText(row, col) {
    var cell = row.cells[col];
    return cell ? (cell.textContent || "").trim() : "";
  }

  function numeric(text) {
    if (text == null) return NaN;
    var cleaned = text.replace(/[^0-9eE.+-]/g, "");
    if (cleaned === "" || cleaned === "-" || cleaned === "+") return NaN;
    var n = Number(cleaned);
    return isNaN(n) ? NaN : n;
  }

  function sortTable(table, col, asc, isDate) {
    var body = table.tBodies[0];
    if (!body) return;
    var rows = Array.prototype.slice.call(body.rows);
    rows.sort(function (a, b) {
      var ta = cellText(a, col);
      var tb = cellText(b, col);
      var cmp;
      if (isDate) {
        var da = dateMs(ta);
        var db = dateMs(tb);
        da = isNaN(da) ? Infinity : da;
        db = isNaN(db) ? Infinity : db;
        cmp = da - db;
      } else {
        var na = numeric(ta);
        var nb = numeric(tb);
        if (!isNaN(na) && !isNaN(nb)) {
          cmp = na - nb;
        } else {
          cmp = ta.localeCompare(tb);
        }
      }
      return asc ? cmp : -cmp;
    });
    rows.forEach(function (r) {
      body.appendChild(r);
    });
  }

  // Returns the index of the first column whose every non-empty cell parses as a date, else -1. Used to
  // decide whether the "Group by date" granularity control applies to a table.
  function detectDateColumn(table) {
    var body = table.tBodies[0];
    if (!body || !body.rows.length) return -1;
    var head = table.tHead && table.tHead.rows[0];
    var cols = head ? head.cells.length : body.rows[0] ? body.rows[0].cells.length : 0;
    for (var c = 0; c < cols; c++) {
      var total = 0;
      var dates = 0;
      for (var i = 0; i < body.rows.length; i++) {
        var t = cellText(body.rows[i], c);
        if (!t) continue;
        total++;
        if (!isNaN(dateMs(t))) dates++;
      }
      if (total > 0 && dates === total) return c;
    }
    return -1;
  }

  // Reorders rows so they are grouped by the date column truncated to the granularity, ascending. Stable
  // (equal buckets keep their original order); rows without a parseable date sink to the bottom.
  function sortTableByBucket(table, col, gran) {
    var body = table.tBodies[0];
    if (!body) return;
    var rows = Array.prototype.slice.call(body.rows);
    var decorated = rows.map(function (r, i) {
      var k = bucketMs(cellText(r, col), gran);
      return { r: r, k: isNaN(k) ? Infinity : k, i: i };
    });
    decorated.sort(function (a, b) {
      return a.k !== b.k ? a.k - b.k : a.i - b.i;
    });
    decorated.forEach(function (d) {
      body.appendChild(d.r);
    });
  }

  function mountTableTools(container) {
    var tableId = container.getAttribute("data-io-table-filter");
    var table = tableId ? document.getElementById(tableId) : null;
    if (!table) return;
    var search = container.querySelector(".io-filter-search");
    var dateCol = detectDateColumn(table);

    function applyFilter() {
      var q = (search ? search.value : "").toLowerCase();
      var body = table.tBodies[0];
      if (!body) return;
      for (var i = 0; i < body.rows.length; i++) {
        var row = body.rows[i];
        var text = (row.textContent || "").toLowerCase();
        row.style.display = !q || text.indexOf(q) >= 0 ? "" : "none";
      }
    }
    if (search) {
      search.addEventListener("input", applyFilter);
    }

    // Date-aware "Group by date" control — only shown when a column is entirely dates (e.g. a time-series
    // report). Reorders rows by that column truncated to the chosen granularity.
    if (dateCol >= 0) {
      var gwrap = document.createElement("label");
      gwrap.className = "io-gran-select";
      gwrap.appendChild(document.createTextNode("Group by date:"));
      var sel = document.createElement("select");
      sel.className = "jenkins-select__input io-gran-select-input";
      [["none", "None"], ["day", "Day"], ["month", "Month"], ["year", "Year"]].forEach(function (o) {
        var opt = document.createElement("option");
        opt.value = o[0];
        opt.textContent = o[1];
        sel.appendChild(opt);
      });
      sel.addEventListener("change", function () {
        if (sel.value !== "none") {
          sortTableByBucket(table, dateCol, sel.value);
        }
      });
      gwrap.appendChild(sel);
      container.appendChild(gwrap);
    }

    // Sortable headers: click to toggle asc/desc; a small aria-sort marker is toggled for a11y. The
    // detected date column sorts chronologically rather than lexically.
    var heads = table.tHead ? table.tHead.rows[0] : null;
    if (heads) {
      Array.prototype.forEach.call(heads.cells, function (th, col) {
        th.classList.add("io-th-sortable");
        var asc = true;
        th.addEventListener("click", function () {
          sortTable(table, col, asc, col === dateCol);
          var all = heads.cells;
          for (var k = 0; k < all.length; k++) all[k].removeAttribute("aria-sort");
          th.setAttribute("aria-sort", asc ? "ascending" : "descending");
          asc = !asc;
        });
      });
    }
    applyFilter();
  }

  function init() {
    var charts = document.querySelectorAll(".io-chart[data-io-model], .io-chart[data-io-trend]");
    Array.prototype.forEach.call(charts, mountChart);
    var tools = document.querySelectorAll("[data-io-table-filter]");
    Array.prototype.forEach.call(tools, mountTableTools);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
