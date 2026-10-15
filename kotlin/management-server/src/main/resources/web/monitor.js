// SPDX-License-Identifier: Apache-2.0
// The monitor (/monitor/{fabric}): the running fabric on the canvas of the blueprint editor, read only. The graph (blocks, tethers, numbers) comes as JSON
// from the server and is polled; what a click shows (statistics of a block, the tethers of a port, the last values of a tether) is HTML that the server
// renders and htmx loads into the side panel and the data dock.
(function () {
  var host = document.getElementById('monitor');
  if (!host || typeof Drawflow === 'undefined') return;
  var base = host.dataset.url;
  var editor = new Drawflow(document.getElementById('drawflow'));
  editor.reroute = false;
  editor.zoom_min = 0.25;
  editor.zoom_max = 1.6;
  editor.start();
  editor.canvas_x = 280;
  editor.canvas_y = 90;
  editor.zoom_refresh();

  var nodes = {};        // block id (or 'ext:<name>') -> { num, ins: [{port, index}], outs: [...] }
  var byNum = {};        // Drawflow node number -> block id
  var edges = {};        // 'out-in-outClass-inClass' -> tether id
  var previous = {};     // tether id -> messages at the last refresh
  var signature = '';
  var last = null;
  var selection = null;  // { kind: 'block'|'port'|'tether', ... }
  var busy = false;
  var paused = false;
  var failed = false;
  var lastOk = 0;
  var prevNow = 0;
  var rates = {};        // tether id -> values per second between the last two refreshes
  var seenSeq = null;    // newest value of the selected tether that the table has shown
  var timer = null;
  var NS = 'http://www.w3.org/2000/svg';

  var pauseButton = document.getElementById('mon-pause');
  var count = document.getElementById('mon-n');
  var every = document.getElementById('mon-every');
  var find = document.getElementById('mon-find');
  var live = document.getElementById('mon-live');
  var filter = document.getElementById('mon-filter');
  var back = document.getElementById('mon-back');
  var props = document.getElementById('mon-props');
  var dock = document.getElementById('mon-data');
  var dockTitle = document.getElementById('mon-data-title');
  var summary = document.getElementById('mon-summary');

  function esc(text) {
    var d = document.createElement('div');
    d.textContent = text;
    return d.innerHTML;
  }
  function slotsOf(block, out) {
    var list = [];
    block.ports.forEach(function (p) {
      if (p.out !== out) return;
      if (p.varArg) { for (var i = 0; i < p.count; i++) list.push({ port: p.name, index: i, label: p.name + '[' + i + ']' }); }
      else list.push({ port: p.name, index: -1, label: p.name });
    });
    return list;
  }
  function slotNumber(list, port, index) {
    for (var i = 0; i < list.length; i++) { if (list[i].port === port && (list[i].index === index || list[i].index === -1 || index < 0)) return i + 1; }
    return 0;
  }
  function nodeHtml(title, subtitle, ins, outs) {
    return '<div class="node-title"><i class="sdot"></i>' + esc(title) + '</div><small>' + esc(subtitle) + '</small>' +
      '<small class="ports">in: ' + esc(ins.map(function (s) { return s.label; }).join(', ') || '-') + ' | out: ' + esc(outs.map(function (s) { return s.label; }).join(', ') || '-') + '</small>' +
      '<small class="mon-stat"></small><small class="mon-total"></small>';
  }
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined) e.textContent = text;
    return e;
  }
  function fmt(rate) { return rate >= 100 ? String(Math.round(rate)) : rate.toFixed(rate < 10 ? 1 : 0); }
  function number(n) { return n >= 10000 ? (n / 1000).toFixed(n >= 100000 ? 0 : 1) + 'k' : String(n); }

  // a column per step from the sources, the rows in the order of the blocks
  function layout(g) {
    var level = {};
    g.blocks.forEach(function (b) { level[b.id] = 0; });
    for (var round = 0; round < g.blocks.length; round++) {
      var moved = false;
      g.tethers.forEach(function (t) {
        if (level[t.fromBlock] === undefined || level[t.toBlock] === undefined) return;
        if (level[t.toBlock] < level[t.fromBlock] + 1 && level[t.fromBlock] + 1 < g.blocks.length) { level[t.toBlock] = level[t.fromBlock] + 1; moved = true; }
      });
      if (!moved) break;
    }
    return level;
  }

  function build(g) {
    editor.clear();
    nodes = {}; byNum = {}; edges = {};
    var level = layout(g);
    var rows = {};
    g.blocks.forEach(function (b) {
      var ins = slotsOf(b, false), outs = slotsOf(b, true);
      var l = level[b.id], r = rows[l] = (rows[l] === undefined ? 0 : rows[l] + 1);
      var num = editor.addNode(b.id, ins.length, outs.length, 40 + 280 * l, 40 + 170 * r, 'monitor-node', { id: b.id, block: b.block }, nodeHtml(b.id, b.block, ins, outs), false);
      nodes[b.id] = { num: num, ins: ins, outs: outs };
      byNum[num] = b.id;
    });
    // an end that is not a block of this fabric (another engine, a service) is drawn as a node of its own
    var externals = {};
    function external(name) {
      var key = 'ext:' + (name || 'remote');
      if (!externals[key]) externals[key] = { name: name || 'remote', ins: [], outs: [] };
      return key;
    }
    g.tethers.forEach(function (t) {
      if (!nodes[t.fromBlock]) { var k = external(t.fromBlock); t._from = k; externals[k].outs.push({ port: t.id, index: -1, label: t.fromPort || 'out' }); }
      if (!nodes[t.toBlock]) { var k2 = external(t.toBlock); t._to = k2; externals[k2].ins.push({ port: t.id, index: -1, label: t.toPort || 'in' }); }
    });
    var n = 0;
    var columns = Math.max.apply(null, [0].concat(g.blocks.map(function (b) { return level[b.id]; })));
    Object.keys(externals).forEach(function (key) {
      var e = externals[key];
      var num = editor.addNode(e.name, e.ins.length, e.outs.length, 40 + 280 * (columns + 1), 40 + 170 * n++, 'monitor-node external', { id: e.name, external: true }, nodeHtml(e.name, 'other engine or service', e.ins, e.outs), false);
      nodes[key] = { num: num, ins: e.ins, outs: e.outs, external: true };
      byNum[num] = key;
    });
    g.tethers.forEach(function (t) {
      var from = nodes[t._from || t.fromBlock], to = nodes[t._to || t.toBlock];
      if (!from || !to) return;
      var o = t._from ? from.outs.findIndex(function (s) { return s.port === t.id; }) + 1 : slotNumber(from.outs, t.fromPort, t.fromIndex);
      var i = t._to ? to.ins.findIndex(function (s) { return s.port === t.id; }) + 1 : slotNumber(to.ins, t.toPort, t.toIndex);
      if (!o || !i) return;
      editor.addConnection(from.num, to.num, 'output_' + o, 'input_' + i);
      edges[from.num + '-' + to.num + '-output_' + o + '-input_' + i] = t.id;
      t._edge = { from: from.num, to: to.num, o: 'output_' + o, i: 'input_' + i };
    });
  }

  function connection(t) {
    var e = t._edge;
    if (!e) return null;
    var conn = host.querySelector('.connection.node_in_node-' + e.to + '.node_out_node-' + e.from + '.' + e.o + '.' + e.i);
    return conn && conn.querySelector('.main-path');
  }

  // the label in the middle of a line: the rate while values flow, the total when idle
  function label(t, path) {
    var conn = path.parentNode;
    var len = path.getTotalLength ? path.getTotalLength() : 0;
    if (!len) return;
    var text = conn.querySelector('text.mon-rate');
    if (!text) { text = document.createElementNS(NS, 'text'); text.setAttribute('class', 'mon-rate'); text.setAttribute('text-anchor', 'middle'); conn.appendChild(text); }
    var p = path.getPointAtLength(len / 2);
    text.setAttribute('x', p.x);
    text.setAttribute('y', p.y - 7);
    var r = rates[t.id] || 0;
    text.textContent = r > 0 ? fmt(r) + '/s' : (t.messages ? number(t.messages) : '');
    text.classList.toggle('flow', r > 0);
  }
  function labels() { if (last) last.tethers.forEach(function (t) { var p = connection(t); if (p) label(t, p); }); }

  function highlight() {
    host.querySelectorAll('.focus').forEach(function (e) { e.classList.remove('focus'); });
    if (!selection) return;
    if (selection.kind === 'tether') {
      var t = last && last.tethers.find(function (x) { return x.id === selection.id; });
      var p = t && connection(t);
      if (p) p.classList.add('focus');
    } else if (nodes[selection.id || selection.block]) {
      var n = document.getElementById('node-' + nodes[selection.id || selection.block].num);
      if (n) n.classList.add('focus');
    }
  }

  function update(g) {
    var broken = {};
    g.breakpoints.forEach(function (id) { broken[id] = true; });
    var dt = prevNow ? (g.now - prevNow) / 1000 : 0;
    g.tethers.forEach(function (t) {
      var delta = previous[t.id] === undefined ? 0 : t.messages - previous[t.id];
      rates[t.id] = dt > 0.2 ? delta / dt : (rates[t.id] || 0);
      previous[t.id] = t.messages;
      t._delta = delta;
    });
    prevNow = g.now;
    var flow = 0, errors = 0, held = 0;
    g.tethers.forEach(function (t) { flow += rates[t.id] || 0; errors += t.errors; held += g.held[t.id] || 0; });
    g.blocks.forEach(function (b) { errors += b.errors; });
    summary.textContent = g.state + ' - ' + g.blocks.length + ' blocks, ' + g.tethers.length + ' tethers' + (g.failure ? ' - ' + g.failure : '');
    g.blocks.forEach(function (b) {
      var el = document.getElementById('node-' + nodes[b.id].num);
      if (!el) return;
      el.className = el.className.replace(/\bstate-\S+|\bholding\b/g, '').trim() + ' state-' + b.state;
      var received = 0, sent = 0, inRate = 0, outRate = 0, heldHere = 0, stop = false;
      g.tethers.forEach(function (t) {
        if (t.toBlock === b.id) { received += t.messages; inRate += rates[t.id] || 0; heldHere += g.held[t.id] || 0; if (broken[t.id]) stop = true; }
        if (t.fromBlock === b.id) { sent += t.messages; outRate += rates[t.id] || 0; }
      });
      if (stop) el.classList.add('holding');
      var stat = el.querySelector('.mon-stat');
      if (stat) stat.textContent = b.state + ' | in ' + fmt(inRate) + '/s | out ' + fmt(outRate) + '/s';
      var total = el.querySelector('.mon-total');
      if (total) total.textContent = 'total in ' + number(received) + ' | out ' + number(sent) + (heldHere ? ' | held ' + heldHere : '') + (b.errors ? ' | errors ' + b.errors : '');
    });
    g.tethers.forEach(function (t) {
      var path = connection(t);
      if (!path) return;
      path.classList.toggle('active', t._delta > 0);
      path.classList.toggle('held', !!broken[t.id]);
      path.classList.toggle('err', t.errors > 0);
      path.classList.toggle('remote', !!t.remote);
      path.setAttribute('data-tether', t.id);
      var title = path.querySelector('title');
      if (!title) { title = document.createElementNS(NS, 'title'); path.appendChild(title); }
      title.textContent = t.id + ' - ' + t.messages + ' values' + (rates[t.id] > 0 ? ' (' + fmt(rates[t.id]) + '/s)' : '');
      label(t, path);
    });
    highlight();
    applyFind();
    if (!selection) showOverview(g, flow, errors, held);
  }

  // what the details show while nothing is selected: the state of the whole fabric and a way into every block
  function showOverview(g, flow, errors, held) {
    var running = g.blocks.filter(function (b) { return b.state === 'running'; }).length;
    var box = el('div', 'overview');
    var chips = el('div', 'mon-stats');
    [[running + '/' + g.blocks.length, 'blocks running', running === g.blocks.length ? '' : 'warn'], [fmt(flow) + '/s', 'values', flow > 0 ? 'flow' : ''], [String(errors), 'errors', errors ? 'bad' : ''], [String(held), 'held', held ? 'warn' : '']]
      .forEach(function (c) { var chip = el('span', 'mon-stat-chip ' + c[2]); chip.append(el('b', '', c[0]), document.createTextNode(' ' + c[1])); chips.append(chip); });
    box.append(el('h3', '', g.blueprint || g.fabric), el('p', 'muted', g.state + (g.failure ? ' - ' + g.failure : '')), chips, el('h4', '', 'Blocks'));
    var list = el('ul', 'block-list');
    g.blocks.forEach(function (b) {
      var item = el('li');
      var button = el('button', 'link');
      button.type = 'button';
      button.dataset.block = b.id;
      button.append(el('i', 'sdot state-' + b.state), document.createTextNode(b.id));
      item.append(button, el('span', 'muted', b.state + (b.errors ? ' - ' + b.errors + ' errors' : '')));
      list.append(item);
    });
    box.append(list, el('p', 'hint', 'Click a block, a port or a line. Esc comes back here.'));
    props.replaceChildren(box);
  }

  function refresh() {
    if (busy) return Promise.resolve();
    busy = true;
    return fetch(host.dataset.graphUrl, { credentials: 'same-origin' })
      .then(function (r) { return r.json(); })
      .then(function (g) {
        if (g.error) { failed = true; summary.textContent = g.error; return; }
        failed = false;
        lastOk = Date.now();
        var sig = g.blocks.map(function (b) { return b.id + ':' + b.ports.map(function (p) { return p.name + p.count; }).join(','); }).join(';') + '|' + g.tethers.map(function (t) { return t.id; }).join(';');
        if (sig !== signature) { var first = signature === ''; signature = sig; previous = {}; rates = {}; prevNow = 0; build(g); last = g; if (first) setTimeout(fit, 0); }
        else g.tethers.forEach(function (t) { var old = last && last.tethers.find(function (x) { return x.id === t.id; }); if (old) t._edge = old._edge; });
        last = g;
        update(g);
      })
      .catch(function () { failed = true; summary.textContent = 'The fabric cannot be reached.'; })
      .then(function () { busy = false; clock(); });
  }

  // the graph in the free area between the panels
  function fit() {
    var nums = Object.keys(byNum);
    if (!nums.length) return;
    var minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    nums.forEach(function (num) {
      var data = editor.drawflow.drawflow.Home.data[num], node = document.getElementById('node-' + num);
      if (!data || !node) return;
      minX = Math.min(minX, data.pos_x); minY = Math.min(minY, data.pos_y);
      maxX = Math.max(maxX, data.pos_x + node.offsetWidth); maxY = Math.max(maxY, data.pos_y + node.offsetHeight);
    });
    if (minX === Infinity) return;
    var left = 280, right = props.parentNode.offsetWidth + 40, top = 90, bottom = (dock.hidden ? 0 : dock.offsetHeight) + 40;
    var aw = Math.max(240, host.clientWidth - left - right), ah = Math.max(160, host.clientHeight - top - bottom);
    var w = maxX - minX, h = maxY - minY;
    var z = Math.max(0.3, Math.min(1.1, aw / w, ah / h));
    editor.zoom = z;
    editor.zoom_last_value = z; // zoom_refresh() rescales the position by zoom / zoom_last_value
    // the canvas scales around its own middle: screen = canvas + origin * (1 - z) + position * z
    var origin = getComputedStyle(editor.precanvas).transformOrigin.split(' ').map(parseFloat);
    editor.canvas_x = left + (aw - w * z) / 2 - (origin[0] || 0) * (1 - z) - minX * z;
    editor.canvas_y = top + (ah - h * z) / 3 - (origin[1] || 0) * (1 - z) - minY * z;
    editor.zoom_refresh();
  }

  function load(target, url) {
    if (typeof htmx === 'undefined') return;
    htmx.ajax('GET', url, { target: target, swap: 'morph:innerHTML' });
  }
  function reload() {
    if (!selection) return;
    if (selection.kind === 'block') load('#mon-props', base + '/block?id=' + encodeURIComponent(selection.id));
    else if (selection.kind === 'port') load('#mon-props', base + '/port?block=' + encodeURIComponent(selection.block) + '&port=' + encodeURIComponent(selection.port));
    else if (selection.kind === 'tether') { dock.hidden = false; load('#mon-data-body', base + '/tether?id=' + encodeURIComponent(selection.id) + '&n=' + encodeURIComponent(count.value)); }
  }
  function select(next) {
    if (next && next.kind === 'tether' && (!selection || selection.id !== next.id)) { seenSeq = null; filter.value = ''; }
    selection = next;
    back.hidden = !selection;
    if (selection && selection.kind === 'tether') {
      dockTitle.textContent = selection.id;
      if (dock.hidden) { dock.hidden = false; setTimeout(fit, 0); }
    }
    highlight();
    reload();
  }
  function clearSelection() {
    selection = null;
    back.hidden = true;
    dock.hidden = true;
    highlight();
    if (last) update(last);
  }
  function tick() { if (document.hidden || paused) return; refresh().then(reload); }
  function schedule() { clearInterval(timer); timer = setInterval(tick, parseInt(every.value, 10) || 2000); }
  function setPaused(value) {
    paused = value;
    pauseButton.textContent = paused ? 'Resume' : 'Pause';
    pauseButton.setAttribute('aria-pressed', String(paused));
    clock();
    if (!paused) tick();
  }
  function clock() {
    var age = Math.round((Date.now() - lastOk) / 1000);
    live.className = 'live ' + (paused ? 'paused' : failed ? 'bad' : 'ok');
    live.lastChild.textContent = paused ? 'Paused' : failed ? 'Disconnected' : lastOk ? 'Live - ' + (age < 2 ? 'now' : age + ' s ago') : 'Loading';
  }

  // a search that dims the other blocks; Enter selects the first match
  function matches() {
    var q = find.value.trim().toLowerCase();
    return !q ? null : (last ? last.blocks.filter(function (b) { return (b.id + ' ' + b.block).toLowerCase().indexOf(q) >= 0; }) : []);
  }
  function applyFind() {
    var hit = matches();
    Object.keys(nodes).forEach(function (key) {
      var n = document.getElementById('node-' + nodes[key].num);
      if (n) n.classList.toggle('dim', !!hit && !hit.some(function (b) { return b.id === key; }));
    });
  }

  // the table of the dock: the filter of the values and the rows that came in since the last refresh
  function applyFilter() {
    var q = filter.value.trim().toLowerCase();
    var shown = 0, all = 0;
    dock.querySelectorAll('table.values tbody tr').forEach(function (row) {
      all++;
      var hit = !q || row.textContent.toLowerCase().indexOf(q) >= 0;
      row.hidden = !hit;
      if (hit) shown++;
    });
    var note = dock.querySelector('.filter-note');
    if (note) note.textContent = q ? shown + ' of ' + all + ' values match' : '';
  }
  function markFresh() {
    var newest = seenSeq === null ? 0 : seenSeq, max = newest;
    dock.querySelectorAll('table.values tbody tr[data-seq]').forEach(function (row) {
      var seq = parseInt(row.dataset.seq, 10);
      if (seenSeq !== null && seq > newest) row.classList.add('fresh');
      if (seq > max) max = seq;
    });
    seenSeq = max;
  }

  editor.on('nodeSelected', function (num) {
    var key = byNum[num];
    if (key && !nodes[key].external) select({ kind: 'block', id: key });
  });
  editor.on('connectionSelected', function (c) {
    var id = edges[c.output_id + '-' + c.input_id + '-' + c.output_class + '-' + c.input_class];
    if (id) select({ kind: 'tether', id: id });
  });
  editor.on('nodeMoved', labels);
  editor.on('zoom', labels);

  // the monitor never changes the graph: no new connection by a drag from a port, no deleting
  var container = document.getElementById('drawflow');
  ['mousedown', 'touchstart'].forEach(function (name) {
    container.addEventListener(name, function (event) { if (event.target.closest('.input, .output')) event.stopPropagation(); }, true);
  });
  container.addEventListener('contextmenu', function (event) { event.preventDefault(); event.stopPropagation(); }, true);
  document.addEventListener('keydown', function (event) {
    if (event.key === 'Delete' || event.key === 'Backspace') { if (!event.target.closest('input')) event.stopImmediatePropagation(); return; }
    var typing = event.target.closest('input, select, textarea');
    if (event.key === 'Escape') { if (typing && typing !== filter && typing !== find) { typing.blur(); return; } clearSelection(); if (typing) typing.blur(); }
    else if (event.key === ' ' && !typing && event.target === document.body) { event.preventDefault(); setPaused(!paused); }
    else if ((event.key === 'f' || event.key === 'F') && !typing && !event.ctrlKey && !event.metaKey) fit();
  }, true);
  container.addEventListener('click', function (event) {
    var port = event.target.closest('.input, .output');
    if (!port) return;
    var nodeEl = port.closest('.drawflow-node');
    var key = nodeEl && byNum[nodeEl.id.replace('node-', '')];
    var info = key && nodes[key];
    if (!info || info.external) return;
    var out = port.classList.contains('output');
    var cls = Array.prototype.find.call(port.classList, function (c) { return /^(in|out)put_\d+$/.test(c); });
    var slot = (out ? info.outs : info.ins)[parseInt(cls.split('_')[1], 10) - 1];
    if (slot) select({ kind: 'port', block: key, port: slot.port });
  });

  host.addEventListener('click', function (event) {
    var block = event.target.closest('button[data-block]');
    if (block) { select({ kind: 'block', id: block.dataset.block }); return; }
    var tether = event.target.closest('button[data-tether]');
    if (tether) { select({ kind: 'tether', id: tether.dataset.tether }); return; }
    var port = event.target.closest('button[data-port]');
    if (port && selection && selection.kind === 'block') { select({ kind: 'port', block: selection.id, port: port.dataset.port }); return; }
    var copy = event.target.closest('button.copy');
    if (copy) {
      var code = copy.parentNode.querySelector('.payload');
      var text = code.dataset.raw || code.textContent;
      var done = function () { copy.textContent = 'Copied'; copy.classList.add('done'); };
      if (navigator.clipboard) navigator.clipboard.writeText(text).then(done, function () {}); else done();
      return;
    }
    var payload = event.target.closest('.payload');
    if (payload) {
      if (!payload.dataset.raw) payload.dataset.raw = payload.textContent;
      var open = payload.classList.toggle('open');
      if (open) { try { payload.textContent = JSON.stringify(JSON.parse(payload.dataset.raw), null, 2); } catch (e) { payload.textContent = payload.dataset.raw; } setPaused(true); }
      else payload.textContent = payload.dataset.raw;
    }
  });
  document.getElementById('mon-data-close').addEventListener('click', function () { if (selection && selection.kind === 'tether') clearSelection(); else dock.hidden = true; });
  back.addEventListener('click', clearSelection);
  pauseButton.addEventListener('click', function () { setPaused(!paused); });
  filter.addEventListener('input', applyFilter);
  find.addEventListener('input', applyFind);
  find.addEventListener('keydown', function (event) {
    if (event.key !== 'Enter') return;
    var hit = matches();
    if (hit && hit.length) select({ kind: 'block', id: hit[0].id });
  });
  host.addEventListener('htmx:afterSwap', function (event) {
    if (event.target && event.target.id === 'mon-data-body') { markFresh(); applyFilter(); }
  });
  host.addEventListener('htmx:afterRequest', function (event) {
    var form = event.target && event.target.tagName === 'FORM';
    if (form) setTimeout(function () { refresh().then(reload); }, 150);
  });
  document.getElementById('zoom-in').addEventListener('click', function () { editor.zoom_in(); });
  document.getElementById('zoom-out').addEventListener('click', function () { editor.zoom_out(); });
  document.getElementById('zoom-fit').addEventListener('click', fit);
  count.addEventListener('change', reload);
  every.addEventListener('change', schedule);
  window.addEventListener('resize', function () { clearTimeout(window.monitorFit); window.monitorFit = setTimeout(fit, 200); });

  back.hidden = true;
  refresh();
  schedule();
  setInterval(clock, 1000);
})();
