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
    AWAITING_VERIFICATION: ['Awaiting receiver confirmation', 'The receiver has recorded the verification step. Explicit confirmation is still required. Do not pay again.'],
    MANUALLY_CONFIRMED: ['Payment confirmed (receiver verified)', 'The receiver independently checked TNG history and explicitly recorded confirmation. This remains a receiver assertion, not authenticated provider settlement.'],
    AMBIGUOUS: ['Ambiguous — receiver review required', 'Competing or changed evidence cannot safely identify the payment for this request. Contact the receiver; completion has not been confirmed.'],
    EXPIRED: ['Expired', 'This request can no longer be verified or confirmed. Ask the receiver for a new checkout.']
  };
  let timer, busy = false, cleared = false, qrUrl = null, qrChecked = false, controller = new AbortController();
  function hideQr(title, detail) {
    byId('receiving-qr').removeAttribute('src');
    if (qrUrl) URL.revokeObjectURL(qrUrl);
    qrUrl = null;
    byId('qr-display').classList.add('hidden');
    byId('qr-placeholder').classList.remove('hidden');
    byId('qr-placeholder-title').textContent = title;
    byId('qr-placeholder-detail').textContent = detail;
    byId('payment-instructions').textContent = 'No payment code is displayed. Do not attempt a payment from this page while the QR is unavailable.';
  }
  async function loadQr(request, signal) {
    if (['EXPIRED', 'MANUALLY_CONFIRMED', 'AMBIGUOUS'].includes(request.status)) {
      hideQr('Payment QR hidden', request.status === 'EXPIRED' ? 'This checkout has expired. Ask the receiver for a new checkout.' : request.status === 'MANUALLY_CONFIRMED' ? 'This checkout is already confirmed. Do not pay again.' : 'Receiver review is required. Do not pay while this checkout is ambiguous.');
      qrChecked = false;
      return;
    }
    if (request.evidence === 'SYNTHETIC') {
      hideQr('Synthetic demonstration — no payment needed', 'Invented evidence only. No actual funds were received; do not make a transfer for this demonstration.');
      qrChecked = false;
      return;
    }
    if (!qrChecked) {
      const result = await fetch('/api/checkout/' + requestId + '/qr', {method: 'GET', headers: {Authorization: 'Bearer ' + key}, credentials: 'omit', cache: 'no-store', redirect: 'error', signal});
      if (cleared) return;
      if (result.status === 404) {
        hideQr('No payment QR configured', 'Nonpayable placeholder · ask the receiver to configure their genuine receiving QR privately.');
        qrChecked = true;
        return;
      }
      if (!result.ok || result.headers.get('Content-Type')?.split(';')[0].trim() !== 'image/png') throw Error('The payment QR is unavailable. Check the current status before attempting payment.');
      const bytes = await result.blob();
      if (cleared || signal.aborted) return;
      const nextUrl = URL.createObjectURL(bytes);
      try {
        byId('receiving-qr').src = nextUrl;
        await byId('receiving-qr').decode();
        if (cleared || signal.aborted) { byId('receiving-qr').removeAttribute('src'); URL.revokeObjectURL(nextUrl); return; }
        if (qrUrl) URL.revokeObjectURL(qrUrl);
        qrUrl = nextUrl;
        qrChecked = true;
        byId('qr-display').classList.remove('hidden');
        byId('qr-placeholder').classList.add('hidden');
      } catch (error) { URL.revokeObjectURL(nextUrl); throw error; }
    }
    if (qrUrl) byId('payment-instructions').textContent = request.status === 'AWAITING_PAYMENT'
      ? 'Scan this receiver-supplied QR using the official TNG app, with another screen if needed. Check the receiving destination yourself. Enter exactly RM ' + (request.amountMinor / 100).toFixed(2) + ' manually unless the official app already shows that exact amount. Independently review and authorize in TNG. Keep this checkout open for status updates.'
      : 'Potential payment evidence has been detected. Do not pay again. Wait for the receiver to check actual TNG history and confirm this checkout.';
  }
  function unavailable(title, detail) {
    hideQr('Payment QR unavailable', 'Current checkout status could not be verified. Retry before attempting a payment.');
    qrChecked = false;
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
      await loadQr(request, attempt.signal);
      if (cleared) return;
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
