// The navigation can be folded away (button.nav-toggle): the class nav-collapsed on <html> narrows the sidebar to its icons; the state is kept in this browser.
(function () {
  var root = document.documentElement, key = 'cringle.nav', button = document.querySelector('.nav-toggle');
  if (!button) return;
  function show(collapsed) {
    root.classList.toggle('nav-collapsed', collapsed);
    var label = collapsed ? 'Unfold the navigation' : 'Fold the navigation away';
    button.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
    button.setAttribute('aria-label', label);
    button.title = label;
  }
  var collapsed = false;
  try { collapsed = window.localStorage.getItem(key) === 'collapsed'; } catch (e) { /* no storage: the state is not kept */ }
  show(collapsed);
  button.addEventListener('click', function () {
    collapsed = !root.classList.contains('nav-collapsed');
    show(collapsed);
    try { window.localStorage.setItem(key, collapsed ? 'collapsed' : 'expanded'); } catch (e) { /* not kept */ }
    // the canvas of the editor and the monitor take the new width
    window.dispatchEvent(new Event('resize'));
  });
})();

// Own helpers of the WebUI; the page logic is in htmx attributes and Alpine x-data.
// The lists refresh themselves (hx-trigger "every 5s [cringleIdle()]"), morphing the new HTML into the old. They wait while the user is working in a
// list: a field has the focus, a <details> is open, or a field differs from what it had when it was rendered (text typed, an option changed).
// A text that is cut off (hidden overflow, ellipsis) shows itself whole as a tooltip: looked at when the pointer enters it or an element inside it.
document.addEventListener('mouseover', function (event) {
  var el = event.target;
  for (var depth = 0; el && el !== document.body && depth < 4; depth++, el = el.parentElement) {
    if (el.title && !el.hasAttribute('data-auto-tip')) return;
    var style = getComputedStyle(el);
    var clips = style.overflowX === 'hidden' || style.overflowX === 'clip';
    var cut = clips && el.scrollWidth > el.clientWidth + 1;
    if (cut) {
      el.title = el.textContent.replace(/\s+/g, ' ').trim();
      el.setAttribute('data-auto-tip', '');
      return;
    }
    if (el.hasAttribute('data-auto-tip')) { el.removeAttribute('title'); el.removeAttribute('data-auto-tip'); }
  }
});

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
  var schemaNames = JSON.parse(host.dataset.schemas || '[]');
  var byBlock = {};
  palette.forEach(function (p) { byBlock[p.block] = p; });
  var editor = new Drawflow(document.getElementById('drawflow'));
  editor.reroute = false;
  editor.start();
  var graph = JSON.parse(host.dataset.graph);
  if (Object.keys(graph.drawflow.Home.data).length > 0) editor.import(graph);
  setTimeout(function () { labelAll(); }, 0);
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
  function slots(blockType, kind, counts, config) {
    var p = byBlock[blockType], out = [];
    ((p && p[kind]) || []).forEach(function (port) {
      if (port.varArg) {
        var n = Math.max(1, (counts && counts[port.name]) || 1);
        // the slots of a port that the configuration names carry the names (the label stays name[index]: it identifies the slot)
        var names = port.namesFrom && config && Array.isArray(config[port.namesFrom]) ? config[port.namesFrom] : [];
        for (var i = 0; i < n; i++) out.push({ port: port.name, index: i, label: port.name + '[' + i + ']', title: names[i] || null });
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
    return '<div class="node-title">' + esc(data.id) + '</div><small>' + esc(data.block) + '</small>';
  }
  // every port of a node gets its label beside the dot (the tooltip adds the type); a node is as high as its ports need
  // the type of every port of a block: a port of a type parameter has the type the blueprint bound (otherwise the bound of the parameter)
  function portTypes(blockType, kind, bound) {
    var types = {};
    ((byBlock[blockType] || {})[kind] || []).forEach(function (port) { types[port.name] = (port.typeParam && bound && bound[port.typeParam]) || port.schema; });
    return types;
  }
  function typeName(type) { return type.substring(type.indexOf('/') + 1); }
  // the response type of a request/response port (the request type is the type of the port)
  function portResponses(blockType, kind) {
    var types = {};
    ((byBlock[blockType] || {})[kind] || []).forEach(function (port) { if (port.responseSchema) types[port.name] = port.responseSchema; });
    return types;
  }
  // the tether types of the ports of a block by name
  function portKindMap(blockType, kind) {
    var kinds = {};
    ((byBlock[blockType] || {})[kind] || []).forEach(function (port) { kinds[port.name] = port.tetherTypes || []; });
    return kinds;
  }
  function labelPorts(list, ports, types, responses, tetherKinds) {
    Array.prototype.forEach.call(ports, function (port, i) {
      var old = port.querySelector('.port-label');
      if (old) old.remove();
      var slot = list[i];
      var text = slot ? (slot.title || slot.label) : 'port ' + (i + 1);
      var span = document.createElement('span');
      span.className = 'port-label';
      var type = slot && types ? types[slot.port] : null;
      var response = slot && responses ? responses[slot.port] : null;
      span.textContent = type ? text + ': ' + typeName(type) + (response ? ' \u2192 ' + typeName(response) : '') : text;
      port.append(span);
      var kinds = (slot && tetherKinds && tetherKinds[slot.port]) || [];
      port.classList.toggle('pull-only', kinds.indexOf('PULL') >= 0 && kinds.indexOf('MESSAGE') < 0);
      port.classList.toggle('pull-capable', kinds.indexOf('PULL') >= 0 && kinds.indexOf('MESSAGE') >= 0);
      port.title = type ? text + ' (' + type + (response ? ' \u2192 ' + response : '') + ')' : text;
    });
  }
  function labelNode(number) {
    var el = document.getElementById('node-' + number);
    if (!el) return;
    var data = editor.getNodeFromId(number).data;
    var ins, outs, inTypes = null, outTypes = null, inResponses = null, outResponses = null;
    if (data.kind === 'external') {
      var name = data.mode === 'service' ? 'service' : (data.send ? 'to remote' : 'from remote');
      ins = data.send ? [{ label: name }] : [];
      outs = data.send ? [] : [{ label: name }];
    } else {
      ins = slots(data.block, 'inputs', data.varArgCounts, data.config);
      outs = slots(data.block, 'outputs', data.varArgCounts, data.config);
      inTypes = portTypes(data.block, 'inputs', data.types);
      outTypes = portTypes(data.block, 'outputs', data.types);
      inResponses = portResponses(data.block, 'inputs');
      outResponses = portResponses(data.block, 'outputs');
    }
    labelPorts(ins, el.querySelectorAll('.inputs .input'), inTypes, inResponses, data.kind === 'external' ? null : portKindMap(data.block, 'inputs'));
    labelPorts(outs, el.querySelectorAll('.outputs .output'), outTypes, outResponses, data.kind === 'external' ? null : portKindMap(data.block, 'outputs'));
    el.style.minHeight = (Math.max(ins.length, outs.length, 1) * 25 + 72) + 'px';
    // the widest input label and the widest output label must fit side by side
    var widest = function (selector) {
      return Array.prototype.reduce.call(el.querySelectorAll(selector), function (w, span) { return Math.max(w, span.offsetWidth); }, 0);
    };
    el.style.minWidth = Math.max(200, widest('.input .port-label') + widest('.output .port-label') + 72) + 'px';
  }
  function labelAll() {
    Object.keys(editor.export().drawflow.Home.data).forEach(labelNode);
    decorateConnections();
  }
  // a tether is drawn by its kind (the first type that both ports support, as the server decides): push, request/response, pull (get), stream
  var TYPE_ORDER = ['MESSAGE', 'REQUEST_RESPONSE', 'PULL', 'STREAM', 'BYTE_STREAM', 'TCP', 'SERIAL'];
  function portKinds(data, kind, className) {
    var slot = slots(data.block, kind, data.varArgCounts)[parseInt(className.replace(kind === 'outputs' ? 'output_' : 'input_', ''), 10) - 1];
    var def = slot && ((byBlock[data.block] || {})[kind] || []).filter(function (p) { return p.name === slot.port; })[0];
    return def && def.tetherTypes ? def.tetherTypes : [];
  }
  // the types that the two ports of a connection share, the first one is the default
  function commonKinds(from, outClass, to, inClass) {
    var out = portKinds(from, 'outputs', outClass), inn = portKinds(to, 'inputs', inClass);
    return TYPE_ORDER.filter(function (t) { return out.indexOf(t) >= 0 && inn.indexOf(t) >= 0; });
  }
  function decorateConnections() {
    var nodes = editor.export().drawflow.Home.data;
    Object.keys(nodes).forEach(function (id) {
      var from = nodes[id].data;
      if (!from || isExternal(from)) return;
      Object.keys(nodes[id].outputs).forEach(function (outClass) {
        nodes[id].outputs[outClass].connections.forEach(function (c) {
          var to = nodes[c.node] && nodes[c.node].data;
          if (!to || isExternal(to)) return;
          var common = commonKinds(from, outClass, to, c.output);
          var chosen = (options[edgeKey({ output_id: id, input_id: c.node, output_class: outClass, input_class: c.output }) ] || {}).type;
          var type = common.indexOf(chosen) >= 0 ? chosen : common[0];
          var el = document.querySelector('#drawflow .connection.node_out_node-' + id + '.node_in_node-' + c.node + '.' + outClass + '.' + c.output);
          if (el && type) el.dataset.type = type;
        });
      });
    });
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
    editor.addNode('external', send ? 1 : 0, send ? 0 : 1, 40 + 280 * (count % 4), 40 + 140 * Math.floor(count / 4), 'cringle-external', data, externalHtml(data));
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
  editor.on('nodeCreated', function (number) { labelNode(number); });
  editor.on('nodeRemoved', showHint);
  showHint();

  // the search field of the palette shows the matching blocks, opens the themes that have one and hides the themes and plugins that have none
  var search = document.getElementById('palette-search');
  if (search) {
    search.addEventListener('input', function () {
      var q = search.value.trim().toLowerCase();
      function filter(buttons) {
        var any = false;
        buttons.forEach(function (b) {
          b.hidden = q !== '' && b.dataset.block.toLowerCase().indexOf(q) < 0;
          if (!b.hidden) any = true;
        });
        return any;
      }
      document.querySelectorAll('#palette section.plugin').forEach(function (section) {
        var anyInPlugin = filter(Array.prototype.filter.call(section.children, function (el) { return el.tagName === 'BUTTON'; }));
        section.querySelectorAll('details.theme').forEach(function (theme) {
          var any = filter(Array.prototype.slice.call(theme.querySelectorAll('button[data-block]')));
          theme.hidden = !any;
          theme.open = q !== '' && any;
          if (any) anyInPlugin = true;
        });
        section.hidden = !anyInPlugin;
      });
    });
  }

  // adding a block from the palette
  document.querySelectorAll('#palette button').forEach(function (button) {
    button.addEventListener('click', function () {
      var p = byBlock[button.dataset.block];
      if (!p) return;
      var id = uniqueId(p.title);
      var data = { id: id, block: p.block, config: {}, isolation: 'SHARED', varArgCounts: defaultCounts(p.block), types: {} };
      var count = Object.keys(editor.export().drawflow.Home.data).length;
      editor.addNode(p.block, slots(p.block, 'inputs', data.varArgCounts).length, slots(p.block, 'outputs', data.varArgCounts).length,
        40 + 280 * (count % 4), 40 + 140 * Math.floor(count / 4), 'cringle-block', data, nodeHtml(data));
    });
  });

  var rejecting = false;
  // the blocks of a refused connection are marked for a few seconds
  function flashNodes(numbers) {
    var els = numbers.map(function (n) { return document.getElementById('node-' + n); }).filter(Boolean);
    els.forEach(function (el) { el.classList.add('node-error'); });
    setTimeout(function () { els.forEach(function (el) { el.classList.remove('node-error'); }); }, 4000);
  }
  // the server decides whether a connection is allowed
  var rebuilding = false;
  // generics: a port of a type parameter that is not bound yet takes the type of the port it is connected to (an unbound `T` is `Any`, that connects to nothing typed)
  function genericPort(data, kind, className) {
    var slot = slots(data.block, kind, data.varArgCounts)[parseInt(className.replace(kind === 'outputs' ? 'output_' : 'input_', ''), 10) - 1];
    if (!slot) return null;
    var def = ((byBlock[data.block] || {})[kind] || []).filter(function (p) { return p.name === slot.port; })[0];
    return { name: slot.port, parameter: def && def.typeParam };
  }
  function inferTypes(c, from, to) {
    var done = [];
    if (isExternal(from) || isExternal(to)) return done;
    var out = genericPort(from, 'outputs', c.output_class), inn = genericPort(to, 'inputs', c.input_class);
    var outType = portTypes(from.block, 'outputs', from.types)[out && out.name], inType = portTypes(to.block, 'inputs', to.types)[inn && inn.name];
    var bind = function (id, data, port, type) {
      if (!port || !port.parameter || (data.types || {})[port.parameter] || !type || type === 'cringle.std/Any') return;
      data.types = Object.assign({}, data.types);
      data.types[port.parameter] = type;
      editor.updateNodeDataFromId(id, data);
      labelNode(id);
      done.push({ id: id, data: data, parameter: port.parameter });
    };
    bind(c.output_id, from, out, inType);
    bind(c.input_id, to, inn, outType);
    return done;
  }
  function undoInferred(done) {
    done.forEach(function (d) { delete d.data.types[d.parameter]; editor.updateNodeDataFromId(d.id, d.data); labelNode(d.id); });
  }
  editor.on('connectionCreated', function (c) {
    if (rebuilding) return;
    var from = blockData(c.output_id), to = blockData(c.input_id);
    var inferred = inferTypes(c, from, to);
    var body = new URLSearchParams({
      fromExternal: isExternal(from) ? from.type : undefined, toExternal: isExternal(to) ? to.type : undefined,
      fromBlock: from.block, fromOutput: c.output_class.replace('output_', ''), fromCounts: JSON.stringify(from.varArgCounts || {}),
      toBlock: to.block, toInput: c.input_class.replace('input_', ''), toCounts: JSON.stringify(to.varArgCounts || {}),
      fromTypes: JSON.stringify(from.types || {}), toTypes: JSON.stringify(to.types || {}),
      plugins: document.getElementById('plugins').value,
    });
    ['fromExternal', 'toExternal'].forEach(function (k) { if (body.get(k) === 'undefined') body.delete(k); });
    ['fromBlock', 'toBlock'].forEach(function (k) { if (body.get(k) === 'undefined') body.delete(k); });
    fetch(host.dataset.checkUrl, { method: 'POST', headers: { 'X-CSRF-Token': csrf(), 'Content-Type': 'application/x-www-form-urlencoded' }, body: body })
      .then(function (r) { return r.json(); })
      .then(function (verdict) {
        if (verdict.ok) {
          decorateConnections();
          say('Connected as a ' + verdict.type + ' tether.' + (inferred.length ? ' Type ' + inferred.map(function (d) { return d.parameter + ' = ' + typeName(d.data.types[d.parameter]); }).join(', ') + '.' : ''), false);
          window.cringleEditorSnapshot();
        } else {
          undoInferred(inferred);
          rejecting = true;
          editor.removeSingleConnection(c.output_id, c.input_id, c.output_class, c.input_class);
          rejecting = false;
          say('Not connected: ' + verdict.message, true);
          flashNodes([c.output_id, c.input_id]);
        }
      })
      .catch(function () {
        undoInferred(inferred);
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
  // one field of the configuration form: [f] is {name, kind, optional, values}; an empty optional field is absent from the configuration
  function configField(f, id, data, jsonArea) {
    function store(value) {
      data.config = data.config || {};
      if (value === undefined) delete data.config[f.name]; else data.config[f.name] = value;
      editor.updateNodeDataFromId(id, data);
      jsonArea.value = JSON.stringify(data.config, null, 2);
      jsonArea.classList.remove('invalid');
      changed();
    }
    var current = (data.config || {})[f.name];
    var control;
    var hint = f.description || '';
    if (f.kind === 'boolean') {
      control = document.createElement('input');
      control.type = 'checkbox';
      control.checked = current === true;
      control.addEventListener('change', function () { store(control.checked); });
    } else if (f.kind === 'stringlist') {
      // one name per line; the slots of the ports that these names name follow the list
      control = document.createElement('textarea');
      control.rows = Math.max(2, Array.isArray(current) ? current.length + 1 : 2);
      control.placeholder = 'one name per line';
      control.value = Array.isArray(current) ? current.join('\n') : '';
      var listMessage = document.createElement('small');
      listMessage.className = 'cfg-error';
      listMessage.hidden = true;
      var lines = function () { return control.value.split('\n').map(function (l) { return l.trim(); }).filter(function (l) { return l !== ''; }); };
      var listProblem = function (names) {
        for (var i = 0; i < names.length; i++) {
          if (f.pattern !== undefined && !new RegExp('^(?:' + f.pattern + ')$').test(names[i])) return '"' + names[i] + '": ' + (f.description || 'not a valid name');
          if (names.indexOf(names[i]) !== i) return '"' + names[i] + '" is used twice';
        }
        return '';
      };
      control.addEventListener('input', function () {
        var names = lines();
        var problem = listProblem(names);
        control.classList.toggle('invalid', problem !== '');
        listMessage.textContent = problem;
        listMessage.hidden = problem === '';
        store(names.length === 0 && f.optional ? undefined : names);
        labelNode(id);
      });
      control.addEventListener('change', function () {
        var names = lines();
        var oldCounts = Object.assign({}, data.varArgCounts), moved = false;
        (((byBlock[data.block] || {}).inputs || []).concat((byBlock[data.block] || {}).outputs || [])).forEach(function (port) {
          if (port.varArg && port.namesFrom === f.name && Math.max(1, names.length) !== Math.max(1, data.varArgCounts[port.name] || 1)) {
            data.varArgCounts[port.name] = Math.max(1, names.length);
            moved = true;
          }
        });
        if (moved) rebuildNode(id, data, oldCounts);
      });
      return fieldBox(f, control, f.description || 'one name per line: every name is a slot of the block', listMessage);
    } else if (f.kind === 'enum') {
      control = document.createElement('select');
      var none = document.createElement('option');
      none.value = '';
      none.textContent = f.optional ? '(not set)' : '(choose)';
      control.append(none);
      (f.values || []).forEach(function (v) {
        var opt = document.createElement('option');
        opt.value = v;
        opt.textContent = v;
        opt.selected = current === v;
        control.append(opt);
      });
      control.addEventListener('change', function () { store(control.value === '' ? undefined : control.value); });
    } else if (f.kind === 'json') {
      control = document.createElement('textarea');
      control.rows = 3;
      control.value = current === undefined ? '' : JSON.stringify(current);
      control.addEventListener('input', function () {
        if (control.value.trim() === '') { control.classList.remove('invalid'); store(undefined); return; }
        try {
          var parsed = JSON.parse(control.value);
          control.classList.remove('invalid');
          store(parsed);
        } catch (e) {
          control.classList.add('invalid');
        }
      });
    } else {
      control = document.createElement('input');
      if (f.kind === 'int' || f.kind === 'double') {
        control.type = 'number';
        control.step = f.kind === 'int' ? '1' : 'any';
        if (f.minimum !== undefined) control.min = f.minimum;
        if (f.maximum !== undefined) control.max = f.maximum;
        if (!hint && (f.minimum !== undefined || f.maximum !== undefined)) hint = (f.minimum !== undefined ? f.minimum : '...') + ' to ' + (f.maximum !== undefined ? f.maximum : '...');
      }
      if (f.maxLength !== undefined) control.maxLength = f.maxLength;
      control.value = current === undefined ? '' : current;
      var message = document.createElement('small');
      message.className = 'cfg-error';
      message.hidden = true;
      // the same checks as the server makes (schema constraints), shown at once at the field
      var validate = function () {
        var problem = '';
        var v = control.value;
        if (v === '') {
          problem = f.optional ? '' : 'required';
        } else if (f.kind === 'string') {
          if (f.minLength !== undefined && v.length < f.minLength) problem = 'at least ' + f.minLength + ' characters';
          else if (f.pattern !== undefined && !new RegExp('^(?:' + f.pattern + ')$').test(v)) problem = f.description || 'not a valid value';
        } else {
          var n = Number(v);
          if (f.minimum !== undefined && n < f.minimum) problem = 'at least ' + f.minimum;
          else if (f.maximum !== undefined && n > f.maximum) problem = 'at most ' + f.maximum;
          else if (f.kind === 'int' && n !== Math.floor(n)) problem = 'a whole number';
        }
        control.classList.toggle('invalid', problem !== '');
        message.textContent = problem;
        message.hidden = problem === '';
      };
      control.addEventListener('input', function () {
        validate();
        if (control.value === '') { store(undefined); return; }
        if (f.kind === 'int') store(parseInt(control.value, 10));
        else if (f.kind === 'double') store(parseFloat(control.value));
        else store(control.value);
      });
      if (control.value !== '') validate();
      return fieldBox(f, control, hint, message);
    }
    return fieldBox(f, control, hint, null);
  }
  // a field of the configuration form: the name (with * for a required one), the control, what a valid value is, and the problem with the value
  function fieldBox(f, control, hint, message) {
    var wrap = document.createElement('div');
    wrap.className = 'cfg-field' + (f.kind === 'boolean' ? ' cfg-check' : '');
    var label = document.createElement('label');
    if (f.kind === 'boolean') label.append(control, ' ' + f.name); else label.append(f.name + (f.optional ? '' : ' *'), control);
    wrap.append(label);
    if (hint) {
      var small = document.createElement('small');
      small.className = 'cfg-hint';
      small.textContent = hint;
      wrap.append(small);
    }
    if (message) wrap.append(message);
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
        changed();
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
    // generics: one select per type parameter of the block
    ((byBlock[data.block] || {}).typeParameters || []).forEach(function (parameter) {
      var select = document.createElement('select');
      var first = document.createElement('option');
      first.value = '';
      first.textContent = typeName(parameter.bound) + ' (bound)';
      select.append(first);
      schemaNames.forEach(function (name) {
        var opt = document.createElement('option');
        opt.value = name;
        opt.textContent = name;
        opt.selected = ((data.types || {})[parameter.name] || '') === name;
        select.append(opt);
      });
      select.addEventListener('change', function () {
        data.types = Object.assign({}, data.types);
        if (select.value) data.types[parameter.name] = select.value; else delete data.types[parameter.name];
        editor.updateNodeDataFromId(id, data);
        labelNode(id);
        changed();
      });
      parts.push(field('Type ' + parameter.name, select));
    });
    // a form for the fields of the configuration type of the block; the JSON below stays and shows the same
    var fields = (byBlock[data.block] || {}).configFields || [];
    if (fields.length > 0) {
      var form = document.createElement('div');
      form.className = 'config-form';
      fields.forEach(function (f) { form.append(configField(f, id, data, config)); });
      parts.push(form);
      var folded = document.createElement('details');
      var summary = document.createElement('summary');
      summary.textContent = 'Configuration as JSON';
      folded.append(summary, config);
      parts.push(folded);
    } else {
      parts.push(field('Configuration (JSON)', config));
    }
    panel.replaceChildren.apply(panel, parts);
  });

  // a new number of slots changes the ports of a node: it is drawn again and keeps the connections whose slot still exists
  function rebuildNode(oldId, data, oldCounts) {
    var node = editor.getNodeFromId(oldId);
    var before = { ins: slots(data.block, 'inputs', oldCounts, data.config), outs: slots(data.block, 'outputs', oldCounts, data.config) };
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
    var inSlots = slots(data.block, 'inputs', data.varArgCounts, data.config), outSlots = slots(data.block, 'outputs', data.varArgCounts, data.config);
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
    // two ports that share several tether types (a function block that pushes and can be asked): the kind is a choice of this connection
    var kinds = [];
    if (!isExternal(blockData(c.output_id)) && !isExternal(blockData(c.input_id))) kinds = commonKinds(blockData(c.output_id), c.output_class, blockData(c.input_id), c.input_class);
    var kind = document.createElement('select');
    kinds.forEach(function (t) {
      var opt = document.createElement('option');
      opt.value = t;
      opt.textContent = { MESSAGE: 'message (push)', PULL: 'pull (get): the receiver asks', REQUEST_RESPONSE: 'request / response' }[t] || t;
      opt.selected = (o.type || kinds[0]) === t;
      kind.append(opt);
    });
    kind.addEventListener('change', function () {
      if (kind.value === kinds[0]) delete o.type; else o.type = kind.value;
      decorateConnections();
      changed();
    });
    panel.replaceChildren(title);
    if (kinds.length > 1) panel.append(field('Kind of tether', kind));
    panel.append(field('Delivery', delivery), field('Record in the data warehouse', record), number('Buffer capacity', o, 'bufferCapacity'), number('Request timeout in ms', o, 'requestTimeoutMs'), retryBox);
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
      provides: document.getElementById('provides').value, fabrics: document.getElementById('fabrics').value, plugins: document.getElementById('plugins').value,
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
      .then(function () { lastSaved = state; markProblems(); })
      .finally(function () { saving = false; });
  }
  if (save) save.addEventListener('click', saveNow);

  // blocks with a problem: marked with the class node-error; a click on a problem selects the block and shows it in the middle of the canvas
  function nodeNumber(blockId) {
    var nodes = editor.export().drawflow.Home.data;
    var keys = Object.keys(nodes);
    for (var i = 0; i < keys.length; i++) if (nodes[keys[i]].data.id === blockId) return keys[i];
    return null;
  }
  function clearErrors() {
    document.querySelectorAll('#drawflow .node-error').forEach(function (el) { el.classList.remove('node-error'); });
  }
  function markErrors(blockIds) {
    blockIds.forEach(function (blockId) {
      var number = nodeNumber(blockId);
      var el = number && document.getElementById('node-' + number);
      if (el) el.classList.add('node-error');
    });
  }
  function markProblems() {
    clearErrors();
    document.querySelectorAll('#problems button.problem').forEach(function (b) { markErrors(b.dataset.blocks.split(',')); });
  }
  function focusBlock(blockId, select) {
    var number = nodeNumber(blockId);
    if (!number) return;
    var node = editor.getNodeFromId(number);
    var zoom = editor.zoom || 1;
    editor.canvas_x = editor.container.clientWidth / 2 - (node.pos_x + 100) * zoom;
    editor.canvas_y = editor.container.clientHeight / 2 - (node.pos_y + 40) * zoom;
    editor.precanvas.style.transform = 'translate(' + editor.canvas_x + 'px, ' + editor.canvas_y + 'px) scale(' + zoom + ')';
    if (select) {
      document.querySelectorAll('#drawflow .drawflow-node.selected').forEach(function (el) { el.classList.remove('selected'); });
      var el = document.getElementById('node-' + number);
      if (el) el.classList.add('selected');
      editor.dispatch('nodeSelected', number);
    }
  }
  var resultBox = document.getElementById('result');
  if (resultBox) {
    resultBox.addEventListener('click', function (event) {
      var button = event.target.closest && event.target.closest('button.problem');
      if (!button) return;
      var ids = button.dataset.blocks.split(',');
      markErrors(ids);
      focusBlock(ids[0], true);
    });
  }

  // autosave 2 s after the last change, only when something differs from what was saved
  var autosave = document.getElementById('autosave');
  function changed() {
    if (!autosave || !autosave.checked) return;
    clearTimeout(autosaveTimer);
    autosaveTimer = setTimeout(function () {
      if (stateKey() !== lastSaved) saveNow();
    }, 2000);
  }
  ['version', 'fabrics', 'provides', 'plugins'].forEach(function (id) { document.getElementById(id).addEventListener('input', changed); });
  // other versions of a plugin offer other blocks: the editor is read again with the saved ranges
  document.getElementById('plugins').addEventListener('change', function () {
    clearTimeout(autosaveTimer);
    saveNow().then(function () { window.location.reload(); });
  });
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

  // deploying: save, then publish and deploy in one step (the develop loop)
  var deploy = document.getElementById('deploy-blueprint');
  if (deploy) {
    var deployNow = function () {
      clearTimeout(autosaveTimer);
      saveNow().then(function () {
        var box = document.getElementById('result');
        if (box.querySelector('.error')) return;
        return htmx.ajax('POST', host.dataset.deployUrl, { source: host, target: '#result', swap: 'innerHTML' });
      });
    };
    deploy.addEventListener('click', deployNow);
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) { event.preventDefault(); deployNow(); }
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
    labelAll();
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
