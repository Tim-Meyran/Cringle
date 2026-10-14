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

  var paused = document.getElementById('mon-pause');
  var count = document.getElementById('mon-n');
  var props = document.getElementById('mon-props');
  var dock = document.getElementById('mon-data');
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
    return '<div class="node-title">' + esc(title) + '</div><small>' + esc(subtitle) + '</small>' +
      '<small class="ports">in: ' + esc(ins.map(function (s) { return s.label; }).join(', ') || '-') + ' | out: ' + esc(outs.map(function (s) { return s.label; }).join(', ') || '-') + '</small>' +
      '<small class="mon-stat"></small>';
  }

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
      var num = editor.addNode(b.id, ins.length, outs.length, 40 + 300 * l, 40 + 170 * r, 'monitor-node', { id: b.id, block: b.block }, nodeHtml(b.id, b.block, ins, outs), false);
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
      var num = editor.addNode(e.name, e.ins.length, e.outs.length, 40 + 300 * (columns + 1), 40 + 170 * n++, 'monitor-node external', { id: e.name, external: true }, nodeHtml(e.name, 'other engine or service', e.ins, e.outs), false);
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

  function update(g) {
    var broken = {};
    g.breakpoints.forEach(function (id) { broken[id] = true; });
    summary.textContent = g.state + ' - ' + g.blocks.length + ' blocks, ' + g.tethers.length + ' tethers' + (g.failure ? ' - ' + g.failure : '');
    g.blocks.forEach(function (b) {
      var el = document.getElementById('node-' + nodes[b.id].num);
      if (!el) return;
      el.className = el.className.replace(/\bstate-\S+|\bholding\b/g, '').trim() + ' state-' + b.state;
      var received = 0, sent = 0, held = 0, stop = false;
      g.tethers.forEach(function (t) {
        if (t.toBlock === b.id) { received += t.messages; held += g.held[t.id] || 0; if (broken[t.id]) stop = true; }
        if (t.fromBlock === b.id) sent += t.messages;
      });
      if (stop) el.classList.add('holding');
      var stat = el.querySelector('.mon-stat');
      if (stat) stat.textContent = b.state + ' | in ' + received + ' | out ' + sent + (held ? ' | held ' + held : '') + (b.errors ? ' | errors ' + b.errors : '');
    });
    g.tethers.forEach(function (t) {
      var path = connection(t);
      if (!path) return;
      var delta = previous[t.id] === undefined ? 0 : t.messages - previous[t.id];
      previous[t.id] = t.messages;
      path.classList.toggle('active', delta > 0);
      path.classList.toggle('held', !!broken[t.id]);
      path.classList.toggle('err', t.errors > 0);
      path.classList.toggle('remote', !!t.remote);
      path.setAttribute('data-tether', t.id);
      var title = path.querySelector('title');
      if (!title) { title = document.createElementNS('http://www.w3.org/2000/svg', 'title'); path.appendChild(title); }
      title.textContent = t.id + ' - ' + t.messages + ' values' + (delta > 0 ? ' (+' + delta + ')' : '');
    });
  }

  function refresh() {
    if (busy) return Promise.resolve();
    busy = true;
    return fetch(host.dataset.graphUrl, { credentials: 'same-origin' })
      .then(function (r) { return r.json(); })
      .then(function (g) {
        if (g.error) { summary.textContent = g.error; return; }
        var sig = g.blocks.map(function (b) { return b.id + ':' + b.ports.map(function (p) { return p.name + p.count; }).join(','); }).join(';') + '|' + g.tethers.map(function (t) { return t.id; }).join(';');
        if (sig !== signature) { signature = sig; build(g); }
        else g.tethers.forEach(function (t) { var old = last && last.tethers.find(function (x) { return x.id === t.id; }); if (old) t._edge = old._edge; });
        last = g;
        update(g);
      })
      .catch(function () { summary.textContent = 'The fabric cannot be reached.'; })
      .then(function () { busy = false; });
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
  function select(next) { selection = next; reload(); }
  function tick() { if (document.hidden) return; refresh().then(function () { if (!paused.checked) reload(); }); }

  editor.on('nodeSelected', function (num) {
    var key = byNum[num];
    if (key && !nodes[key].external) select({ kind: 'block', id: key });
  });
  editor.on('connectionSelected', function (c) {
    var id = edges[c.output_id + '-' + c.input_id + '-' + c.output_class + '-' + c.input_class];
    if (id) select({ kind: 'tether', id: id });
  });

  // the monitor never changes the graph: no new connection by a drag from a port, no deleting
  var container = document.getElementById('drawflow');
  ['mousedown', 'touchstart'].forEach(function (name) {
    container.addEventListener(name, function (event) { if (event.target.closest('.input, .output')) event.stopPropagation(); }, true);
  });
  container.addEventListener('contextmenu', function (event) { event.preventDefault(); event.stopPropagation(); }, true);
  document.addEventListener('keydown', function (event) { if (event.key === 'Delete' || event.key === 'Backspace') event.stopImmediatePropagation(); }, true);
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
    var tether = event.target.closest('[data-tether]');
    if (tether && tether.tagName === 'BUTTON') { select({ kind: 'tether', id: tether.dataset.tether }); return; }
    var port = event.target.closest('button[data-port]');
    if (port && selection && selection.kind === 'block') { select({ kind: 'port', block: selection.id, port: port.dataset.port }); return; }
    var payload = event.target.closest('.payload');
    if (payload) { payload.classList.toggle('open'); paused.checked = true; }
  });
  document.getElementById('mon-data-close').addEventListener('click', function () { dock.hidden = true; if (selection && selection.kind === 'tether') selection = null; });
  host.addEventListener('htmx:afterRequest', function (event) {
    var form = event.target && event.target.tagName === 'FORM';
    if (form) setTimeout(function () { refresh().then(reload); }, 150);
  });
  document.getElementById('zoom-in').addEventListener('click', function () { editor.zoom_in(); });
  document.getElementById('zoom-out').addEventListener('click', function () { editor.zoom_out(); });
  document.getElementById('zoom-fit').addEventListener('click', function () { editor.zoom_reset(); });
  count.addEventListener('change', reload);

  refresh();
  setInterval(tick, 2000);
})();
