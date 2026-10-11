// Own helpers of the WebUI; the page logic is in htmx attributes and Alpine x-data.
// The lists refresh themselves (hx-trigger "every 5s [cringleIdle()]"), morphing the new HTML into the old. They wait while the user is working in a
// list: a field has the focus, a <details> is open, or a field differs from what it had when it was rendered (text typed, an option changed).
window.cringleIdle = function () {
  var list = document.getElementById('list');
  if (!list) return true;
  if (document.querySelector('.htmx-request:not(#list)')) return false; // a request of the user is running
  var active = document.activeElement;
  if (active && list.contains(active) && /^(INPUT|TEXTAREA|SELECT)$/.test(active.tagName)) return false;
  if (list.querySelector('details[open]') || document.querySelector('dialog[open]')) return false;
  var fields = list.querySelectorAll('input, textarea, select');
  for (var i = 0; i < fields.length; i++) {
    var f = fields[i];
    if (f.tagName === 'SELECT') {
      for (var j = 0; j < f.options.length; j++) if (f.options[j].selected !== f.options[j].defaultSelected) return false;
    } else if (f.type === 'checkbox' || f.type === 'radio') {
      if (f.checked !== f.defaultChecked) return false;
    } else if (f.type === 'file') {
      if (f.files && f.files.length > 0) return false;
    } else if (f.type !== 'hidden' && f.value !== f.defaultValue) {
      return false;
    }
  }
  return true;
};
// A request of the user (a button, a form) must not meet a refresh that started earlier: its answer would arrive later than the answer of the
// request and put the old state back. So a request that is not a GET aborts the refresh in flight (htmx:abort), and no refresh starts while one runs.
document.addEventListener('htmx:beforeRequest', function (event) {
  var config = event.detail.requestConfig;
  var list = document.getElementById('list');
  if (config && config.verb && config.verb !== 'get' && list && list.classList.contains('htmx-request')) htmx.trigger(list, 'htmx:abort');
});
// Feedback lives in #flash (see flash() in PageSupport.kt): the refresh never touches it. Keep the last three plain messages; a result that is shown
// once (data-sticky) and a form that continues a flow stay until they are closed or used.
document.addEventListener('htmx:oobAfterSwap', function (event) {
  var flash = document.getElementById('flash');
  if (!flash || !event.target || event.target.id !== 'flash') return;
  var plain = Array.prototype.filter.call(flash.children, function (item) { return !item.hasAttribute('data-sticky'); });
  plain.slice(0, -3).forEach(function (item) { item.remove(); });
});
// a form in #flash (the confirmation of a step) is used up by sending it
document.addEventListener('htmx:afterRequest', function (event) {
  var item = event.detail.elt && event.detail.elt.closest && event.detail.elt.closest('#flash > .notice');
  if (item && event.detail.successful) item.remove();
});

// htmx reports a request that fails at the network or with a status >= 400 as an event; show the answer of the server inline.
document.addEventListener('htmx:responseError', function (event) {
  var target = event.detail.target;
  if (target && event.detail.xhr.responseText) {
    var message = document.createElement('p');
    message.className = 'error';
    message.textContent = 'Error ' + event.detail.xhr.status;
    target.prepend(message);
  }
});

// The blueprint editor (/blueprints/{name}): Drawflow draws the graph, the server decides whether two ports may be connected and turns the graph into the blueprint.
(function () {
  var host = document.getElementById('blueprint-editor');
  if (!host || typeof Drawflow === 'undefined') return;
  var palette = JSON.parse(host.dataset.palette);
  var options = JSON.parse(host.dataset.options);
  var byBlock = {};
  palette.forEach(function (p) { byBlock[p.block] = p; });
  var editor = new Drawflow(document.getElementById('drawflow'));
  editor.reroute = false;
  editor.start();
  var graph = JSON.parse(host.dataset.graph);
  if (Object.keys(graph.drawflow.Home.data).length > 0) editor.import(graph);
  // the palette, the toolbar and the properties float above the canvas: start with the graph clear of the palette and the toolbar
  editor.canvas_x = 280;
  editor.canvas_y = 90;
  editor.zoom_refresh();

  function csrf() {
    try { return JSON.parse(document.body.getAttribute('hx-headers'))['X-CSRF-Token']; } catch (e) { return ''; }
  }
  function esc(text) {
    var d = document.createElement('div');
    d.textContent = text;
    return d.innerHTML;
  }
  function say(text, error) {
    var box = document.getElementById('result');
    var p = document.createElement('p');
    p.className = error ? 'error' : 'info';
    p.textContent = text;
    box.replaceChildren(p);
  }
  function blockData(id) { return editor.getNodeFromId(id).data; }
  // the ports of a node as it draws them: a VarArg port once per slot (name[0], name[1], ...), in the order of the definition
  function slots(blockType, kind, counts) {
    var p = byBlock[blockType], out = [];
    ((p && p[kind]) || []).forEach(function (port) {
      if (port.varArg) {
        var n = Math.max(1, (counts && counts[port.name]) || 1);
        for (var i = 0; i < n; i++) out.push({ port: port.name, index: i, label: port.name + '[' + i + ']' });
      } else {
        out.push({ port: port.name, index: null, label: port.name });
      }
    });
    return out;
  }
  function defaultCounts(blockType) {
    var counts = {};
    ((byBlock[blockType] || {}).inputs || []).concat((byBlock[blockType] || {}).outputs || []).forEach(function (port) { if (port.varArg) counts[port.name] = 1; });
    return counts;
  }
  function nodeHtml(data) {
    var ins = slots(data.block, 'inputs', data.varArgCounts), outs = slots(data.block, 'outputs', data.varArgCounts);
    var label = function (l) { return l.label; };
    return '<div class="node-title">' + esc(data.id) + '</div><small>' + esc(data.block) + '</small><small class="ports">in: ' + esc(ins.map(label).join(', ')) +
      ' | out: ' + esc(outs.map(label).join(', ')) + '</small>';
  }
  function slotLabel(data, kind, className) {
    var n = parseInt(className.replace(kind === 'outputs' ? 'output_' : 'input_', ''), 10);
    var slot = slots(data.block, kind, data.varArgCounts)[n - 1];
    return slot ? slot.label : '?';
  }
  function isExternal(data) { return data.kind === 'external'; }
  function endName(data, kind, className) { return isExternal(data) ? data.id : data.id + '.' + slotLabel(data, kind, className); }
  function edgeKey(c) {
    var from = blockData(c.output_id), to = blockData(c.input_id);
    return endName(from, 'outputs', c.output_class) + '>' + endName(to, 'inputs', c.input_class);
  }
  // an external end: a port on another engine or a service of another project, drawn as a node with one port
  function externalHtml(data) {
    var what = data.mode === 'service' ? 'service ' + (data.name || '') : 'remote ' + (data.fabric || '') + '/' + (data.block || '') + '.' + (data.port || '');
    return '<div class="node-title">' + esc(data.id) + '</div><small>' + esc(what) + '</small>';
  }
  function addExternal(kind) {
    var send = kind !== 'remote-send';
    var data = { kind: 'external', id: uniqueId('ext'), mode: kind === 'service' ? 'service' : 'remote', type: 'MESSAGE', send: send };
    if (data.mode === 'service') data.name = ''; else { data.fingerprint = ''; data.fabric = ''; data.block = ''; data.port = ''; data.address = ''; data.index = ''; }
    var count = Object.keys(editor.export().drawflow.Home.data).length;
    editor.addNode('external', send ? 1 : 0, send ? 0 : 1, 40 + 220 * (count % 4), 40 + 140 * Math.floor(count / 4), 'cringle-external', data, externalHtml(data));
  }
  document.querySelectorAll('#externals button').forEach(function (button) {
    button.addEventListener('click', function () { addExternal(button.dataset.external); });
  });
  function uniqueId(base) {
    var ids = {};
    var nodes = editor.export().drawflow.Home.data;
    Object.keys(nodes).forEach(function (k) { ids[nodes[k].data.id] = true; });
    var n = 1;
    while (ids[base + n]) n++;
    return base + n;
  }
  function setTitle(nodeId, text) {
    var el = document.querySelector('#node-' + nodeId + ' .node-title');
    if (el) el.textContent = text;
  }

  // the hint on an empty canvas
  var hint = document.getElementById('canvas-hint');
  function showHint() {
    if (hint) hint.hidden = Object.keys(editor.export().drawflow.Home.data).length > 0;
  }
  editor.on('nodeCreated', showHint);
  editor.on('nodeRemoved', showHint);
  showHint();

  // the search field of the palette filters the buttons and the plugin headings
  var search = document.getElementById('palette-search');
  if (search) {
    search.addEventListener('input', function () {
      var q = search.value.trim().toLowerCase();
      var heading = null, any = false;
      Array.prototype.forEach.call(document.getElementById('palette').children, function (el) {
        if (el.tagName === 'H3') {
          if (heading) heading.hidden = !any;
          heading = el; any = false;
        } else if (el.tagName === 'BUTTON') {
          el.hidden = q !== '' && el.dataset.block.toLowerCase().indexOf(q) < 0;
          if (!el.hidden) any = true;
        }
      });
      if (heading) heading.hidden = !any;
    });
  }

  // adding a block from the palette
  document.querySelectorAll('#palette button').forEach(function (button) {
    button.addEventListener('click', function () {
      var p = byBlock[button.dataset.block];
      if (!p) return;
      var id = uniqueId(p.title);
      var data = { id: id, block: p.block, config: {}, isolation: 'SHARED', varArgCounts: defaultCounts(p.block) };
      var count = Object.keys(editor.export().drawflow.Home.data).length;
      editor.addNode(p.block, slots(p.block, 'inputs', data.varArgCounts).length, slots(p.block, 'outputs', data.varArgCounts).length,
        40 + 220 * (count % 4), 40 + 140 * Math.floor(count / 4), 'cringle-block', data, nodeHtml(data));
    });
  });

  var rejecting = false;
  // the server decides whether a connection is allowed
  var rebuilding = false;
  editor.on('connectionCreated', function (c) {
    if (rebuilding) return;
    var from = blockData(c.output_id), to = blockData(c.input_id);
    var body = new URLSearchParams({
      fromExternal: isExternal(from) ? from.type : undefined, toExternal: isExternal(to) ? to.type : undefined,
      fromBlock: from.block, fromOutput: c.output_class.replace('output_', ''), fromCounts: JSON.stringify(from.varArgCounts || {}),
      toBlock: to.block, toInput: c.input_class.replace('input_', ''), toCounts: JSON.stringify(to.varArgCounts || {}),
    });
    ['fromExternal', 'toExternal'].forEach(function (k) { if (body.get(k) === 'undefined') body.delete(k); });
    ['fromBlock', 'toBlock'].forEach(function (k) { if (body.get(k) === 'undefined') body.delete(k); });
    fetch(host.dataset.checkUrl, { method: 'POST', headers: { 'X-CSRF-Token': csrf(), 'Content-Type': 'application/x-www-form-urlencoded' }, body: body })
      .then(function (r) { return r.json(); })
      .then(function (verdict) {
        if (verdict.ok) {
          say('Connected as a ' + verdict.type + ' tether.', false);
          window.cringleEditorSnapshot();
        } else {
          rejecting = true;
          editor.removeSingleConnection(c.output_id, c.input_id, c.output_class, c.input_class);
          rejecting = false;
          say('Not connected: ' + verdict.message, true);
        }
      })
      .catch(function () {
        rejecting = true;
        editor.removeSingleConnection(c.output_id, c.input_id, c.output_class, c.input_class);
        rejecting = false;
        say('Not connected: the server could not be asked.', true);
      });
  });

  // properties of the selected block or connection
  var panel = document.getElementById('node-properties');
  function field(label, element) {
    var l = document.createElement('label');
    l.append(label + ' ', element);
    var wrap = document.createElement('div');
    wrap.append(l);
    return wrap;
  }
  function showExternal(id, data) {
    function text(label, name, placeholder) {
      var input = document.createElement('input');
      input.value = data[name] === undefined ? '' : data[name];
      if (placeholder) input.placeholder = placeholder;
      input.addEventListener('input', function () {
        data[name] = input.value.trim();
        editor.updateNodeDataFromId(id, data);
        var content = document.querySelector('#node-' + id + ' .drawflow_content_node');
        if (content) content.innerHTML = externalHtml(data);
        changed();
      });
      return field(label, input);
    }
    var type = document.createElement('select');
    ['MESSAGE', 'REQUEST_RESPONSE', 'STREAM', 'BYTE_STREAM'].forEach(function (v) { var opt = document.createElement('option'); opt.textContent = v; opt.selected = data.type === v; type.append(opt); });
    type.addEventListener('change', function () { data.type = type.value; editor.updateNodeDataFromId(id, data); changed(); });
    var parts = [field('Tether type', type)];
    if (data.mode === 'service') {
      parts.push(text('Name of the service', 'name', 'orders'));
    } else {
      parts.push(text('Key of the other engine (SHA-256)', 'fingerprint', '64 hex characters'), text('Fabric', 'fabric'), text('Block', 'block'), text('Port', 'port'),
        text('Address (host:port)', 'address', 'found through the router'), text('Index (VarArg port)', 'index'));
    }
    panel.replaceChildren.apply(panel, parts);
  }

  editor.on('nodeSelected', function (id) {
    var data = blockData(id);
    if (isExternal(data)) { showExternal(id, data); return; }
    var idInput = document.createElement('input');
    idInput.value = data.id;
    idInput.addEventListener('input', function () {
      data.id = idInput.value.trim();
      editor.updateNodeDataFromId(id, data);
      setTitle(id, data.id);
    });
    var config = document.createElement('textarea');
    config.rows = 8;
    config.value = JSON.stringify(data.config || {}, null, 2);
    config.addEventListener('input', function () {
      try {
        data.config = JSON.parse(config.value || '{}');
        config.classList.remove('invalid');
        editor.updateNodeDataFromId(id, data);
      } catch (e) {
        config.classList.add('invalid');
      }
    });
    var isolation = document.createElement('select');
    ['SHARED', 'PROCESS'].forEach(function (v) { var opt = document.createElement('option'); opt.textContent = v; opt.selected = (data.isolation || 'SHARED') === v; isolation.append(opt); });
    isolation.addEventListener('change', function () { data.isolation = isolation.value; editor.updateNodeDataFromId(id, data); changed(); });
    var parts = [field('Block id', idInput), field('Isolation', isolation)];
    Object.keys(data.varArgCounts || {}).forEach(function (port) {
      var n = document.createElement('input');
      n.type = 'number';
      n.min = '1';
      n.value = data.varArgCounts[port];
      n.addEventListener('change', function () {
        var next = Math.max(1, parseInt(n.value, 10) || 1);
        n.value = next;
        var oldCounts = Object.assign({}, data.varArgCounts);
        data.varArgCounts[port] = next;
        rebuildNode(id, data, oldCounts);
      });
      parts.push(field('Slots of ' + port, n));
    });
    parts.push(field('Configuration (JSON)', config));
    panel.replaceChildren.apply(panel, parts);
  });

  // a new number of slots changes the ports of a node: it is drawn again and keeps the connections whose slot still exists
  function rebuildNode(oldId, data, oldCounts) {
    var node = editor.getNodeFromId(oldId);
    var before = { ins: slots(data.block, 'inputs', oldCounts), outs: slots(data.block, 'outputs', oldCounts) };
    var links = [];
    Object.keys(node.outputs).forEach(function (cls) {
      node.outputs[cls].connections.forEach(function (c) { links.push({ side: 'out', slot: cls, other: c.node, otherClass: c.output }); });
    });
    Object.keys(node.inputs).forEach(function (cls) {
      node.inputs[cls].connections.forEach(function (c) { links.push({ side: 'in', slot: cls, other: c.node, otherClass: c.input }); });
    });
    var x = node.pos_x, y = node.pos_y;
    var oldLabels = { out: {}, in: {} };
    Object.keys(node.outputs).forEach(function (cls, i) { oldLabels.out[cls] = (before.outs[i] || {}).label; });
    Object.keys(node.inputs).forEach(function (cls, i) { oldLabels.in[cls] = (before.ins[i] || {}).label; });
    rebuilding = true;
    editor.removeNodeId('node-' + oldId);
    var inSlots = slots(data.block, 'inputs', data.varArgCounts), outSlots = slots(data.block, 'outputs', data.varArgCounts);
    var created = editor.addNode(data.block, inSlots.length, outSlots.length, x, y, 'cringle-block', data, nodeHtml(data));
    links.forEach(function (l) {
      var label = oldLabels[l.side][l.slot];
      if (l.side === 'out') {
        var k = outSlots.findIndex(function (s2) { return s2.label === label; });
        if (k >= 0) editor.addConnection(created, l.other, 'output_' + (k + 1), l.otherClass);
      } else {
        var m = inSlots.findIndex(function (s2) { return s2.label === label; });
        if (m >= 0) editor.addConnection(l.other, created, l.otherClass, 'input_' + (m + 1));
      }
    });
    rebuilding = false;
    panel.replaceChildren();
    showHint();
    later();
  }
  editor.on('nodeUnselected', function () { panel.replaceChildren(); });
  editor.on('connectionSelected', function (c) {
    var key = edgeKey(c);
    var o = options[key] || (options[key] = { delivery: 'DROP', record: false });
    function number(label, holder, name, hint) {
      var input = document.createElement('input');
      input.type = 'number';
      input.min = '0';
      input.placeholder = hint || 'default';
      input.value = holder[name] === undefined ? '' : holder[name];
      input.addEventListener('input', function () {
        if (input.value === '') delete holder[name]; else holder[name] = parseInt(input.value, 10);
        changed();
      });
      return field(label, input);
    }
    var delivery = document.createElement('select');
    ['DROP', 'BUFFER'].forEach(function (v) { var opt = document.createElement('option'); opt.textContent = v; opt.selected = o.delivery === v; delivery.append(opt); });
    var record = document.createElement('input');
    record.type = 'checkbox';
    record.checked = !!o.record;
    record.addEventListener('change', function () { o.record = record.checked; changed(); });
    var title = document.createElement('p');
    title.textContent = key;
    var retryBox = document.createElement('div');
    function drawRetry() {
      if (o.delivery !== 'BUFFER') { delete o.retry; retryBox.replaceChildren(); return; }
      var r = o.retry || {};
      var backoff = document.createElement('select');
      ['', 'FIXED', 'EXPONENTIAL'].forEach(function (v) { var opt = document.createElement('option'); opt.value = v; opt.textContent = v || 'default (FIXED)'; opt.selected = (r.backoff || '') === v; backoff.append(opt); });
      backoff.addEventListener('change', function () { if (backoff.value === '') delete r.backoff; else r.backoff = backoff.value; store(); });
      function store() { if (Object.keys(r).length === 0) delete o.retry; else o.retry = r; changed(); }
      var group = document.createElement('fieldset');
      var legend = document.createElement('legend');
      legend.textContent = 'Retry';
      var wrap = function (label, name, hint) {
        var f = number(label, r, name, hint);
        f.addEventListener('input', store);
        return f;
      };
      group.append(legend, wrap('Maximum attempts', 'maxAttempts', 'unlimited'), wrap('Delay in ms', 'backoffMs', '50'), field('Growth of the delay', backoff), wrap('Longest delay in ms', 'maxBackoffMs', '5000'));
      retryBox.replaceChildren(group);
    }
    delivery.addEventListener('change', function () { o.delivery = delivery.value; drawRetry(); changed(); });
    drawRetry();
    panel.replaceChildren(title, field('Delivery', delivery), field('Record in the data warehouse', record), number('Buffer capacity', o, 'bufferCapacity'), number('Request timeout in ms', o, 'requestTimeoutMs'), retryBox);
  });

  // saving: the server turns the graph into the blueprint and checks it
  var save = document.getElementById('save-blueprint');
  var saving = false;
  var autosaveTimer = null;
  // everything that is saved: the graph, the options of the tethers and the fields below the editor (not the revision)
  function stateKey() {
    var v = values();
    delete v.revision;
    return JSON.stringify(v);
  }
  var lastSaved = null;
  function values() {
    return {
      graph: JSON.stringify(editor.export()), options: JSON.stringify(options),
      provides: document.getElementById('provides').value, fabrics: document.getElementById('fabrics').value,
      schemas: Array.prototype.filter.call(document.querySelectorAll('.schema-pick'), function (c) { return c.checked; }).map(function (c) { return c.value; }).join(','),
      version: document.getElementById('version').value, revision: document.getElementById('revision').value,
    };
  }
  lastSaved = stateKey();
  function saveNow() {
    if (!save || saving) return Promise.resolve();
    saving = true;
    var state = stateKey();
    return htmx.ajax('POST', host.dataset.saveUrl, { source: host, target: '#result', swap: 'morph:innerHTML', values: values() })
      .then(function () { lastSaved = state; })
      .finally(function () { saving = false; });
  }
  if (save) save.addEventListener('click', saveNow);

  // autosave 2 s after the last change, only when something differs from what was saved
  var autosave = document.getElementById('autosave');
  function changed() {
    if (!autosave || !autosave.checked) return;
    clearTimeout(autosaveTimer);
    autosaveTimer = setTimeout(function () {
      if (stateKey() !== lastSaved) saveNow();
    }, 2000);
  }
  ['version', 'fabrics', 'provides'].forEach(function (id) { document.getElementById(id).addEventListener('input', changed); });
  document.querySelectorAll('.schema-pick').forEach(function (c) { c.addEventListener('change', changed); });
  editor.on('nodeDataChanged', function () { changed(); });

  // publishing: save first, then publish the saved draft
  var publish = document.getElementById('publish-blueprint');
  if (publish) {
    publish.addEventListener('click', function () {
      var version = document.getElementById('version');
      window.cringleConfirm('Publish the blueprint?', 'The draft is saved, checked, and published as a package' + (version && version.value ? ' (version ' + version.value + ')' : '') + ' in the repository. Deploy it from the page Deployments.', 'Publish', false, function () {
        clearTimeout(autosaveTimer);
        saveNow().then(function () {
          var box = document.getElementById('result');
          if (box.querySelector('.error')) return;
          return htmx.ajax('POST', host.dataset.publishUrl, { source: host, target: '#result', swap: 'innerHTML' });
        });
      });
    });
  }

  // undo and redo from a stack of exports of the graph
  var history = [JSON.stringify(editor.export())], at = 0, snapTimer = null;
  function snapshot() {
    var now = JSON.stringify(editor.export());
    if (now === history[at]) return;
    history = history.slice(0, at + 1);
    history.push(now);
    if (history.length > 50) history.shift();
    at = history.length - 1;
    changed();
  }
  function later() { clearTimeout(snapTimer); snapTimer = setTimeout(snapshot, 300); }
  function restore(state) {
    editor.clear();
    editor.import(JSON.parse(state));
    showHint();
    panel.replaceChildren();
    changed();
  }
  ['nodeCreated', 'nodeRemoved', 'nodeMoved', 'nodeDataChanged'].forEach(function (name) { editor.on(name, later); });
  editor.on('connectionRemoved', function () { if (!rejecting) later(); });
  document.getElementById('undo').addEventListener('click', function () { snapshot(); if (at > 0) restore(history[--at]); });
  document.getElementById('redo').addEventListener('click', function () { if (at < history.length - 1) restore(history[++at]); });
  document.getElementById('zoom-in').addEventListener('click', function () { editor.zoom_in(); });
  document.getElementById('zoom-out').addEventListener('click', function () { editor.zoom_out(); });
  document.getElementById('zoom-fit').addEventListener('click', function () { editor.zoom_reset(); editor.canvas_x = 280; editor.canvas_y = 90; editor.zoom_refresh(); });
  editor.on('connectionCreated', function () { /* the verdict of the server decides, see above */ });
  window.cringleEditorSnapshot = snapshot; // the verdict handler snapshots an accepted connection
})();

// A fingerprint (code.fp, data-copy) is shown shortened; a click copies the whole value.
document.addEventListener('click', function (event) {
  var el = event.target.closest && event.target.closest('[data-copy]');
  if (!el || !navigator.clipboard) return;
  navigator.clipboard.writeText(el.getAttribute('data-copy')).then(function () {
    el.classList.add('copied');
    setTimeout(function () { el.classList.remove('copied'); }, 1200);
  });
});

// A login link (/login#token=...) fills in the token: it goes from the fragment into the form, and the fragment leaves the address bar first. It does
// not sign in by itself, the person confirms: a link sent by someone else would otherwise sign the person in as that someone (login CSRF).
(function () {
  var match = /^#token=([^&]+)$/.exec(location.hash);
  var form = document.querySelector('form[action="/login"]');
  if (!match || !form) return;
  history.replaceState(null, '', location.pathname + location.search);
  form.querySelector('[name=token]').value = decodeURIComponent(match[1]);
  var notice = document.createElement('p');
  notice.className = 'notice';
  notice.setAttribute('role', 'status');
  notice.textContent = 'This link brings a token. Sign in only if it comes from your administrator or from you: with a token from someone else you work in that person’s account.';
  form.insertBefore(notice, form.querySelector('label'));
  form.querySelector('button').focus();
})();

// Dialogs (formPanel and rowDialog in PageSupport.kt): a button with data-dialog="<id>" opens the modal <dialog>, data-close closes it, a click on the
// backdrop too. A request that starts in a dialog and fails shows its error in the dialog (which stays open with what was typed); one that works
// closes the dialog, and the result is in #flash as always.
(function () {
  function openDialog(dialog) {
    if (dialog.open) return;
    var error = dialog.querySelector('[data-dialog-error]');
    if (error) { error.hidden = true; error.textContent = ''; }
    if (typeof dialog.showModal === 'function') dialog.showModal(); else dialog.setAttribute('open', '');
    var first = dialog.querySelector('input:not([type=hidden]):not([type=checkbox]), select, textarea');
    if (first) first.focus();
  }
  document.addEventListener('click', function (event) {
    var target = event.target;
    if (!target || !target.closest) return;
    var opener = target.closest('[data-dialog]');
    if (opener) {
      var dialog = document.getElementById(opener.getAttribute('data-dialog'));
      if (dialog) openDialog(dialog);
      return;
    }
    var closer = target.closest('[data-close]');
    if (closer) {
      var own = closer.closest('dialog');
      if (own) own.close();
      return;
    }
    // the backdrop is part of the dialog: a click on the dialog itself (not on its content) is a click outside
    if (target.tagName === 'DIALOG' && target.classList.contains('dialog')) target.close();
  });
  // a closed dialog forgets what was typed (the list may refresh again then)
  document.addEventListener('close', function (event) {
    if (event.target.tagName === 'DIALOG') event.target.querySelectorAll('form').forEach(function (f) { f.reset(); });
  }, true);
  document.addEventListener('htmx:beforeSwap', function (event) {
    var request = event.detail.requestConfig;
    var dialog = request && request.elt && request.elt.closest ? request.elt.closest('dialog.dialog') : null;
    if (!dialog) return;
    var doc = new DOMParser().parseFromString(event.detail.serverResponse || '', 'text/html');
    var problem = doc.querySelector('.notice.error, p.error');
    if (problem) {
      problem.querySelectorAll('button').forEach(function (b) { b.remove(); });
      var box = dialog.querySelector('[data-dialog-error]');
      if (box) { box.textContent = problem.textContent.trim(); box.hidden = false; }
      event.detail.shouldSwap = false;
      return;
    }
    if (event.detail.xhr.status < 400) dialog.close();
  });
  // the questions of hx-confirm: a dialog of our own instead of the one of the browser
  var confirmDialog = null;
  function ensureConfirm() {
    if (confirmDialog) return confirmDialog;
    confirmDialog = document.createElement('dialog');
    confirmDialog.className = 'dialog small';
    confirmDialog.innerHTML = '<div class="dialog-head"><h2 data-confirm-title>Are you sure?</h2></div><div class="dialog-body"><p data-confirm-message></p>' +
      '<div class="dialog-actions"><button type="button" class="btn" data-confirm-cancel>Cancel</button><button type="button" class="btn primary" data-confirm-ok>Confirm</button></div></div>';
    document.body.append(confirmDialog);
    return confirmDialog;
  }
  // asks [message] in the dialog and calls [onYes] if the user confirms; [danger] colors the button
  window.cringleConfirm = function (title, message, label, danger, onYes) {
    var d = ensureConfirm();
    d.querySelector('[data-confirm-message]').textContent = message;
    d.querySelector('[data-confirm-title]').textContent = title;
    var ok = d.querySelector('[data-confirm-ok]'), cancel = d.querySelector('[data-confirm-cancel]');
    ok.textContent = label;
    ok.className = 'btn ' + (danger ? 'danger-solid' : 'primary');
    function done(yes) {
      ok.onclick = null; cancel.onclick = null; d.onclose = null;
      if (d.open) d.close();
      if (yes) onYes();
    }
    ok.onclick = function () { done(true); };
    cancel.onclick = function () { done(false); };
    d.onclose = function () { done(false); };
    d.showModal();
    ok.focus();
  };
  document.addEventListener('htmx:confirm', function (event) {
    if (!event.detail.question) return;
    event.preventDefault();
    var label = (event.detail.elt && event.detail.elt.textContent ? event.detail.elt.textContent.trim() : '') || 'Confirm';
    var short = label.length < 24;
    window.cringleConfirm(short ? label + '?' : 'Are you sure?', event.detail.question, short ? label : 'Confirm', /delete|revoke|remove|undeploy|untrust|stop/i.test(label), function () { event.detail.issueRequest(true); });
  });
})();

// The form of the schema editor (SchemaPages.editor): Alpine holds the model {namespace, version, types: [{name, kind, fields, values}]}; the server turns it
// into the schema document and checks it (SchemaForm). `open` (is the card unfolded) is only for the page, the server ignores it.
window.schemaEditor = function (model) {
  var standard = ['cringle.std/String', 'cringle.std/Boolean', 'cringle.std/Int', 'cringle.std/Double', 'cringle.std/Bytes', 'cringle.std/Timestamp', 'cringle.std/Empty', 'cringle.std/Error'];
  var name = /^[A-Z][A-Za-z0-9]*$/;
  return Object.assign(model, {
    newName: '', newKind: 'record', newError: '',
    init: function () {
      this.types.forEach(function (t) { t.open = false; });
      if (this.types.length <= 2) this.types.forEach(function (t) { t.open = true; });
    },
    changed: function () { this.$dispatch('cringle-changed'); },
    validName: function (n) { return name.test(n); },
    typeOptions: function () {
      var ns = (this.namespace || '').trim();
      var own = this.types.filter(function (t) { return t.name; }).map(function (t) { return ns ? ns + '/' + t.name : t.name; });
      return standard.concat(own.filter(function (o, i) { return own.indexOf(o) === i; }));
    },
    summary: function (t) {
      if (t.kind === 'enum') { var n = this.valueList(t).length; return n + (n === 1 ? ' value' : ' values'); }
      return t.fields.length + (t.fields.length === 1 ? ' field' : ' fields');
    },
    valueList: function (t) { return (t.values || '').split(',').map(function (v) { return v.trim(); }).filter(function (v) { return v; }); },
    addValue: function (t, event) {
      var input = event.target, v = (input.value || '').replace(/,/g, '').trim();
      input.value = '';
      if (!v) return;
      var list = this.valueList(t);
      if (list.indexOf(v) < 0) list.push(v);
      t.values = list.join(', ');
      this.changed();
    },
    removeValue: function (t, i) { var list = this.valueList(t); list.splice(i, 1); t.values = list.join(', '); this.changed(); },
    addField: function (t) { t.fields.push({ name: '', type: 'cringle.std/String', wrap: '' }); this.changed(); },
    removeField: function (t, i) { t.fields.splice(i, 1); this.changed(); },
    removeType: function (i) { this.types.splice(i, 1); this.changed(); },
    openNew: function () {
      this.newName = ''; this.newKind = 'record'; this.newError = '';
      this.$refs.newType.showModal();
      var input = this.$refs.newName;
      setTimeout(function () { input.focus(); }, 0);
    },
    createType: function () {
      var n = (this.newName || '').trim();
      if (!name.test(n)) { this.newError = 'The name starts with a capital letter and has letters and digits only.'; return; }
      if (this.types.some(function (t) { return t.name === n; })) { this.newError = 'There is a type ' + n + ' already.'; return; }
      this.types.push({ name: n, kind: this.newKind, fields: this.newKind === 'record' ? [{ name: '', type: 'cringle.std/String', wrap: '' }] : [], values: '', open: true });
      this.$refs.newType.close();
      this.changed();
    },
  });
};
