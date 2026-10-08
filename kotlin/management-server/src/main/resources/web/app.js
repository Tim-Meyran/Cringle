// Own helpers of the WebUI; the page logic is in htmx attributes and Alpine x-data.
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
