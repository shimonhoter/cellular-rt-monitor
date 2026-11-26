/* Links modem observations to physical antenna sites.
   Pure functions (no DOM) so they can be unit-tested. See "איך מחושב השיוך" in the UI for the user-facing explanation.

   Input : permanent observation rows (one per cell per ~22 m place) + the gov.il antenna dataset.
   Output: per observed cell-group the most plausible physical site, a confidence score and the evidence behind it.

   Evidence used (none of it is an identifier match — the gov.il data has no Cell-ID / PCI):
     1. operator (PLMN → company) and generation filter the candidate sites;
     2. proximity of the strongest readings to the site;
     3. signal-vs-distance fit: RSRP should fall with log10(distance) from the right site (regression per candidate);
     4. Timing Advance (LTE) vs distance from the site;
     5. geometry: readings from all around the site, over a range of distances, are far stronger evidence than a single spot;
     6. margin over the next-best candidate. */
(function (root) {
  "use strict";

  var OBS = ["tech", "plmn", "gnb", "pci", "arfcn", "band", "gx", "gy", "n", "sumLat", "sumLon", "sumRsrp", "maxRsrp", "minRsrp",
    "sumRsrq", "nRsrq", "sumSinr", "nSinr", "sumRssi", "nRssi", "sumTa", "nTa", "firstTs", "lastTs"];
  var PLMN_COMPANY = { "425-01": "PHI", "425-02": "סלקום", "425-03": "פלאפון", "425-07": "PHI", "425-08": "סלקום", "425-19": "PHI" };
  var TA_M = 78.12;

  function rad(d) { return d * Math.PI / 180; }
  function dist(la1, lo1, la2, lo2) {
    var R = 6371000, a = Math.sin(rad(la2 - la1) / 2) ** 2 + Math.cos(rad(la1)) * Math.cos(rad(la2)) * Math.sin(rad(lo2 - lo1) / 2) ** 2;
    return 2 * R * Math.asin(Math.sqrt(a));
  }
  function bearing(la1, lo1, la2, lo2) {
    var y = Math.sin(rad(lo2 - lo1)) * Math.cos(rad(la2)), x = Math.cos(rad(la1)) * Math.sin(rad(la2)) - Math.sin(rad(la1)) * Math.cos(rad(la2)) * Math.cos(rad(lo2 - lo1));
    return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
  }
  function genOf(tech) {
    tech = String(tech || "");
    return tech.indexOf("5G") >= 0 ? "5" : tech.indexOf("4G") >= 0 ? "4" : tech.indexOf("3G") >= 0 ? "3" : tech.indexOf("2G") >= 0 ? "2" : "";
  }
  var clamp = function (v, a, b) { return Math.max(a, Math.min(b, v)); };

  function groupObs(rows) {
    var groups = {};
    rows.forEach(function (r) {
      var o = {}; OBS.forEach(function (c, i) { o[c] = r[i]; });
      var site = o.gnb >= 0 ? "e" + o.gnb : "p" + o.pci + "@" + o.arfcn;
      var key = o.tech + "|" + o.plmn + "|" + site;
      var g = groups[key];
      if (!g) g = groups[key] = { key: key, tech: o.tech, plmn: o.plmn, gnb: o.gnb >= 0 ? o.gnb : null, pcis: {}, arfcns: {}, bands: {}, pts: [], N: 0,
        sRsrp: 0, mx: -999, mn: 999, sRsrq: 0, nRsrq: 0, sSinr: 0, nSinr: 0, sRssi: 0, nRssi: 0, first: o.firstTs, last: o.lastTs };
      if (o.pci >= 0) g.pcis[o.pci] = (g.pcis[o.pci] || 0) + o.n;
      if (o.arfcn >= 0) g.arfcns[o.arfcn] = 1;
      if (o.band) g.bands[o.band] = 1;
      g.pts.push({ lat: o.sumLat / o.n, lon: o.sumLon / o.n, n: o.n, mean: o.sumRsrp / o.n, max: o.maxRsrp, ta: o.nTa >= Math.max(1, o.n * 0.5) ? o.sumTa / o.nTa : null });
      g.N += o.n; g.sRsrp += o.sumRsrp; g.mx = Math.max(g.mx, o.maxRsrp); g.mn = Math.min(g.mn, o.minRsrp);
      g.sRsrq += o.sumRsrq; g.nRsrq += o.nRsrq; g.sSinr += o.sumSinr; g.nSinr += o.nSinr; g.sRssi += o.sumRssi; g.nRssi += o.nRssi;
      g.first = Math.min(g.first, o.firstTs); g.last = Math.max(g.last, o.lastTs);
    });
    return Object.keys(groups).map(function (k) { return groups[k]; });
  }

  // evaluate one candidate site against one cell-group
  function evaluate(g, c) {
    var pts = g.pts, n = pts.length, sx = 0, sy = 0, sxx = 0, sxy = 0, syy = 0, sw = 0;
    var dmin = 1e9, dmax = 0, strongest = null, bearings = [], taN = 0, taOk = 0;
    pts.forEach(function (p) {
      var d = Math.max(10, dist(c.lat, c.lon, p.lat, p.lon)), x = Math.log10(d), y = p.mean, w = Math.sqrt(p.n);
      p.d = d; sw += w; sx += w * x; sy += w * y; sxx += w * x * x; sxy += w * x * y; syy += w * y * y;
      dmin = Math.min(dmin, d); dmax = Math.max(dmax, d);
      if (!strongest || p.max > strongest.max) strongest = p;
      if (d > 25) bearings.push(bearing(c.lat, c.lon, p.lat, p.lon));
      if (p.ta != null) { taN++; if (Math.abs(d - p.ta * TA_M) <= 110) taOk++; }
    });
    var mx = sx / sw, my = sy / sw, vx = sxx / sw - mx * mx, vy = syy / sw - my * my, cov = sxy / sw - mx * my;
    var slope = vx > 1e-6 ? cov / vx : 0, corr = (vx > 1e-6 && vy > 1e-6) ? cov / Math.sqrt(vx * vy) : 0;
    var rmse = Math.sqrt(Math.max(0, vy - (vx > 1e-6 ? cov * cov / vx : 0)));
    var spreadOk = (dmax - dmin) >= 40;                       // regression only meaningful over a range of distances
    var E = spreadOk ? rmse + (slope > -8 ? 6 : 0) : 8;
    var dStrong = strongest ? strongest.d : 1e9, P = Math.exp(-dStrong / 250);
    var T = taN >= 3 && g.tech.indexOf("4G") >= 0 ? taOk / taN : null;
    // angular coverage = 1 - largest empty bearing gap
    var cover = 0;
    if (bearings.length >= 2) {
      bearings.sort(function (a, b) { return a - b; });
      var gap = 360 - bearings[bearings.length - 1] + bearings[0];
      for (var i = 1; i < bearings.length; i++) gap = Math.max(gap, bearings[i] - bearings[i - 1]);
      cover = 1 - gap / 360;
    }
    var cost = E + 12 * (1 - P) + (T != null ? 10 * (1 - T) : 0);
    return { c: c, cost: cost, rmse: rmse, slope: slope, corr: corr, spreadOk: spreadOk, dStrong: dStrong, P: P, T: T, taN: taN, cover: cover,
      dmin: dmin, dmax: dmax, E: E };
  }

  function confidence(g, best, second, nCand, plmnKnown) {
    var nPts = g.pts.length;
    var Q = Math.min(1, nPts / 10) * 0.7 + Math.min(1, g.N / 100) * 0.3;
    var spread = best.dmax > 0 ? Math.min(1, (best.dmax - best.dmin) / Math.max(60, 0.5 * best.dmax)) : 0;
    var G = best.cover * 0.7 + spread * 0.3;
    var F = 0.5 * clamp(-best.corr, 0, 1) + 0.5 * clamp(1 - (best.E - 3) / 12, 0, 1);
    if (!best.spreadOk) F = 0.15;
    var M = nCand > 1 && second ? clamp((second.cost - best.cost) / 10, 0, 1) : 0.6;
    var P = best.P, T = best.T;
    var v = T != null ? 0.15 * Q + 0.15 * G + 0.20 * F + 0.20 * M + 0.15 * P + 0.15 * T : 0.20 * (Q + G + F + M + P);
    var pct = Math.round(100 * v);
    var caps = [];
    if (nPts < 3) { pct = Math.min(pct, 30); caps.push("פחות מ-3 מיקומי מדידה"); }
    if (nPts < 5) { pct = Math.min(pct, 55); if (nPts >= 3) caps.push("מעט מיקומי מדידה (פחות מ-5)"); }
    if (best.cover < 0.35 && nPts >= 3) { pct = Math.min(pct, 65); caps.push("המדידות רק מצד אחד של התחנה"); }
    if (best.dStrong > 600) { pct = Math.min(pct, 25); caps.push("הקריאה החזקה ביותר רחוקה מ-600 מ׳ מהתחנה"); }
    if (!plmnKnown) { pct = Math.min(pct, 45); caps.push("מפעיל לא מזוהה"); }
    return { pct: pct, level: pct >= 65 ? "high" : pct >= 35 ? "mid" : "low", parts: { Q: Q, G: G, F: F, M: M, P: P, T: T }, caps: caps };
  }

  /** rows: observation rows (see OBS). ant: window.RT_ANTENNAS-shaped data. Returns {groups, bySite}. */
  function associate(rows, ant) {
    var groups = groupObs(rows), bySite = {};
    var li = {}; ant.fields.forEach(function (f, i) { li[f] = i; });
    groups.forEach(function (g) {
      var pts = g.pts, cla = 0, clo = 0, cw = 0;
      pts.forEach(function (p) { var w = Math.pow(10, (p.mean + 130) / 10) * p.n; cla += w * p.lat; clo += w * p.lon; cw += w; });
      cla /= cw; clo /= cw;
      var name = PLMN_COMPANY[g.plmn], plmnKnown = !!name, ci = -1;
      if (name) ci = ant.companies.findIndex(function (x) { return x.indexOf(name) === 0; });
      var gen = genOf(g.tech);
      var minLa = 90, maxLa = -90, minLo = 180, maxLo = -180;
      pts.forEach(function (p) { minLa = Math.min(minLa, p.lat); maxLa = Math.max(maxLa, p.lat); minLo = Math.min(minLo, p.lon); maxLo = Math.max(maxLo, p.lon); });
      var padLa = 0.012, padLo = 0.015;
      function pick(useGen) {
        var out = [];
        for (var i = 0; i < ant.rows.length; i++) {
          var r = ant.rows[i];
          if (ci >= 0 && r[li.company] !== ci) continue;
          if (useGen && gen && String(r[li.gens]).indexOf(gen) < 0) continue;
          if (r[0] < minLa - padLa || r[0] > maxLa + padLa || r[1] < minLo - padLo || r[1] > maxLo + padLo) continue;
          out.push({ idx: i, lat: r[0], lon: r[1], d: dist(cla, clo, r[0], r[1]) });
        }
        return out.sort(function (a, b) { return a.d - b.d; }).slice(0, 250);
      }
      var cands = pick(true); if (!cands.length) cands = pick(false);
      g.cands = cands.length;
      if (!cands.length) { g.best = null; return; }
      var ev = cands.map(function (c) { return evaluate(g, c); }).sort(function (a, b) { return a.cost - b.cost; });
      var best = ev[0], second = ev[1] || null;
      g.best = { idx: best.c.idx, ev: best, conf: confidence(g, best, second, ev.length, plmnKnown) };
      g.alts = ev.slice(1, 3).map(function (e) { return { idx: e.c.idx, cost: e.cost, dStrong: e.dStrong }; });
      (bySite[best.c.idx] = bySite[best.c.idx] || []).push(g);
    });
    Object.keys(bySite).forEach(function (k) { bySite[k].sort(function (a, b) { return b.best.conf.pct - a.best.conf.pct; }); });
    return { groups: groups, bySite: bySite };
  }

  var api = { OBS: OBS, associate: associate, groupObs: groupObs, dist: dist, PLMN_COMPANY: PLMN_COMPANY };
  if (typeof module !== "undefined" && module.exports) module.exports = api; else root.RtAssoc = api;
})(typeof window !== "undefined" ? window : this);
