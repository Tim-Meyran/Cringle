// Own helpers of the WebUI; the page logic is in htmx attributes and Alpine x-data.
// The lists refresh themselves (hx-trigger "every 5s [cringleIdle()]"), morphing the new HTML into the old. They wait while the user is working in a
// list: a field has the focus, a <details> is open, or a field differs from what it had when it was rendered (text typed, an option changed).
window.cringleIdle = function () {
  var list = document.getElementById('list');
  if (!list) return true;
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
// the hint "refresh paused while you edit" follows the same rule
setInterval(function () {
  var hint = document.getElementById('paused');
  if (hint) hint.hidden = window.cringleIdle();
}, 1000);

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
        } else {
          editor.removeSingleConnection(c.output_id, c.input_id, c.output_class, c.input_class);
          say('Not connected: ' + verdict.message, true);
        }
      })
      .catch(function () {
        editor.removeSingleConnection(c.output_id, c.input_id, c.output_class, c.input_class);
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
    delivery.addEventListener('change', function () { o.delivery = delivery.value; });
    var record = document.createElement('input');
    record.type = 'checkbox';
    record.checked = !!o.record;
    record.addEventListener('change', function () { o.record = record.checked; });
    var title = document.createElement('p');
    title.textContent = key;
    panel.replaceChildren(title, field('Delivery', delivery), field('Record in the data warehouse', record));
  });

  // saving: the server turns the graph into the blueprint and checks it
  var save = document.getElementById('save-blueprint');
  if (save) {
    save.addEventListener('click', function () {
      htmx.ajax('POST', host.dataset.saveUrl, {
        source: host, target: '#result', swap: 'morph:innerHTML',
        values: {
          graph: JSON.stringify(editor.export()), options: JSON.stringify(options),
          provides: document.getElementById('provides').value, roles: document.getElementById('roles').value,
          version: document.getElementById('version').value, revision: document.getElementById('revision').value,
        },
      });
    });
  }
})();
