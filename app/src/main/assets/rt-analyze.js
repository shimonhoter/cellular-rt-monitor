/* Offline advanced analysis of an RF-sample CSV exported by the app (or the device's full sample store).
   Produces summary, tables, charts and rule-based conclusions, and a printable (PDF) report. */
(function () {
  "use strict";
  var $ = function (id) { return document.getElementById(id); };
  var esc = function (v) { return String(v == null ? "" : v).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;"); };
  var pad = function (n) { return String(n).padStart(2, "0"); };
  var fmtT = function (t) { var d = new Date(t); return pad(d.getDate()) + "/" + pad(d.getMonth() + 1) + "/" + d.getFullYear() + " " + pad(d.getHours()) + ":" + pad(d.getMinutes()) + ":" + pad(d.getSeconds()); };
  var fmtDur = function (ms) {
    var s = Math.round(ms / 1000);
    if (s < 60) return s + " שנ׳";
    var m = Math.floor(s / 60); if (m < 60) return m + " דק׳ " + (s % 60) + " שנ׳";
    var h = Math.floor(m / 60); return h + " שע׳ " + (m % 60) + " דק׳";
  };
  var f1 = function (v) { return v == null || isNaN(v) ? "--" : (Math.round(v * 10) / 10).toFixed(1); };

  var DATA = null;      // normalized rows (sorted by time)
  var RES = null;       // analysis result
  var SOURCE = "";

  /* ------------------------------ CSV parsing ------------------------------ */
  function parseCsv(text) {
    if (text.charCodeAt(0) === 0xFEFF) text = text.slice(1);
    var rows = [], row = [], cur = "", q = false, i = 0, n = text.length;
    while (i < n) {
      var c = text[i];
      if (q) {
        if (c === '"') { if (text[i + 1] === '"') { cur += '"'; i++; } else q = false; } else cur += c;
      } else if (c === '"') q = true;
      else if (c === ",") { row.push(cur); cur = ""; }
      else if (c === "\n") { row.push(cur); rows.push(row); row = []; cur = ""; }
      else if (c !== "\r") cur += c;
      i++;
    }
    if (cur !== "" || row.length) { row.push(cur); rows.push(row); }
    return rows;
  }

  var ALIAS = {
    t: ["epoch_ms", "timestamp", "time", "local_time", "datetime", "date"],
    lat: ["lat", "latitude"], lon: ["lon", "lng", "longitude"],
    tech: ["tech", "rat_name"], rat: ["rat"], band: ["band"], plmn: ["plmn"],
    pci: ["pci", "cell_id", "cellid", "psc"], gnb: ["gnb", "enb", "enodeb", "enb_id"], arfcn: ["arfcn", "earfcn", "nrarfcn"],
    rsrp: ["rsrp", "dbm"], rsrq: ["rsrq"], sinr: ["sinr", "rssnr"], rssi: ["rssi"], ta: ["ta", "timing_advance"],
    ho: ["handover", "ho"], tx: ["tx_power", "tx"]
  };
  function num(v) {
    if (v == null || v === "" || v === "---" || v === "--") return null;
    var x = Number(v); return isFinite(x) ? x : null;
  }

  function normalize(table) {
    if (table.length < 2) throw new Error("הקובץ ריק או ללא שורות נתונים");
    var head = table[0].map(function (h) { return h.trim().toLowerCase(); });
    var col = {};
    Object.keys(ALIAS).forEach(function (k) {
      for (var a = 0; a < ALIAS[k].length; a++) { var ix = head.indexOf(ALIAS[k][a]); if (ix >= 0) { col[k] = ix; break; } }
    });
    if (col.t == null || col.rsrp == null) throw new Error("לא נמצאו עמודות זמן ו-RSRP — האם זה קובץ שיוצא מהאפליקציה?");
    var out = [];
    for (var r = 1; r < table.length; r++) {
      var x = table[r];
      if (x.length < 3) continue;
      var tv = x[col.t], t = /^\d{11,}$/.test(tv) ? Number(tv) : Date.parse(tv);
      if (!isFinite(t)) continue;
      var g = function (k) { return col[k] == null ? null : x[col[k]]; };
      var tech = g("tech") || g("rat") || "";
      out.push({
        t: t, lat: num(g("lat")), lon: num(g("lon")), tech: String(tech).trim(), band: String(g("band") || "").trim(),
        plmn: String(g("plmn") || "").trim(), pci: num(g("pci")), gnb: num(g("gnb")), arfcn: num(g("arfcn")),
        rsrp: num(g("rsrp")), rsrq: num(g("rsrq")), sinr: num(g("sinr")), rssi: num(g("rssi")), ta: num(g("ta")),
        ho: g("ho") === "1" || g("ho") === "true", tx: num(g("tx"))
      });
    }
    out.sort(function (a, b) { return a.t - b.t; });
    if (!out.length) throw new Error("לא נמצאו שורות תקינות");
    return out;
  }

  /* ------------------------------- statistics ------------------------------ */
  function quant(sorted, q) {
    if (!sorted.length) return null;
    var p = (sorted.length - 1) * q, lo = Math.floor(p), hi = Math.ceil(p);
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (p - lo);
  }
  function stats(vals) {
    var a = vals.filter(function (v) { return v != null; });
    if (!a.length) return null;
    var s = a.slice().sort(function (x, y) { return x - y; });
    var sum = 0; a.forEach(function (v) { sum += v; });
    var mean = sum / a.length, vr = 0; a.forEach(function (v) { vr += (v - mean) * (v - mean); });
    return { n: a.length, min: s[0], max: s[s.length - 1], mean: mean, median: quant(s, 0.5), p5: quant(s, 0.05), p95: quant(s, 0.95), std: Math.sqrt(vr / a.length) };
  }
  var cellKey = function (r) { return (r.tech || "?") + "|" + (r.gnb != null ? "e" + r.gnb : "") + "|p" + (r.pci == null ? "?" : r.pci); };
  var cellLabel = function (r) { return (r.tech || "?") + (r.gnb != null ? " eNB " + r.gnb : "") + " PCI " + (r.pci == null ? "?" : r.pci); };

  var RSRP_BUCKETS = [
    { k: "מצוין (≥ -80)", test: function (v) { return v >= -80; }, color: "#22c55e" },
    { k: "טוב (-80..-90)", test: function (v) { return v < -80 && v >= -90; }, color: "#84cc16" },
    { k: "בינוני (-90..-100)", test: function (v) { return v < -90 && v >= -100; }, color: "#eab308" },
    { k: "חלש (-100..-110)", test: function (v) { return v < -100 && v >= -110; }, color: "#f97316" },
    { k: "גרוע (< -110)", test: function (v) { return v < -110; }, color: "#ef4444" }
  ];
  var SINR_BUCKETS = [
    { k: "מצוין (≥ 20)", test: function (v) { return v >= 20; }, color: "#22c55e" },
    { k: "טוב (13..20)", test: function (v) { return v < 20 && v >= 13; }, color: "#84cc16" },
    { k: "בינוני (0..13)", test: function (v) { return v < 13 && v >= 0; }, color: "#eab308" },
    { k: "גרוע (< 0)", test: function (v) { return v < 0; }, color: "#ef4444" }
  ];
  function buckets(vals, defs) {
    var a = vals.filter(function (v) { return v != null; }), out = defs.map(function (d) { return { k: d.k, color: d.color, n: 0 }; });
    a.forEach(function (v) { for (var i = 0; i < defs.length; i++) if (defs[i].test(v)) { out[i].n++; break; } });
    out.forEach(function (o) { o.pct = a.length ? o.n * 100 / a.length : 0; });
    return { total: a.length, items: out };
  }

  function analyze(rows) {
    var n = rows.length, t0 = rows[0].t, t1 = rows[n - 1].t, res = { n: n, t0: t0, t1: t1, dur: t1 - t0 };

    // sampling interval & gaps
    var dts = [];
    for (var i = 1; i < n; i++) dts.push(rows[i].t - rows[i - 1].t);
    var sd = dts.slice().sort(function (a, b) { return a - b; });
    var medDt = sd.length ? quant(sd, 0.5) : 1000; if (medDt < 200) medDt = 200;
    var gapThr = Math.max(10000, medDt * 5);
    res.medDt = medDt; res.gaps = []; res.gapTime = 0;
    for (i = 1; i < n; i++) if (dts[i - 1] > gapThr) { res.gaps.push({ from: rows[i - 1].t, to: rows[i].t, ms: dts[i - 1] }); res.gapTime += dts[i - 1]; }
    var wDt = function (idx) { var d = idx + 1 < n ? rows[idx + 1].t - rows[idx].t : medDt; return d > gapThr ? medDt : d; };   // time weight of a sample

    // metric stats
    res.m = {};
    ["rsrp", "rsrq", "sinr", "rssi", "tx"].forEach(function (k) { res.m[k] = stats(rows.map(function (r) { return r[k]; })); });
    res.rsrpB = buckets(rows.map(function (r) { return r.rsrp; }), RSRP_BUCKETS);
    res.sinrB = buckets(rows.map(function (r) { return r.sinr; }), SINR_BUCKETS);

    // tech / band share (time weighted)
    function share(keyFn) {
      var m = {}, tot = 0;
      rows.forEach(function (r, ix) { var k = keyFn(r); if (!k) return; var w = wDt(ix); m[k] = (m[k] || 0) + w; tot += w; });
      return Object.keys(m).map(function (k) { return { k: k, ms: m[k], pct: tot ? m[k] * 100 / tot : 0 }; }).sort(function (a, b) { return b.ms - a.ms; });
    }
    res.techShare = share(function (r) { return r.tech; });
    res.bandShare = share(function (r) { return r.band ? (r.tech ? r.tech.replace(/\s.*$/, "") + " " : "") + r.band : ""; });
    var bandRsrp = {};
    rows.forEach(function (r) { if (r.band && r.rsrp != null) { (bandRsrp[r.band] = bandRsrp[r.band] || []).push(r.rsrp); } });
    res.bandAvg = Object.keys(bandRsrp).map(function (b) { var s = stats(bandRsrp[b]); return { band: b, n: s.n, mean: s.mean }; }).filter(function (b) { return b.n >= 20; });

    // cells
    var cells = {};
    rows.forEach(function (r, ix) {
      var k = cellKey(r), c = cells[k];
      if (!c) c = cells[k] = { k: k, label: cellLabel(r), band: r.band, n: 0, ms: 0, rsrp: [], sinr: [], first: r.t, last: r.t };
      c.n++; c.ms += wDt(ix); c.last = r.t; if (r.rsrp != null) c.rsrp.push(r.rsrp); if (r.sinr != null) c.sinr.push(r.sinr);
    });
    res.cells = Object.keys(cells).map(function (k) {
      var c = cells[k], sr = stats(c.rsrp), ss = stats(c.sinr);
      return { label: c.label, band: c.band, n: c.n, ms: c.ms, pct: 0, rsrp: sr, sinr: ss, first: c.first, last: c.last };
    }).sort(function (a, b) { return b.ms - a.ms; });
    var totMs = 0; res.cells.forEach(function (c) { totMs += c.ms; });
    res.cells.forEach(function (c) { c.pct = totMs ? c.ms * 100 / totMs : 0; });

    // handovers (cell change or explicit flag)
    res.hos = []; var lastChange = t0, prevK = cellKey(rows[0]), dwell = [];
    for (i = 1; i < n; i++) {
      var k = cellKey(rows[i]);
      if (k !== prevK && rows[i].t - rows[i - 1].t <= gapThr) {
        dwell.push(rows[i].t - lastChange);
        res.hos.push({ t: rows[i].t, from: cellLabel(rows[i - 1]), to: cellLabel(rows[i]), fromK: prevK, toK: k,
          rsrpBefore: rows[i - 1].rsrp, rsrpAfter: rows[i].rsrp, dwell: rows[i].t - lastChange, pp: false });
        lastChange = rows[i].t;
      } else if (k !== prevK) lastChange = rows[i].t;
      prevK = k;
    }
    for (i = 1; i < res.hos.length; i++) {
      if (res.hos[i].toK === res.hos[i - 1].fromK && res.hos[i].fromK === res.hos[i - 1].toK && res.hos[i].t - res.hos[i - 1].t < 60000) {
        res.hos[i].pp = true; res.hos[i - 1].pp = true;
      }
    }
    res.pingPong = 0; for (i = 1; i < res.hos.length; i++) if (res.hos[i].pp && res.hos[i - 1].pp && res.hos[i].toK === res.hos[i - 1].fromK) res.pingPong++;
    var ds = dwell.slice().sort(function (a, b) { return a - b; });
    res.dwell = ds.length ? { min: ds[0], med: quant(ds, 0.5), max: ds[ds.length - 1] } : null;
    res.hoPerHour = res.dur > 60000 ? res.hos.length * 3600000 / res.dur : null;

    // poor-signal episodes (< -110 dBm for >= 5 s)
    res.poor = []; var run = null;
    rows.forEach(function (r, ix) {
      var bad = r.rsrp != null && r.rsrp < -110;
      if (bad) { if (!run) run = { from: r.t, to: r.t, worst: r.rsrp, lat: r.lat, lon: r.lon }; run.to = r.t; if (r.rsrp < run.worst) { run.worst = r.rsrp; run.lat = r.lat; run.lon = r.lon; } }
      else if (run) { if (run.to - run.from >= 5000) res.poor.push(run); run = null; }
    });
    if (run && run.to - run.from >= 5000) res.poor.push(run);
    res.poorTime = res.poor.reduce(function (s, e) { return s + (e.to - e.from); }, 0);

    // spatial grid (~100 m)
    var gr = {}, withLoc = 0;
    rows.forEach(function (r) {
      if (r.lat == null || r.lon == null || r.rsrp == null) return;
      withLoc++;
      var key = Math.round(r.lat * 1000) + "," + Math.round(r.lon * 1000), g = gr[key];
      if (!g) g = gr[key] = { lat: 0, lon: 0, n: 0, sum: 0, min: 0 };
      g.lat += r.lat; g.lon += r.lon; g.n++; g.sum += r.rsrp;
    });
    res.withLoc = withLoc;
    res.spots = Object.keys(gr).map(function (k) { var g = gr[k]; return { lat: g.lat / g.n, lon: g.lon / g.n, n: g.n, mean: g.sum / g.n }; }).filter(function (g) { return g.n >= 3; });
    res.worstSpots = res.spots.slice().sort(function (a, b) { return a.mean - b.mean; }).slice(0, 8);
    res.bestSpots = res.spots.slice().sort(function (a, b) { return b.mean - a.mean; }).slice(0, 5);

    // interference hint: good RSRP but poor RSRQ
    var hintN = 0, hintD = 0;
    rows.forEach(function (r) { if (r.rsrp != null && r.rsrq != null && r.rsrp > -95) { hintD++; if (r.rsrq < -14) hintN++; } });
    res.interfPct = hintD ? hintN * 100 / hintD : null;

    var ta = rows.map(function (r) { return r.ta; }), tas = stats(ta);
    res.ta = tas;
    res.conclusions = conclude(res);
    return res;
  }

  function conclude(R) {
    var C = [], add = function (lvl, txt) { C.push({ lvl: lvl, txt: txt }); };
    var rs = R.m.rsrp, sn = R.m.sinr;
    if (rs) {
      var good = R.rsrpB.items[0].pct + R.rsrpB.items[1].pct, poor = R.rsrpB.items[3].pct + R.rsrpB.items[4].pct;
      var lvl = rs.median >= -90 ? "ok" : rs.median >= -105 ? "warn" : "bad";
      add(lvl, "עוצמת הקליטה (RSRP): חציון " + f1(rs.median) + " dBm, ממוצע " + f1(rs.mean) + ", טווח " + f1(rs.min) + " עד " + f1(rs.max) + ". " + f1(good) + "% מהדגימות טובות או מצוינות ו-" + f1(poor) + "% חלשות או גרועות.");
    }
    if (sn) {
      var lv = sn.median >= 13 ? "ok" : sn.median >= 5 ? "warn" : "bad";
      add(lv, "איכות האות (SINR): חציון " + f1(sn.median) + " dB, אחוזון 5 הוא " + f1(sn.p5) + " dB. " + (sn.median < 5 ? "ערכים נמוכים מצביעים על הפרעות או עומס — ייתכן שיש ירידה בקצבי הנתונים." : sn.median < 13 ? "סביר, אך בחלק מהזמן ייתכנו קצבים מופחתים." : "מספיק לקצבי נתונים טובים ברוב הזמן."));
    }
    if (R.poor.length) {
      var longest = R.poor.reduce(function (m, e) { return Math.max(m, e.to - e.from); }, 0);
      add(R.poorTime > R.dur * 0.1 ? "bad" : "warn", "זוהו " + R.poor.length + " אירועי קליטה גרועה (RSRP < -110 dBm, 5 שניות ומעלה), בסך " + fmtDur(R.poorTime) + ", הארוך ביותר " + fmtDur(longest) + ".");
    } else add("ok", "לא זוהו אירועי קליטה גרועה ממושכים (RSRP < -110 dBm).");
    if (R.hos.length) {
      var rate = R.hoPerHour != null ? " (≈ " + f1(R.hoPerHour) + " בשעה)" : "";
      var l2 = R.pingPong > 0 ? "bad" : (R.hoPerHour != null && R.hoPerHour > 60 ? "warn" : "ok");
      add(l2, "זוהו " + R.hos.length + " מעברי תא" + rate + (R.dwell ? ", זמן שהייה חציוני בתא " + fmtDur(R.dwell.med) : "") + ". " +
        (R.pingPong > 0 ? "נמצאו " + R.pingPong + " מקרי Ping-Pong (חזרה לתא הקודם בתוך דקה) — מומלץ לבדוק פרמטרי מעבר/כיסוי חופף." : "לא נמצאה תופעת Ping-Pong."));
    } else add("info", "לא זוהו מעברי תא בתקופה.");
    var t = R.techShare;
    if (t.length) {
      add("info", "טכנולוגיה דומיננטית: " + t[0].k + " (" + f1(t[0].pct) + "% מהזמן)" + (t.length > 1 ? "; בנוסף " + t.slice(1, 4).map(function (x) { return x.k + " " + f1(x.pct) + "%"; }).join(", ") : "") + ".");
      var old = t.filter(function (x) { return /2G|3G/.test(x.k); }).reduce(function (s, x) { return s + x.pct; }, 0);
      if (old > 5) add("warn", "המכשיר שהה " + f1(old) + "% מהזמן ב-2G/3G (נפילה מ-4G/5G) — חשד לכיסוי חלש או לנעילת דורות.");
    }
    if (R.bandAvg.length > 1) {
      var bs = R.bandAvg.slice().sort(function (a, b) { return b.mean - a.mean; }), bst = bs[0], wst = bs[bs.length - 1];
      if (bst.mean - wst.mean >= 6) add("info", "פער בין תדרים: " + bst.band + " ממוצע " + f1(bst.mean) + " dBm לעומת " + wst.band + " ממוצע " + f1(wst.mean) + " dBm (פער " + f1(bst.mean - wst.mean) + " dB).");
    }
    if (R.interfPct != null && R.interfPct > 10) add("warn", "ב-" + f1(R.interfPct) + "% מהדגימות עם RSRP טוב (מעל -95) ה-RSRQ נמוך מ-14- dB — סימן להפרעות או עומס בתא.");
    if (R.cells.length) {
      var c0 = R.cells[0];
      add("info", "זוהו " + R.cells.length + " תאים שונים. התא העיקרי: " + c0.label + " (" + f1(c0.pct) + "% מהזמן, RSRP ממוצע " + f1(c0.rsrp ? c0.rsrp.mean : null) + " dBm).");
    }
    if (R.gaps.length) add(R.gapTime > R.dur * 0.1 ? "warn" : "info", "נמצאו " + R.gaps.length + " פערים בהקלטה (מעל " + Math.round(Math.max(10, R.medDt * 5 / 1000)) + " שניות), בסך " + fmtDur(R.gapTime) + " — ייתכן שהאפליקציה הושהתה או שלא היה כיסוי.");
    if (R.worstSpots.length && R.worstSpots[0].mean < -105) {
      var w = R.worstSpots[0];
      add("warn", "האזור החלש ביותר: ‏" + w.lat.toFixed(5) + ", " + w.lon.toFixed(5) + " (RSRP ממוצע " + f1(w.mean) + " dBm, " + w.n + " דגימות).");
    }
    if (R.ta && R.ta.max > 0) add("info", "Timing Advance מרבי " + R.ta.max + " (≈ " + Math.round(R.ta.max * 78.12) + " מ׳ מהתחנה ב-LTE).");
    var bad = C.filter(function (c) { return c.lvl === "bad"; }).length, warn = C.filter(function (c) { return c.lvl === "warn"; }).length;
    C.unshift({ lvl: bad ? "bad" : warn ? "warn" : "ok", txt: bad ? "סיכום: נמצאו " + bad + " ממצאים משמעותיים הדורשים תשומת לב ו-" + warn + " אזהרות." :
      warn ? "סיכום: איכות הרשת סבירה, עם " + warn + " נקודות לשיפור." : "סיכום: איכות הרשת טובה לאורך התקופה." });
    return C;
  }

  /* -------------------------------- charts -------------------------------- */
  var DARK = { bg: "#020617", fg: "#cbd5e1", grid: "#1e293b", axis: "#64748b" };
  var LIGHT = { bg: "#ffffff", fg: "#111827", grid: "#e5e7eb", axis: "#6b7280" };
  function setup(cv, th, forceW, forceH) {
    var dpr = forceW ? 2 : (window.devicePixelRatio || 1);
    var w = forceW || cv.clientWidth || 320, h = forceH || cv.clientHeight || 180;
    cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr);
    var c = cv.getContext("2d"); c.setTransform(dpr, 0, 0, dpr, 0, 0);
    c.fillStyle = th.bg; c.fillRect(0, 0, w, h);
    c.font = "10px sans-serif"; c.textBaseline = "middle";
    return { c: c, w: w, h: h };
  }
  function downsample(rows, key, maxPts, t0, t1) {
    var step = (t1 - t0) / maxPts || 1, out = [], b = -1, acc = 0, cnt = 0, bt = 0;
    rows.forEach(function (r) {
      var v = r[key]; if (v == null) return;
      var bi = Math.floor((r.t - t0) / step);
      if (bi !== b) { if (cnt) out.push({ t: bt / cnt, v: acc / cnt }); b = bi; acc = 0; cnt = 0; bt = 0; }
      acc += v; cnt++; bt += r.t;
    });
    if (cnt) out.push({ t: bt / cnt, v: acc / cnt });
    return out;
  }
  function drawSeries(cv, th, W, H) {
    var s = setup(cv, th, W, H), c = s.c, R = RES, L = 38, Rr = 34, T = 8, B = 16, pw = s.w - L - Rr, ph = s.h - T - B;
    var x = function (t) { return L + (t - R.t0) / Math.max(1, R.dur) * pw; };
    var yR = function (v) { return T + (1 - (v + 140) / 100) * ph; };       // -140..-40
    var yS = function (v) { return T + (1 - (v + 10) / 40) * ph; };         // -10..30
    c.strokeStyle = th.grid; c.fillStyle = th.axis; c.lineWidth = 1; c.textAlign = "right";
    [-140, -120, -100, -80, -60, -40].forEach(function (v) { c.beginPath(); c.moveTo(L, yR(v)); c.lineTo(L + pw, yR(v)); c.stroke(); c.fillText(v, L - 4, yR(v)); });
    c.textAlign = "left"; [-10, 0, 10, 20, 30].forEach(function (v) { c.fillText(v, L + pw + 4, yS(v)); });
    c.textAlign = "center"; for (var i = 0; i <= 4; i++) { var tt = R.t0 + R.dur * i / 4, d = new Date(tt); c.fillText(pad(d.getHours()) + ":" + pad(d.getMinutes()), x(tt), s.h - 6); }
    // thresholds
    c.setLineDash([3, 3]); c.strokeStyle = "#ef444488"; c.beginPath(); c.moveTo(L, yR(-110)); c.lineTo(L + pw, yR(-110)); c.stroke(); c.setLineDash([]);
    // HO markers
    c.strokeStyle = "#a855f730"; R.hos.slice(0, 400).forEach(function (h) { c.beginPath(); c.moveTo(x(h.t), T); c.lineTo(x(h.t), T + ph); c.stroke(); });
    [["rsrp", yR, "#22c55e"], ["rssi", yR, "#f59e0b"], ["sinr", yS, "#6366f1"]].forEach(function (d) {
      var pts = downsample(DATA, d[0], 900, R.t0, R.t1); c.strokeStyle = d[2]; c.lineWidth = 1.4; c.beginPath();
      var prev = null;
      pts.forEach(function (p) { var px = x(p.t), py = d[1](p.v); if (prev == null || p.t - prev > Math.max(R.medDt * 30, (R.dur / 900) * 3)) c.moveTo(px, py); else c.lineTo(px, py); prev = p.t; });
      c.stroke();
    });
  }
  function drawHist(cv, th, W, H) {
    var s = setup(cv, th, W, H), c = s.c, L = 28, T = 8, B = 18, pw = s.w - L - 6, ph = s.h - T - B;
    var bins = {}, mx = 0, tot = 0;
    DATA.forEach(function (r) { if (r.rsrp == null) return; var b = Math.floor(r.rsrp / 2) * 2; bins[b] = (bins[b] || 0) + 1; tot++; });
    Object.keys(bins).forEach(function (k) { mx = Math.max(mx, bins[k]); });
    var lo = -140, hi = -40, bw = pw / ((hi - lo) / 2);
    for (var v = lo; v < hi; v += 2) {
      var cnt = bins[v] || 0, bh = mx ? cnt / mx * ph : 0, col = "#22c55e";
      for (var i = 0; i < RSRP_BUCKETS.length; i++) if (RSRP_BUCKETS[i].test(v + 1)) { col = RSRP_BUCKETS[i].color; break; }
      c.fillStyle = col; c.fillRect(L + (v - lo) / 2 * bw, T + ph - bh, Math.max(1, bw - 1), bh);
    }
    c.fillStyle = th.axis; c.textAlign = "center"; [-140, -120, -100, -80, -60, -40].forEach(function (v) { c.fillText(v, L + (v - lo) / 2 * bw, s.h - 7); });
    c.textAlign = "right"; c.fillText(tot ? Math.round(mx * 100 / tot) + "%" : "", L - 3, T + 4);
  }
  function drawBars(cv, th, items, W, H) {
    var s = setup(cv, th, W, H), c = s.c, rowH = Math.min(22, (s.h - 6) / Math.max(1, items.length)), lw = Math.min(140, s.w * 0.4);
    items.forEach(function (it, i) {
      var y = 4 + i * rowH + rowH / 2, bw = (s.w - lw - 46) * Math.min(100, it.pct) / 100;
      c.fillStyle = th.fg; c.textAlign = "right"; c.fillText(it.k.length > 24 ? it.k.slice(0, 23) + "…" : it.k, s.w - 4, y);
      c.fillStyle = it.color || "#38bdf8"; c.fillRect(s.w - lw - 4 - bw, y - rowH * 0.3, bw, rowH * 0.6);
      c.fillStyle = th.fg; c.textAlign = "left"; c.fillText(f1(it.pct) + "%", 4, y);
    });
  }
  function rsrpColor(v) {
    for (var i = 0; i < RSRP_BUCKETS.length; i++) if (RSRP_BUCKETS[i].test(v)) return RSRP_BUCKETS[i].color;
    return "#94a3b8";
  }
  function drawTrack(cv, th, W, H) {
    var s = setup(cv, th, W, H), c = s.c, pts = DATA.filter(function (r) { return r.lat != null && r.lon != null; });
    if (pts.length < 2) { c.fillStyle = th.axis; c.textAlign = "center"; c.fillText("אין נתוני מיקום בקובץ", s.w / 2, s.h / 2); return; }
    var la0 = 90, la1 = -90, lo0 = 180, lo1 = -180;
    pts.forEach(function (r) { la0 = Math.min(la0, r.lat); la1 = Math.max(la1, r.lat); lo0 = Math.min(lo0, r.lon); lo1 = Math.max(lo1, r.lon); });
    var k = Math.cos((la0 + la1) / 2 * Math.PI / 180), dx = Math.max(1e-5, (lo1 - lo0) * k), dy = Math.max(1e-5, la1 - la0), m = 16;
    var sc = Math.min((s.w - 2 * m) / dx, (s.h - 2 * m) / dy), ox = (s.w - dx * sc) / 2, oy = (s.h - dy * sc) / 2;
    var X = function (lon) { return ox + (lon - lo0) * k * sc; }, Y = function (lat) { return s.h - oy - (lat - la0) * sc; };
    var step = Math.max(1, Math.floor(pts.length / 6000));
    for (var i = 0; i < pts.length; i += step) { var r = pts[i]; if (r.rsrp == null) continue; c.fillStyle = rsrpColor(r.rsrp); c.beginPath(); c.arc(X(r.lon), Y(r.lat), 2.2, 0, 7); c.fill(); }
    c.fillStyle = th.fg; c.textAlign = "left"; c.fillText("▶ התחלה", X(pts[0].lon) + 5, Y(pts[0].lat) - 6);
    c.textAlign = "right"; c.fillText("סוף ■", X(pts[pts.length - 1].lon) - 5, Y(pts[pts.length - 1].lat) + 8);
    RES.hos.slice(0, 200).forEach(function (h) {
      var near = null, best = 1e18; for (var j = 0; j < pts.length; j += Math.max(1, step)) { var d = Math.abs(pts[j].t - h.t); if (d < best) { best = d; near = pts[j]; } }
      if (near) { c.strokeStyle = "#a855f7"; c.lineWidth = 1.5; c.beginPath(); c.arc(X(near.lon), Y(near.lat), 5, 0, 7); c.stroke(); }
    });
  }

  var CHARTS = [
    { id: "an-ch-series", draw: function (cv, th, w, h) { drawSeries(cv, th, w, h); }, pw: 700, ph: 240 },
    { id: "an-ch-hist", draw: function (cv, th, w, h) { drawHist(cv, th, w, h); }, pw: 340, ph: 200 },
    { id: "an-ch-quality", draw: function (cv, th, w, h) { drawBars(cv, th, RES.rsrpB.items.map(function (o) { return { k: o.k, pct: o.pct, color: o.color }; }), w, h); }, pw: 340, ph: 160 },
    { id: "an-ch-tech", draw: function (cv, th, w, h) { drawBars(cv, th, RES.techShare.slice(0, 7).map(function (o) { return { k: o.k, pct: o.pct, color: "#38bdf8" }; }), w, h); }, pw: 340, ph: 160 },
    { id: "an-ch-band", draw: function (cv, th, w, h) { drawBars(cv, th, RES.bandShare.slice(0, 8).map(function (o) { return { k: o.k, pct: o.pct, color: "#a78bfa" }; }), w, h); }, pw: 340, ph: 180 },
    { id: "an-ch-track", draw: function (cv, th, w, h) { drawTrack(cv, th, w, h); }, pw: 700, ph: 300 }
  ];
  function drawAllScreen() {
    if (!RES) return;
    CHARTS.forEach(function (ch) { var cv = $(ch.id); if (cv) ch.draw(cv, DARK); });
  }
  function drawAllPrint() {
    if (!RES) return;
    CHARTS.forEach(function (ch) {
      var tmp = document.createElement("canvas");
      ch.draw(tmp, LIGHT, ch.pw, ch.ph);
      var img = $(ch.id + "-print"); if (img) img.src = tmp.toDataURL("image/png");
    });
  }

  /* -------------------------------- rendering ------------------------------- */
  function table(heads, rows, cls) {
    return '<div class="overflow-x-auto"><table class="an-table w-full text-[11px] ' + (cls || "") + '"><thead><tr>' +
      heads.map(function (h) { return "<th>" + esc(h) + "</th>"; }).join("") + "</tr></thead><tbody>" +
      rows.map(function (r) { return "<tr>" + r.map(function (c) { return "<td>" + (c == null ? "" : c) + "</td>"; }).join("") + "</tr>"; }).join("") +
      "</tbody></table></div>";
  }
  function render() {
    var R = RES, root = $("an-result");
    var iconOf = { ok: "✅", warn: "⚠️", bad: "⛔", info: "ℹ️" };
    var m = R.m;
    var kpi = function (label, val, sub) { return '<div class="an-kpi"><div class="an-kpi-l">' + label + '</div><div class="an-kpi-v" dir="ltr" style="text-align:right">' + val + '</div><div class="an-kpi-s">' + (sub || "") + "</div></div>"; };
    var html = "";
    html += '<div class="an-print-head print-only"><h1>דוח ניתוח רשת סלולרית — RT-MON</h1><div>מקור הנתונים: ' + esc(SOURCE) + " · הופק: " + fmtT(Date.now()) + "</div></div>";
    html += '<div class="an-grid">' +
      kpi("דגימות", R.n.toLocaleString(), "תקופה: " + fmtDur(R.dur)) +
      kpi("מתאריך", fmtT(R.t0).slice(0, 16), "עד " + fmtT(R.t1).slice(0, 16)) +
      kpi("RSRP חציוני", f1(m.rsrp && m.rsrp.median) + " dBm", "ממוצע " + f1(m.rsrp && m.rsrp.mean)) +
      kpi("SINR חציוני", f1(m.sinr && m.sinr.median) + " dB", "ממוצע " + f1(m.sinr && m.sinr.mean)) +
      kpi("מעברי תא", R.hos.length, R.hoPerHour != null ? f1(R.hoPerHour) + " בשעה · PP " + R.pingPong : "") +
      kpi("תאים", R.cells.length, "פערי הקלטה: " + R.gaps.length) + "</div>";
    html += '<div class="an-card"><h3>📋 מסקנות</h3><ul class="an-concl">' +
      R.conclusions.map(function (c) { return '<li class="lvl-' + c.lvl + '">' + iconOf[c.lvl] + " " + esc(c.txt) + "</li>"; }).join("") + "</ul></div>";

    var chart = function (id, title, h) {
      return '<div class="an-card an-chart"><h3>' + title + '</h3><canvas id="' + id + '" class="chart w-full" style="height:' + h + 'px;display:block"></canvas><img id="' + id + '-print" class="print-only" alt=""></div>';
    };
    html += chart("an-ch-series", "עוצמת קליטה לאורך זמן (ירוק RSRP · כתום RSSI · סגול-כחול SINR · קווים סגולים = מעבר תא)", 240);
    html += '<div class="an-two">' + chart("an-ch-hist", "היסטוגרמת RSRP", 200) + chart("an-ch-quality", "התפלגות איכות RSRP", 160) + "</div>";
    html += '<div class="an-two">' + chart("an-ch-tech", "זמן לפי טכנולוגיה", 160) + chart("an-ch-band", "זמן לפי תדר (Band)", 180) + "</div>";
    html += chart("an-ch-track", "מסלול ועוצמת קליטה (ירוק חזק → אדום חלש · עיגול סגול = מעבר תא)", 300);

    var stRow = function (name, unit, s) { return s ? [name, s.n.toLocaleString(), f1(s.min), f1(s.p5), f1(s.median), f1(s.mean), f1(s.p95), f1(s.max), f1(s.std)] : [name, 0, "", "", "", "", "", "", ""]; };
    html += '<div class="an-card"><h3>📈 סטטיסטיקה לפי מדד</h3>' + table(["מדד", "מדידות", "מינ׳", "P5", "חציון", "ממוצע", "P95", "מקס׳", "סטיית תקן"],
      [stRow("RSRP (dBm)", "", m.rsrp), stRow("RSRQ (dB)", "", m.rsrq), stRow("SINR (dB)", "", m.sinr), stRow("RSSI (dBm)", "", m.rssi), stRow("Tx הטלפון (dBm)", "", m.tx)]) + "</div>";
    html += '<div class="an-card"><h3>📡 תאים</h3>' + table(["תא", "Band", "דגימות", "זמן", "% זמן", "RSRP ממוצע", "RSRP מינ׳", "SINR ממוצע", "נראה לראשונה"],
      R.cells.slice(0, 20).map(function (c) { return [esc(c.label), esc(c.band), c.n.toLocaleString(), fmtDur(c.ms), f1(c.pct) + "%", f1(c.rsrp && c.rsrp.mean), f1(c.rsrp && c.rsrp.min), f1(c.sinr && c.sinr.mean), fmtT(c.first)]; })) +
      (R.cells.length > 20 ? '<div class="an-note">מוצגים 20 מתוך ' + R.cells.length + "</div>" : "") + "</div>";
    html += '<div class="an-card"><h3>📶 תדרים וטכנולוגיות</h3>' + table(["תדר / טכנולוגיה", "זמן", "% זמן", "RSRP ממוצע"],
      R.bandShare.map(function (b) { var av = R.bandAvg.filter(function (x) { return b.k.indexOf(x.band) >= 0; })[0]; return [esc(b.k), fmtDur(b.ms), f1(b.pct) + "%", av ? f1(av.mean) : ""]; })) + "</div>";
    html += '<div class="an-card"><h3>🔁 מעברי תא (Handover)</h3>' + (R.hos.length ? table(["זמן", "מתא", "לתא", "RSRP לפני", "RSRP אחרי", "שהייה בתא הקודם", "Ping-Pong"],
      R.hos.slice(0, 60).map(function (h) { return [fmtT(h.t), esc(h.from), esc(h.to), f1(h.rsrpBefore), f1(h.rsrpAfter), fmtDur(h.dwell), h.pp ? "⚠️" : ""]; })) +
      (R.hos.length > 60 ? '<div class="an-note">מוצגים 60 מתוך ' + R.hos.length + "</div>" : "") : '<div class="an-note">לא זוהו מעברי תא.</div>') + "</div>";
    html += '<div class="an-card"><h3>🕳️ אירועי קליטה גרועה (RSRP &lt; -110 dBm)</h3>' + (R.poor.length ? table(["התחלה", "משך", "הכי חלש (dBm)", "מיקום"],
      R.poor.slice().sort(function (a, b) { return (b.to - b.from) - (a.to - a.from); }).slice(0, 15).map(function (e) { return [fmtT(e.from), fmtDur(e.to - e.from), f1(e.worst), e.lat != null ? e.lat.toFixed(5) + ", " + e.lon.toFixed(5) : ""]; })) : '<div class="an-note">לא זוהו אירועים.</div>') + "</div>";
    if (R.spots.length) {
      html += '<div class="an-card"><h3>📍 אזורים (רשת ~100 מ׳)</h3><div class="an-two"><div><b>החלשים ביותר</b>' + table(["מיקום", "RSRP ממוצע", "דגימות"],
        R.worstSpots.map(function (g) { return [g.lat.toFixed(5) + ", " + g.lon.toFixed(5), f1(g.mean), g.n]; })) + "</div><div><b>החזקים ביותר</b>" + table(["מיקום", "RSRP ממוצע", "דגימות"],
        R.bestSpots.map(function (g) { return [g.lat.toFixed(5) + ", " + g.lon.toFixed(5), f1(g.mean), g.n]; })) + "</div></div></div>";
    }
    html += '<div class="an-note">הניתוח מבוצע במלואו במכשיר, ללא אינטרנט. המסקנות מבוססות כללים אוטומטיים ואינן תחליף לבדיקה הנדסית.</div>';
    root.innerHTML = html;
    root.classList.remove("hidden");
    setTimeout(drawAllScreen, 30);
  }

  /* --------------------------------- loading -------------------------------- */
  function status(msg, bad) { var s = $("an-status"); if (s) { s.textContent = msg; s.className = "text-[11px] " + (bad ? "text-rose-400" : "text-slate-400"); } }
  function runOn(rows, source) {
    try {
      DATA = rows; SOURCE = source; RES = analyze(rows);
      status(source + " · " + rows.length.toLocaleString() + " דגימות · " + fmtDur(RES.dur));
      $("an-btn-pdf").disabled = false; $("an-btn-pdf").classList.remove("opacity-40");
      render();
    } catch (e) { status("שגיאת ניתוח: " + e.message, true); }
  }
  function loadFile(file) {
    if (!file) return;
    status("קורא את " + file.name + "...");
    var rd = new FileReader();
    rd.onerror = function () { status("קריאת הקובץ נכשלה", true); };
    rd.onload = function () {
      setTimeout(function () {
        try { runOn(normalize(parseCsv(String(rd.result))), file.name); }
        catch (e) { status(e.message, true); }
      }, 30);
    };
    rd.readAsText(file);
  }
  function loadFromDevice() {
    var store = window.RfStore;
    if (!store || store.count() === 0) { status("אין דגימות שמורות במכשיר", true); return; }
    var total = store.count(), rows = [], after = 0;
    status("טוען " + total.toLocaleString() + " דגימות מהמכשיר...");
    (function step() {
      var ch = store.chunkAsc(after, 4000);
      if (!ch.length) { runOn(rows, "נתוני המכשיר (כל הדגימות)"); return; }
      ch.forEach(function (r) {
        var o = store.obj(r);
        rows.push({ t: o.ts, lat: o.lat, lon: o.lon, tech: o.tech || o.rat || "", band: o.band || "", plmn: o.plmn || "", pci: o.pci, gnb: o.gnb,
          arfcn: o.arfcn, rsrp: o.rsrp, rsrq: o.rsrq, sinr: o.sinr, rssi: o.rssi, ta: o.ta, ho: !!o.ho, tx: o.tx });
      });
      after = ch[ch.length - 1][0];
      status("נטענו " + rows.length.toLocaleString() + " / " + total.toLocaleString());
      setTimeout(step, 0);
    })();
  }
  function printReport() {
    if (!RES) return;
    drawAllPrint();
    document.body.classList.add("rt-print-analyze");
    var title = "RT-MON-report-" + new Date().toISOString().slice(0, 10);
    setTimeout(function () {
      if (window.AndroidModem && window.AndroidModem.printPage) window.AndroidModem.printPage(title);
      else window.print();
    }, 250);
  }
  window.addEventListener("beforeprint", function () { if (RES && document.body.classList.contains("rt-print-analyze")) drawAllPrint(); });

  function init() {
    var inp = $("an-file");
    if (inp) inp.addEventListener("change", function (e) { loadFile(e.target.files[0]); e.target.value = ""; });
    window.addEventListener("resize", function () { if (RES && !$("view-analyze").classList.contains("hidden")) drawAllScreen(); });
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init); else init();

  window.rtAnalyze = {
    pickFile: function () { var i = $("an-file"); if (i) i.click(); },
    loadFromDevice: loadFromDevice,
    printReport: printReport,
    onShow: function () { document.body.classList.add("rt-print-analyze"); if (RES) setTimeout(drawAllScreen, 30); },
    onHide: function () { document.body.classList.remove("rt-print-analyze"); },
    // exposed for tests
    _parse: function (text) { return normalize(parseCsv(text)); }, _analyze: analyze, _run: runOn
  };
})();
