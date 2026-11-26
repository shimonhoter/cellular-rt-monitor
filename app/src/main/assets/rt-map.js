/* Real map (Leaflet + OpenStreetMap tiles) with Israeli cellular antenna sites (data.gov.il, Ministry of
   Environmental Protection), the user's location, and modem observations linked to physical sites. */
(function () {
  "use strict";
  var map = null, userMarker = null, accCircle = null, antLayer = null, built = false;
  var loc = null, autoCenter = false;
  var markers = {};                 // dataset row index -> circleMarker (kept across refreshes so open popups survive)
  var openIdx = null;               // index of the station whose popup is currently open
  var assoc = null, assocTs = 0;    // result of RtAssoc.associate()
  var COLORS = ["#22c55e", "#f59e0b", "#a855f7", "#38bdf8", "#f43f5e"];
  var CONF_COLOR = { high: "#16a34a", mid: "#f59e0b", low: "#ef4444" };
  var CONF_LABEL = { high: "גבוהה", mid: "בינונית", low: "נמוכה" };
  var $ = function (id) { return document.getElementById(id); };
  var esc = function (v) {
    return String(v == null ? "" : v).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  };
  var f1 = function (v) { return typeof v === "number" && isFinite(v) ? (Math.round(v * 10) / 10).toFixed(1) : ""; };

  function data() { return window.RT_ANTENNAS || null; }
  function f(row, name) { return row[data().fields.indexOf(name)]; }

  /* ----------------------------- controls ----------------------------- */
  function setAuto(on, quiet) {
    autoCenter = !!on;
    var b = $("rt-map-auto");
    if (b) {
      b.textContent = "מירכוז אוטומטי: " + (autoCenter ? "דולק" : "כבוי");
      b.className = "px-2 py-1 rounded font-bold border " + (autoCenter
        ? "bg-emerald-600 text-white border-emerald-400" : "bg-slate-800 text-slate-300 border-slate-700");
    }
    if (autoCenter && loc) centerOnUser(false);
    if (!quiet && typeof showToast === "function") showToast("מפה", "מירכוז אוטומטי " + (autoCenter ? "דולק" : "כבוי"));
  }
  function centerOnUser(zoomIn) {
    if (!map || !loc) return;
    // never pan while a station popup is open, so the details stay readable
    if (openIdx != null && !zoomIn) return;
    var z = zoomIn ? Math.max(map.getZoom(), 15) : map.getZoom();
    map.setView([loc.lat, loc.lon], z, { animate: true });
  }

  /* ------------------------ modem data per station ------------------------ */
  function currentGroupKey() {
    var st = window.state; if (!st) return null;
    var sc = st.servingCell || {}, m = /(\d{3})-?(\d{2,3})/.exec(String(sc.plmn || ""));
    var valid = function (v) { return v !== undefined && v !== null && v !== "---" && v !== ""; };
    if (!m || !valid(sc.tech)) return null;
    var site = valid(sc.gnb) ? "e" + sc.gnb : (valid(sc.pci) ? "p" + sc.pci + "@" + sc.arfcn : null);
    return site ? sc.tech + "|" + m[1] + "-" + m[2] + "|" + site : null;
  }
  function chip(conf) {
    return '<span style="display:inline-block;padding:0 6px;border-radius:9px;color:#fff;font-size:11px;background:' + CONF_COLOR[conf.level] +
      '">רמת סמך ' + CONF_LABEL[conf.level] + " " + conf.pct + "%</span>";
  }
  function groupHtml(g, live) {
    var c = g.best.conf, ev = g.best.ev, pcis = Object.keys(g.pcis), bands = Object.keys(g.bands);
    var avg = function (s, n) { return n ? s / n : null; };
    var line = function (label, val, unit) { return val == null ? "" : "<div>" + label + ": <b><bdi dir=\"ltr\">" + f1(val) + "</bdi></b> " + unit + "</div>"; };
    var h = '<div style="border:1px solid #cbd5e1;border-radius:6px;padding:4px 6px;margin:4px 0">';
    h += "<div><b>" + esc(g.tech) + "</b> " + esc(bands.join("/")) + (g.gnb != null ? " · eNB " + esc(g.gnb) : "") +
      (pcis.length ? " · PCI " + esc(pcis.join(",")) : "") + "</div>";
    h += '<div style="margin:2px 0">נתוני קליטה מהמודם · ' + chip(c) + "</div>";
    if (live) h += '<div style="color:#16a34a">● מחובר כעת' + (typeof live.rsrp === "number" ? " · RSRP " + f1(live.rsrp) + " dBm" : "") + "</div>";
    h += line("RSRP ממוצע", g.sRsrp / g.N, "dBm") + '<div style="color:#475569">טווח <bdi dir="ltr">' + f1(g.mn) + "</bdi> עד <bdi dir=\"ltr\">" + f1(g.mx) + "</bdi> dBm</div>";
    h += line("RSRQ ממוצע", avg(g.sRsrq, g.nRsrq), "dB") + line("SINR ממוצע", avg(g.sSinr, g.nSinr), "dB") + line("RSSI ממוצע", avg(g.sRssi, g.nRssi), "dBm");
    h += '<div style="color:#475569">' + g.N + " דגימות ב-" + g.pts.length + " מיקומים · נראה לאחרונה " + new Date(g.last).toLocaleDateString("he-IL") + "</div>";
    var why = [];
    why.push("כיסוי זוויתי " + Math.round(ev.cover * 100) + "%");
    why.push(ev.spreadOk ? "מתאם RSRP↔מרחק " + (Math.round(ev.corr * 100) / 100) : "טווח מרחקים צר");
    if (ev.T != null) why.push("התאמת TA " + Math.round(ev.T * 100) + "%");
    why.push("הקריאה החזקה ב-" + Math.round(ev.dStrong) + " מ׳ מהתחנה");
    if (g.alts && g.alts.length) why.push(g.alts.length + " חלופות סבירות");
    h += '<div style="color:#64748b;font-size:11px">' + esc(why.join(" · ")) + "</div>";
    if (c.caps.length) h += '<div style="color:#b45309;font-size:11px">⚠ ' + esc(c.caps.join("; ")) + "</div>";
    return h + "</div>";
  }
  function popupHtml(row, idx) {
    var d = data();
    var rows = [
      ["חברה", d.companies[f(row, "company")]], ["מס' אתר", f(row, "siteNo")],
      ["כתובת", [f(row, "city"), f(row, "address")].filter(Boolean).join(", ")], ["סוג אתר", d.types[f(row, "type")]],
      ["טכנולוגיות (דור)", f(row, "gens")], ["היתר הקמה", f(row, "buildPermit")], ["היתר הפעלה", f(row, "opPermit")],
      ["בדיקה תקופתית אחרונה", f(row, "lastTest")], ["היתר קרינה", f(row, "radPermit")],
      ["עוצמה מרבית תיאורטית", f(row, "powerUWcm2") == null ? "" : f(row, "powerUWcm2") + " µW/סמ״ר"],
      ["תוצאה מרבית מסף הבריאות", f(row, "pctOfLimit") == null ? "" : f(row, "pctOfLimit") + "%"],
      ["נקודת מדידה", f(row, "maxPoint")],
      ["מרחק ממני", loc ? Math.round(map.distance([loc.lat, loc.lon], [f(row, "lat"), f(row, "lon")])) + " מ׳" : ""]
    ];
    var html = '<div dir="rtl" style="font:12px/1.5 sans-serif;min-width:230px;max-width:290px;max-height:60vh;overflow:auto">';
    var groups = assoc && assoc.bySite[idx];
    if (groups && groups.length) {
      var ck = currentGroupKey(), rf = (window.state && window.state.rf) || {};
      html += '<div style="background:#ecfdf5;border:1px solid #6ee7b7;border-radius:6px;padding:2px 6px;margin-bottom:4px"><b>📶 נתוני קליטה מהמודם (מצטבר)</b>';
      groups.forEach(function (g) { html += groupHtml(g, g.key === ck ? rf : null); });
      html += '<div style="font-size:11px"><a href="#" data-explain="1" style="color:#0369a1">איך מחושב השיוך?</a></div></div>';
    }
    rows.forEach(function (r) { if (r[1] === "" || r[1] == null) return; html += "<div><b>" + esc(r[0]) + ":</b> " + esc(r[1]) + "</div>"; });
    ["buildDoc:מסמך הקמה", "opDoc:מסמך הפעלה"].forEach(function (x) {
      var p = x.split(":"), v = f(row, p[0]);
      if (v) html += '<div><a href="#" data-doc="http://documents.sviva.gov.il/' + esc(v) + '" style="color:#0369a1">' + p[1] + " (PDF)</a></div>";
    });
    return html + '<div style="color:#64748b;margin-top:4px">מקור: המשרד להגנת הסביבה (data.gov.il)</div></div>';
  }

  /* ------------------------------ markers ------------------------------ */
  function styleFor(idx) {
    var row = data().rows[idx], gs = assoc && assoc.bySite[idx];
    var base = { radius: map.getZoom() >= 14 ? 7 : 5, color: "#0f172a", weight: 1, fillColor: COLORS[row[2] % COLORS.length], fillOpacity: 0.9 };
    if (gs && gs.length) {
      var best = gs[0].best.conf;
      base.color = CONF_COLOR[best.level]; base.weight = 3; base.radius += 3;
    }
    return base;
  }
  function restyleAll() {
    if (!map || !data()) return;
    Object.keys(markers).forEach(function (k) { markers[k].setStyle(styleFor(+k)); markers[k].setRadius(styleFor(+k).radius); });
  }
  function refreshAntennas() {
    var d = data();
    if (!map || !d || !antLayer) return;
    var show = $("rt-map-show-ant"), info = $("rt-map-info");
    var want = {}, total = 0, CAP = 3500;
    if ((show && !show.checked) || map.getZoom() < 10) {
      if (info) info.textContent = map.getZoom() < 10 && (!show || show.checked) ? "התקרב כדי לראות תחנות" : "";
    } else {
      var comp = $("rt-map-company").value, ci = comp === "" ? -1 : Number(comp);
      var onlyAssoc = $("rt-map-only-assoc") && $("rt-map-only-assoc").checked;
      var b = map.getBounds().pad(0.1), n = 0;
      for (var i = 0; i < d.rows.length; i++) {
        var r = d.rows[i];
        if (ci >= 0 && r[2] !== ci) continue;
        if (!b.contains([r[0], r[1]])) continue;
        if (onlyAssoc && !(assoc && assoc.bySite[i])) continue;
        total++;
        if (n >= CAP && i !== openIdx) continue;
        n++; want[i] = true;
      }
      if (info) info.textContent = "תחנות בתצוגה: " + total + (total > CAP ? " (מוצגות " + CAP + ", התקרב)" : "") +
        (assoc ? " · עם נתוני קליטה: " + Object.keys(assoc.bySite).length : "");
    }
    if (openIdx != null && markers[openIdx]) want[openIdx] = true;   // an open popup is never removed
    Object.keys(markers).forEach(function (k) { if (!want[k]) { antLayer.removeLayer(markers[k]); delete markers[k]; } });
    Object.keys(want).forEach(function (k) {
      if (markers[k]) return;
      var idx = +k, st = styleFor(idx), m = L.circleMarker([d.rows[idx][0], d.rows[idx][1]], st);
      m.bindPopup(function () { return popupHtml(d.rows[idx], idx); }, { maxWidth: 300, autoPanPadding: [10, 60], closeOnClick: false });
      m.on("popupopen", function () { openIdx = idx; });
      m.on("popupclose", function () { if (openIdx === idx) openIdx = null; });
      antLayer.addLayer(m); markers[idx] = m;
    });
  }

  /* --------------------- accumulated observations -> stations --------------------- */
  function obsRows() {
    try {
      if (window.AndroidModem && window.AndroidModem.getCellObs) return JSON.parse(window.AndroidModem.getCellObs()) || [];
      if (window.CellObs) return window.CellObs.rows();
    } catch (e) {}
    return [];
  }
  function recompute() {
    var d = data();
    if (!d || !window.RtAssoc) return;
    try {
      assoc = window.RtAssoc.associate(obsRows(), d); assocTs = Date.now();
      if (map) {
        restyleAll(); refreshAntennas();
        if (openIdx != null && markers[openIdx] && markers[openIdx].isPopupOpen()) markers[openIdx].getPopup().setContent(popupHtml(d.rows[openIdx], openIdx));
        updateLink(true);
      }
    } catch (e) { if (window.console) console.error("assoc", e); }
  }

  var lastLink = 0, linkLine = null, linkRing = null, lastLinkIdx = null;
  function updateLink(force) {
    var now = Date.now();
    if (!force && now - lastLink < 1500) return;
    lastLink = now;
    var el = $("rt-map-link"), d = data();
    if (!el || !d) return;
    var key = currentGroupKey(), g = null;
    if (assoc && key) g = assoc.groups.filter(function (x) { return x.key === key; })[0];
    if (!g || !g.best) {
      el.textContent = key ? "🔗 אין עדיין מדידות מצטברות מספיקות לתא הנוכחי — נדרשת הליכה/נסיעה סביב התחנה" : "🔗 ממתין לנתוני תא ולמיקום…";
      if (linkLine) { map.removeLayer(linkLine); map.removeLayer(linkRing); linkLine = linkRing = null; lastLinkIdx = null; }
      return;
    }
    var row = d.rows[g.best.idx], c = g.best.conf;
    el.innerHTML = '🔗 תחנה משוערת לתא הנוכחי: <b>' + esc(d.companies[row[2]]) + "</b> · " + esc([row[4], row[5]].filter(Boolean).join(", ")) +
      (loc ? " · " + Math.round(window.RtAssoc.dist(loc.lat, loc.lon, row[0], row[1])) + " מ׳ ממני" : "") + " · " + chip(c) +
      " · " + g.N + " דגימות ב-" + g.pts.length + " מיקומים";
    if (!map) return;
    var ll = [row[0], row[1]];
    if (!linkRing) {
      linkRing = L.circleMarker(ll, { radius: 14, color: "#f97316", weight: 3, fill: false, interactive: false }).addTo(map);
      linkLine = L.polyline([], { color: "#f97316", weight: 2, dashArray: "6 6", interactive: false }).addTo(map);
    }
    linkRing.setLatLng(ll);
    linkLine.setLatLngs(loc ? [[loc.lat, loc.lon], ll] : []);
    lastLinkIdx = g.best.idx;
  }
  window.rtMapLinkUpdate = function () { updateLink(false); };

  /* --------------------------------- build --------------------------------- */
  var EXPLAIN = "השיוך של נתוני המודם לתחנה פיזית הוא הערכה סטטיסטית, לא התאמת מזהים: מאגר gov.il אינו כולל Cell-ID או PCI, ו-Android אינו מדווח מיקום תחנה.\n\n" +
    "איך זה מחושב:\n1. כל מדידה (עם מיקום GPS) נשמרת לצמיתות במאגר מצטבר לפי תא (טכנולוגיה, מפעיל, eNB) ומקום (~22 מ׳). המאגר לא נמחק בכפתור 'נקה'.\n" +
    "2. לכל תא נבחרות תחנות מועמדות של אותו מפעיל ואותו דור באזור המדידות.\n" +
    "3. כל מועמדת נבדקת: האם עוצמת הקליטה יורדת עם המרחק ממנה (רגרסיה של RSRP מול log המרחק), האם הקריאות החזקות ביותר קרובות אליה, ובתאי LTE האם Timing Advance תואם למרחק.\n" +
    "4. נבחרת המועמדת עם ההתאמה הטובה ביותר. רמת הסמך מחושבת מכמות המיקומים, מהכיסוי הזוויתי סביב התחנה (מדידות מכל הכיוונים), איכות ההתאמה, ופער מהמועמדת הבאה.\n\n" +
    "ככל שמצטברות מדידות ממקומות שונים סביב התחנה, רמת הסמך מתעדכנת. מדידה ממקום אחד בלבד תישאר ברמת סמך נמוכה.";

  function build() {
    if (built) return;
    var host = $("rt-map");
    if (!host) return;
    if (typeof L === "undefined") { host.innerHTML = '<div style="padding:12px;color:#94a3b8">ספריית המפה לא נטענה</div>'; return; }
    built = true;
    map = L.map(host, { zoomControl: true, preferCanvas: true, minZoom: 6, maxZoom: 19 }).setView([31.8, 35.0], 8);
    L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19, attribution: "© OpenStreetMap" }).addTo(map);
    antLayer = L.layerGroup().addTo(map);

    var d = data();
    if (d) {
      var sel = $("rt-map-company"), leg = $("rt-map-legend");
      d.companies.forEach(function (c, i) {
        var o = document.createElement("option"); o.value = i; o.textContent = c; sel.appendChild(o);
        leg.insertAdjacentHTML("beforeend", '<span><span style="display:inline-block;width:9px;height:9px;border-radius:50%;background:' +
          COLORS[i % COLORS.length] + ';margin-left:3px"></span>' + esc(c) + "</span>");
      });
      leg.insertAdjacentHTML("beforeend", '<span>טבעת: <span style="color:#16a34a">●</span> סמך גבוה <span style="color:#f59e0b">●</span> בינוני <span style="color:#ef4444">●</span> נמוך (יש נתוני קליטה מהמודם)</span>');
      leg.insertAdjacentHTML("beforeend", "<span>" + esc(d.source) + " (" + d.count + ")</span>");
    }
    ["rt-map-company", "rt-map-show-ant", "rt-map-only-assoc"].forEach(function (id) { var e = $(id); if (e) e.addEventListener("change", refreshAntennas); });
    $("rt-map-auto").addEventListener("click", function () { setAuto(!autoCenter); });
    $("rt-map-center-now").addEventListener("click", function () {
      if (loc) centerOnUser(true); else if (typeof showToast === "function") showToast("מפה", "ממתין למיקום GPS...", "warn");
    });
    var ex = $("rt-map-explain"); if (ex) ex.addEventListener("click", function () { alert(EXPLAIN); });
    map.on("moveend", refreshAntennas);
    map.on("zoomend", function () { restyleAll(); refreshAntennas(); });
    map.on("dragstart", function () { if (autoCenter) setAuto(false); });
    map.on("popupopen", function (e) {
      var el = e.popup.getElement(); if (!el) return;
      el.querySelectorAll("a[data-doc]").forEach(function (a) {
        a.addEventListener("click", function (ev) {
          ev.preventDefault();
          var u = a.getAttribute("data-doc");
          if (window.AndroidModem && window.AndroidModem.openUrl) window.AndroidModem.openUrl(u); else window.open(u, "_blank");
        });
      });
      el.querySelectorAll("a[data-explain]").forEach(function (a) { a.addEventListener("click", function (ev) { ev.preventDefault(); alert(EXPLAIN); }); });
    });
    setAuto(false, true);
    if (loc) { drawUser(); centerOnUser(true); }
    recompute();
    refreshAntennas();
  }

  function drawUser() {
    if (!map || !loc) return;
    var ll = [loc.lat, loc.lon];
    if (!userMarker) {
      accCircle = L.circle(ll, { radius: loc.acc || 30, color: "#3b82f6", weight: 1, fillOpacity: 0.12, interactive: false }).addTo(map);
      userMarker = L.circleMarker(ll, { radius: 8, color: "#fff", weight: 2, fillColor: "#2563eb", fillOpacity: 1 }).addTo(map);
      userMarker.bindPopup(function () {
        var st = window.state || {}, sc = st.servingCell || {}, rf = st.rf || {};
        var v = function (x, u) { return typeof x === "number" ? x + " " + u : ""; };
        return '<div dir="rtl" style="font:12px/1.5 sans-serif"><b>המיקום שלי</b><br>' + esc((sc.tech || "") + " " + (sc.band || "")) +
          "<br>PCI: " + esc(sc.pci === "---" ? "" : sc.pci) + " | TAC: " + esc(sc.tac === "---" ? "" : sc.tac) +
          "<br>RSRP: " + v(rf.rsrp, "dBm") + " | RSSI: " + v(rf.rssi, "dBm") + "<br>דיוק: " + Math.round(loc.acc || 0) + " מ׳</div>";
      });
    } else {
      userMarker.setLatLng(ll); accCircle.setLatLng(ll); accCircle.setRadius(loc.acc || 30);
    }
  }

  window.onRealLocation = function (lat, lon, acc) {
    if (typeof lat === "object" && lat) { acc = lat.acc; lon = lat.lon; lat = lat.lat; }
    if (typeof lat !== "number" || typeof lon !== "number") return;
    var first = !loc;
    loc = { lat: lat, lon: lon, acc: acc };
    window.rtLastLocation = loc;
    if (!map) return;
    drawUser();
    if (first) centerOnUser(true); else if (autoCenter) centerOnUser(false);
  };

  if (!window.AndroidModem && navigator.geolocation) {
    try {
      navigator.geolocation.watchPosition(function (p) { window.onRealLocation(p.coords.latitude, p.coords.longitude, p.coords.accuracy); },
        function () {}, { enableHighAccuracy: true, maximumAge: 1000 });
    } catch (e) {}
  }

  // accumulated data changes slowly: refresh the station links every 30 s while the map is visible
  setInterval(function () {
    var v = $("view-map");
    if (map && v && !v.classList.contains("hidden") && !document.hidden) recompute();
  }, 30000);

  window.rtMap = {
    onShow: function () {
      build();
      if (map) setTimeout(function () { map.invalidateSize(); recompute(); }, 50);
    },
    setAutoCenter: setAuto,
    recompute: recompute,
    get map() { return map; },
    get assoc() { return assoc; },
    _openIdx: function () { return openIdx; }
  };
})();
