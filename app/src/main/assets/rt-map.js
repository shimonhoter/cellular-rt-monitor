/* Real map (Leaflet + OpenStreetMap tiles) with Israeli cellular antenna sites
   (data.gov.il, Ministry of Environmental Protection) and user location. */
(function () {
  "use strict";
  var map = null, userMarker = null, accCircle = null, antLayer = null;
  var loc = null, autoCenter = false, built = false;
  var COLORS = ["#22c55e", "#f59e0b", "#a855f7", "#38bdf8", "#f43f5e"];
  var $ = function (id) { return document.getElementById(id); };
  var esc = function (v) {
    return String(v == null ? "" : v).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  };

  function data() { return window.RT_ANTENNAS || null; }
  function f(row, name) { return row[data().fields.indexOf(name)]; }

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
    var z = zoomIn ? Math.max(map.getZoom(), 15) : map.getZoom();
    map.setView([loc.lat, loc.lon], z, { animate: true });
  }

  function popupHtml(row) {
    var d = data();
    var rows = [
      ["חברה", d.companies[f(row, "company")]],
      ["מס' אתר", f(row, "siteNo")],
      ["כתובת", [f(row, "city"), f(row, "address")].filter(Boolean).join(", ")],
      ["סוג אתר", d.types[f(row, "type")]],
      ["טכנולוגיות (דור)", f(row, "gens")],
      ["היתר הקמה", f(row, "buildPermit")],
      ["היתר הפעלה", f(row, "opPermit")],
      ["בדיקה תקופתית אחרונה", f(row, "lastTest")],
      ["היתר קרינה", f(row, "radPermit")],
      ["עוצמה מרבית תיאורטית", f(row, "powerUWcm2") == null ? "" : f(row, "powerUWcm2") + " µW/סמ״ר"],
      ["תוצאה מרבית מסף הבריאות", f(row, "pctOfLimit") == null ? "" : f(row, "pctOfLimit") + "%"],
      ["נקודת מדידה", f(row, "maxPoint")],
      ["מרחק ממני", loc ? Math.round(map.distance([loc.lat, loc.lon], [f(row, "lat"), f(row, "lon")])) + " מ׳" : ""]
    ];
    var html = '<div dir="rtl" style="font:12px/1.5 sans-serif;min-width:210px;max-width:260px">';
    rows.forEach(function (r) {
      if (r[1] === "" || r[1] == null) return;
      html += '<div><b>' + esc(r[0]) + ':</b> ' + esc(r[1]) + '</div>';
    });
    ["buildDoc:מסמך הקמה", "opDoc:מסמך הפעלה"].forEach(function (x) {
      var p = x.split(":"), v = f(row, p[0]);
      if (v) html += '<div><a href="#" data-doc="http://documents.sviva.gov.il/' + esc(v) + '" style="color:#0369a1">' + p[1] + ' (PDF)</a></div>';
    });
    html += '<div style="color:#64748b;margin-top:4px">מקור: המשרד להגנת הסביבה (data.gov.il)</div></div>';
    return html;
  }

  function refreshAntennas() {
    var d = data();
    if (!map || !d || !antLayer) return;
    antLayer.clearLayers();
    var show = $("rt-map-show-ant"), info = $("rt-map-info");
    if (show && !show.checked) { if (info) info.textContent = ""; return; }
    if (map.getZoom() < 10) { if (info) info.textContent = "התקרב כדי לראות תחנות"; return; }
    var comp = $("rt-map-company").value, b = map.getBounds().pad(0.1), n = 0, CAP = 3500, total = 0;
    var ci = comp === "" ? -1 : Number(comp);
    for (var i = 0; i < d.rows.length; i++) {
      var r = d.rows[i];
      if (ci >= 0 && r[2] !== ci) continue;
      if (!b.contains([r[0], r[1]])) continue;
      total++;
      if (n >= CAP) continue;
      n++;
      var m = L.circleMarker([r[0], r[1]], {
        radius: map.getZoom() >= 14 ? 7 : 5, color: "#0f172a", weight: 1,
        fillColor: COLORS[r[2] % COLORS.length], fillOpacity: 0.9
      });
      m.bindPopup(popupHtml(r));
      antLayer.addLayer(m);
    }
    if (info) info.textContent = "תחנות בתצוגה: " + total + (total > CAP ? " (מוצגות " + CAP + ", התקרב)" : "");
  }

  function build() {
    if (built) return;
    var host = $("rt-map");
    if (!host) return;
    if (typeof L === "undefined") { host.innerHTML = '<div style="padding:12px;color:#94a3b8">ספריית המפה לא נטענה</div>'; return; }
    built = true;
    map = L.map(host, { zoomControl: true, preferCanvas: true, minZoom: 6, maxZoom: 19 }).setView([31.8, 35.0], 8);
    L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
      maxZoom: 19, attribution: "© OpenStreetMap"
    }).addTo(map);
    antLayer = L.layerGroup().addTo(map);

    var d = data();
    if (d) {
      var sel = $("rt-map-company"), leg = $("rt-map-legend");
      d.companies.forEach(function (c, i) {
        var o = document.createElement("option"); o.value = i; o.textContent = c; sel.appendChild(o);
        leg.insertAdjacentHTML("beforeend", '<span><span style="display:inline-block;width:9px;height:9px;border-radius:50%;background:' +
          COLORS[i % COLORS.length] + ';margin-left:3px"></span>' + esc(c) + '</span>');
      });
      leg.insertAdjacentHTML("beforeend", "<span>" + esc(d.source) + " (" + d.count + ")</span>");
    }
    $("rt-map-company").addEventListener("change", refreshAntennas);
    $("rt-map-show-ant").addEventListener("change", refreshAntennas);
    $("rt-map-auto").addEventListener("click", function () { setAuto(!autoCenter); });
    $("rt-map-center-now").addEventListener("click", function () {
      if (loc) centerOnUser(true);
      else if (typeof showToast === "function") showToast("מפה", "ממתין למיקום GPS...", "warn");
    });
    map.on("moveend zoomend", refreshAntennas);
    // manual pan turns auto-centering off (so the map doesn't fight the user)
    map.on("dragstart", function () { if (autoCenter) setAuto(false); });
    map.on("popupopen", function (e) {
      var el = e.popup.getElement();
      if (!el) return;
      el.querySelectorAll("a[data-doc]").forEach(function (a) {
        a.addEventListener("click", function (ev) {
          ev.preventDefault();
          var u = a.getAttribute("data-doc");
          if (window.AndroidModem && window.AndroidModem.openUrl) window.AndroidModem.openUrl(u);
          else window.open(u, "_blank");
        });
      });
    });
    setAuto(false, true);
    if (loc) { drawUser(); centerOnUser(true); }
    refreshAntennas();
  }

  function drawUser() {
    if (!map || !loc) return;
    var ll = [loc.lat, loc.lon];
    if (!userMarker) {
      accCircle = L.circle(ll, { radius: loc.acc || 30, color: "#3b82f6", weight: 1, fillOpacity: 0.12 }).addTo(map);
      userMarker = L.circleMarker(ll, { radius: 8, color: "#fff", weight: 2, fillColor: "#2563eb", fillOpacity: 1 }).addTo(map);
      userMarker.bindPopup(function () {
        var st = window.state || {}, sc = st.servingCell || {}, rf = st.rf || {};
        var v = function (x, u) { return typeof x === "number" ? x + " " + u : "--"; };
        return '<div dir="rtl" style="font:12px/1.5 sans-serif"><b>המיקום שלי</b><br>' +
          esc((sc.tech || "") + " " + (sc.band || "")) + "<br>PCI: " + esc(sc.pci) + " | TAC: " + esc(sc.tac) +
          "<br>RSRP: " + v(rf.rsrp, "dBm") + " | RSSI: " + v(rf.rssi, "dBm") +
          "<br>דיוק: " + Math.round(loc.acc || 0) + " מ׳</div>";
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
    if (first) centerOnUser(true);
    else if (autoCenter) centerOnUser(false);
  };

  // Browser geolocation fallback (when not running inside the Android bridge)
  if (!window.AndroidModem && navigator.geolocation) {
    try {
      navigator.geolocation.watchPosition(function (p) {
        window.onRealLocation(p.coords.latitude, p.coords.longitude, p.coords.accuracy);
      }, function () {}, { enableHighAccuracy: true, maximumAge: 1000 });
    } catch (e) {}
  }

  window.rtMap = {
    onShow: function () {
      build();
      if (map) setTimeout(function () { map.invalidateSize(); refreshAntennas(); }, 50);
    },
    setAutoCenter: setAuto,
    get map() { return map; }
  };
})();
