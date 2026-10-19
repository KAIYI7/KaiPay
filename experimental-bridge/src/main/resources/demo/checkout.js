'use strict';
(() => {
  const byId = id => document.getElementById(id);
  const fragment = new URLSearchParams(location.hash.slice(1));
  let requestId = fragment.get('id'), key = fragment.get('key');
  history.replaceState(null, '', location.pathname);
  fragment.delete('id'); fragment.delete('key');
  const states = {
    AWAITING_PAYMENT: ['Awaiting payment', 'No matching candidate has been detected for this request.'],
    PAYMENT_DETECTED: ['Potential payment detected — unverified', 'Candidate evidence has tentatively matched. Detection is not proof of settlement. The receiver has not completed verification.'],
    AWAITING_VERIFICATION: ['Awaiting receiver verification', 'The receiver has begun verification. Explicit manual confirmation is still required.'],
    MANUALLY_CONFIRMED: ['Manually confirmed', 'The receiver explicitly recorded manual confirmation after the separate verification step. This is an experimental receiver assertion.'],
    AMBIGUOUS: ['Ambiguous — receiver review required', 'Competing or changed evidence cannot safely identify the payment for this request. Contact the receiver; completion has not been confirmed.'],
    EXPIRED: ['Expired', 'This request can no longer be verified or confirmed. Ask the receiver for a new checkout.']
  };
  let timer, busy = false, cleared = false, controller = new AbortController();
  function unavailable(title, detail) {
    byId('status-title').textContent = title;
    byId('status-detail').textContent = detail;
    byId('checkout-status').className = 'checkout-status';
    byId('updated').textContent = 'Current payment status is unavailable.';
    byId('evidence-note').className = 'notice hidden';
    byId('evidence-note').textContent = '';
  }
  async function refresh() {
    if (busy || cleared) return;
    busy = true;
    const attempt = new AbortController();
    const cancel = () => attempt.abort();
    controller.signal.addEventListener('abort', cancel, {once: true});
    const timeout = setTimeout(cancel, 10000);
    try {
      const result = await fetch('/api/checkout/' + requestId, {method: 'GET', headers: {Authorization: 'Bearer ' + key}, credentials: 'omit', cache: 'no-store', redirect: 'error', signal: attempt.signal});
      if (!result.ok) throw Error(result.status === 401 || result.status === 403 || result.status === 404 ? 'Private access was not accepted. Ask the receiver for a current payer link.' : 'The server could not provide a current status. Retrying automatically.');
      const request = await result.json();
      if (cleared) return;
      if (request.id !== requestId || !Number.isInteger(request.amountMinor) || request.amountMinor < 1 || request.amountMinor > 1000000 || !Object.hasOwn(states, request.status) || !['NONE', 'SYNTHETIC', 'NOTIFICATION_UNVERIFIED'].includes(request.evidence) || !Number.isFinite(Date.parse(request.expiresAt))) throw Error('The server returned an unexpected checkout response. Ask the receiver to check it.');
      byId('checkout-amount').textContent = 'RM ' + (request.amountMinor / 100).toFixed(2);
      byId('checkout-reference').textContent = request.id;
      byId('checkout-expiry').textContent = 'Request expiry: ' + new Date(request.expiresAt).toLocaleString();
      byId('status-title').textContent = request.evidence === 'SYNTHETIC' && request.status === 'MANUALLY_CONFIRMED' ? 'Demo manually confirmed' : states[request.status][0];
      byId('status-detail').textContent = request.evidence === 'SYNTHETIC' && request.status === 'MANUALLY_CONFIRMED' ? 'The receiver manually confirmed an invented demonstration scenario. No actual funds were received.' : states[request.status][1];
      byId('checkout-status').className = 'checkout-status' + (request.status === 'MANUALLY_CONFIRMED' ? ' confirmed' : '');
      byId('evidence-note').textContent = request.evidence === 'SYNTHETIC' ? 'SYNTHETIC DEMO · invented evidence · no actual funds received.' : request.evidence === 'NOTIFICATION_UNVERIFIED' ? 'Android notification candidate evidence is unverified and does not prove settlement.' : '';
      byId('evidence-note').className = 'notice' + (request.evidence === 'NONE' ? ' hidden' : '');
      byId('updated').textContent = 'Updated ' + new Date().toLocaleTimeString() + ' · checks automatically every 3 seconds';
    } catch (error) {
      if (!cleared) unavailable('Unable to retrieve current status', error.name === 'AbortError' ? 'The connection timed out. Retrying automatically.' : error.message || 'Connection unavailable. Retrying automatically.');
    } finally { clearTimeout(timeout); controller.signal.removeEventListener('abort', cancel); busy = false; }
  }
  function clear() {
    cleared = true;
    clearInterval(timer);
    controller.abort();
    key = null; requestId = null;
    byId('checkout-amount').textContent = '—';
    byId('checkout-reference').textContent = 'Private checkout cleared';
    byId('checkout-expiry').textContent = '';
    unavailable('Private checkout cleared', 'Re-open the original private payer link to view this request.');
    byId('retry').disabled = true;
  }
  byId('retry').onclick = refresh;
  byId('forget').onclick = clear;
  window.addEventListener('pagehide', clear);
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(requestId || '') || !/^[A-Za-z0-9_-]{43}$/.test(key || '')) {
    key = null; requestId = null; cleared = true;
    byId('checkout-reference').textContent = 'Private link required';
    unavailable('Open your private checkout link', 'Use the complete payer link supplied by the receiver. Reloading clears this tab\'s private access.');
    byId('retry').disabled = true;
  } else { refresh(); timer = setInterval(refresh, 3000); }
})();
