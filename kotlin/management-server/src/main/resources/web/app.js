// Own helpers of the WebUI; the page logic is in htmx attributes and Alpine x-data.
// The lists refresh themselves (hx-trigger "every 5s [cringleIdle()]"), morphing the new HTML into the old. They wait while the user is working in a
// list: a field has the focus, a <details> is open, or a field differs from what it had when it was rendered (text typed, an option changed).
window.cringleIdle = function () {
  var list = document.getElementById('list');
  if (!list) return true;
  if (document.querySelector('.htmx-request:not(#list)')) return false; // a request of the user is running
  var active = document.activeElement;
  if (active && list.contains(active) && /^(INPUT|TEXTAREA|SELECT)$/.test(active.tagName)) return false;
  if (list.querySelector('details[open]')) return false;
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
  function portName(blockType, kind, index) {
    var p = byBlock[blockType];
    return p ? p[kind][index - 1] : '?';
  }
  function edgeKey(c) {
    var from = blockData(c.output_id), to = blockData(c.input_id);
    return from.id + '.' + portName(from.block, 'outputs', parseInt(c.output_class.replace('output_', ''), 10)) + '>' +
      to.id + '.' + portName(to.block, 'inputs', parseInt(c.input_class.replace('input_', ''), 10));
  }
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
      if (p.varArg) { say('Blocks with VarArg ports cannot be used in the editor yet.', true); return; }
      var id = uniqueId(p.title);
      var data = { id: id, block: p.block, config: {} };
      var html = '<div class="node-title">' + esc(id) + '</div><small>' + esc(p.block) + '</small><small class="ports">in: ' + esc(p.inputs.join(', ')) + ' | out: ' + esc(p.outputs.join(', ')) + '</small>';
      var count = Object.keys(editor.export().drawflow.Home.data).length;
      editor.addNode(p.block, p.inputs.length, p.outputs.length, 40 + 220 * (count % 4), 40 + 140 * Math.floor(count / 4), 'cringle-block', data, html);
    });
  });

  var rejecting = false;
  // the server decides whether a connection is allowed
  editor.on('connectionCreated', function (c) {
    var from = blockData(c.output_id), to = blockData(c.input_id);
    var body = new URLSearchParams({
      fromBlock: from.block, fromOutput: c.output_class.replace('output_', ''),
      toBlock: to.block, toInput: c.input_class.replace('input_', ''),
    });
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
  editor.on('nodeSelected', function (id) {
    var data = blockData(id);
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
    panel.replaceChildren(field('Block id', idInput), field('Configuration (JSON)', config));
  });
  editor.on('nodeUnselected', function () { panel.replaceChildren(); });
  editor.on('connectionSelected', function (c) {
    var key = edgeKey(c);
    var o = options[key] || (options[key] = { delivery: 'DROP', record: false });
    var delivery = document.createElement('select');
    ['DROP', 'BUFFER'].forEach(function (v) { var opt = document.createElement('option'); opt.textContent = v; opt.selected = o.delivery === v; delivery.append(opt); });
    delivery.addEventListener('change', function () { o.delivery = delivery.value; changed(); });
    var record = document.createElement('input');
    record.type = 'checkbox';
    record.checked = !!o.record;
    record.addEventListener('change', function () { o.record = record.checked; changed(); });
    var title = document.createElement('p');
    title.textContent = key;
    panel.replaceChildren(title, field('Delivery', delivery), field('Record in the data warehouse', record));
  });

  // saving: the server turns the graph into the blueprint and checks it
  var save = document.getElementById('save-blueprint');
  var saving = false;
  var autosaveTimer = null;
  var lastSaved = JSON.stringify(editor.export()) + JSON.stringify(options);
  function values() {
    return {
      graph: JSON.stringify(editor.export()), options: JSON.stringify(options),
      provides: document.getElementById('provides').value, roles: document.getElementById('roles').value,
      version: document.getElementById('version').value, revision: document.getElementById('revision').value,
    };
  }
  function saveNow() {
    if (!save || saving) return Promise.resolve();
    saving = true;
    var state = JSON.stringify(editor.export()) + JSON.stringify(options);
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
      if (JSON.stringify(editor.export()) + JSON.stringify(options) !== lastSaved) saveNow();
    }, 2000);
  }
  ['version', 'roles', 'provides'].forEach(function (id) { document.getElementById(id).addEventListener('input', changed); });
  editor.on('nodeDataChanged', function () { changed(); });

  // publishing: save first, then publish the saved draft
  var publish = document.getElementById('publish-blueprint');
  if (publish) {
    publish.addEventListener('click', function () {
      clearTimeout(autosaveTimer);
      saveNow().then(function () {
        var box = document.getElementById('result');
        if (box.querySelector('.error')) return;
        return htmx.ajax('POST', host.dataset.publishUrl, { source: host, target: '#result', swap: 'innerHTML' });
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
  document.getElementById('zoom-fit').addEventListener('click', function () { editor.zoom_reset(); });
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
