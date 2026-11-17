'use strict';
(() => {
  const byId = id => document.getElementById(id);
  const privateEntry = document.documentElement.dataset.checkoutEntry === 'private';
  // Set only by the private backend in synthetic-only integration mode: no QR fetch and no genuine-payment wording.
  const synthetic = privateEntry && document.documentElement.dataset.checkoutMode === 'synthetic';
  const legacy = new URLSearchParams(location.hash.slice(1));
  const hadFragment = location.hash.length > 0, hadQuery = location.search.length > 0;
  let legacyId = privateEntry ? legacy.get('id') : null, legacyKey = privateEntry ? legacy.get('key') : null;
  history.replaceState(null, '', location.pathname);
  for (const name of [...legacy.keys()]) legacy.delete(name);
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
  const bearer = /^[A-Za-z0-9_-]{43}$/;
  const code = /^KP1\.([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.([A-Za-z0-9_-]{43})$/;
  const allowedTransport = location.protocol === 'https:' || (location.protocol === 'http:' && ['127.0.0.1', 'localhost', '[::1]'].includes(location.hostname));
  const states = {
    AWAITING_PAYMENT: ['Awaiting payment', 'No matching candidate has been detected for this request.'],
    PAYMENT_DETECTED: ['Potential payment detected — unverified', 'Candidate evidence has tentatively matched. Detection is not proof of settlement. The receiver has not completed verification.'],
    AWAITING_VERIFICATION: ['Awaiting receiver confirmation', 'The receiver has recorded the verification step. Explicit confirmation is still required. Do not pay again.'],
    MANUALLY_CONFIRMED: ['Payment confirmed (receiver verified)', 'The receiver independently checked TNG history and explicitly recorded confirmation. This remains a receiver assertion, not authenticated provider settlement.'],
    AMBIGUOUS: ['Ambiguous — receiver review required', 'Competing or changed evidence cannot safely identify the payment for this request. Contact the receiver; completion has not been confirmed.'],
    EXPIRED: ['Payment window closed — unconfirmed', 'This request can no longer accept candidates or be confirmed. Contact the receiver. If money may have been sent, do not pay again automatically.'],
    REVIEW_EXPIRED: ['Receiver review ended — unconfirmed', 'The receiver review deadline has passed without confirmation. Contact the receiver. If money may have been sent, do not pay again automatically.']
  };
  const syntheticStates = {
    AWAITING_PAYMENT: ['Awaiting synthetic evidence', 'No invented candidate has been detected for this demo checkout. No payment is needed.'],
    PAYMENT_DETECTED: ['Potential payment detected — unverified (synthetic)', 'An invented candidate has tentatively matched. It is not a payment, and the organizer has not reviewed it.'],
    AWAITING_VERIFICATION: ['Awaiting organizer confirmation (synthetic)', 'The organizer recorded the review step for invented evidence. Explicit confirmation is still required. No money moves.'],
    MANUALLY_CONFIRMED: ['Demo manually confirmed', 'The organizer manually confirmed an invented demonstration scenario. No actual funds were received.'],
    AMBIGUOUS: ['Ambiguous — organizer review required (synthetic)', 'Competing invented evidence cannot identify this demo checkout. No payment is involved.'],
    EXPIRED: ['Demo window closed — unconfirmed', 'This demo checkout no longer accepts invented candidates. No payment is involved.'],
    REVIEW_EXPIRED: ['Organizer review ended — unconfirmed (synthetic)', 'The review deadline passed without confirmation. No payment is involved.']
  };
  let active = null, timer, deadlineTimer, qrUrl = null;
  const current = session => active === session && !session.controller.signal.aborted;
  function hideQr(title, detail) {
    if (synthetic) { title = 'Synthetic demonstration — no payment needed'; detail = 'Invented evidence only. No actual funds are involved; do not make a transfer for this demonstration.'; }
    byId('receiving-qr').removeAttribute('src');
    if (qrUrl) URL.revokeObjectURL(qrUrl);
    qrUrl = null;
    byId('qr-display').classList.add('hidden');
    byId('qr-placeholder').classList.remove('hidden');
    byId('qr-placeholder-title').textContent = title;
    byId('qr-placeholder-detail').textContent = detail;
    byId('payment-instructions').textContent = synthetic ? 'No payment is needed or possible for this synthetic demo.' : 'No payment code is displayed. Do not attempt a payment from this page while the QR is unavailable.';
  }
  function unavailable(title, detail) {
    hideQr('Payment QR unavailable', 'Current checkout status could not be verified. Retry before attempting a payment.');
    if (active) active.qrChecked = false;
    byId('status-title').textContent = title;
    byId('status-detail').textContent = detail;
    byId('checkout-status').className = 'checkout-status';
    byId('updated').textContent = synthetic ? 'Current demo status is unavailable.' : 'Current payment status is unavailable.';
    byId('evidence-note').className = 'notice hidden';
    byId('evidence-note').textContent = '';
  }
  function clearAccess(title, detail) {
    const previous = active;
    active = null;
    clearInterval(timer); clearInterval(deadlineTimer);
    if (previous) { previous.controller.abort(); previous.key = null; previous.id = null; }
    byId('checkout-code').value = '';
    byId('checkout-amount').textContent = '—';
    byId('checkout-reference').textContent = 'Checkout code required';
    byId('checkout-expiry').textContent = '';
    byId('checkout-review').textContent = '';
    byId('retry').disabled = true;
    unavailable(title, detail);
    byId('access-message').textContent = detail;
  }
  function accessRejected() {
    clearAccess('Checkout code not accepted', 'The code is invalid, expired or revoked. Ask the receiver for a new checkout code, then enter it here.');
  }
  async function loadQr(request, session, signal) {
    if (!current(session)) return;
    if (synthetic) { hideQr('', ''); session.qrChecked = false; return; }
    if (request.paymentWindowClosed || request.status !== 'AWAITING_PAYMENT' || Date.now() >= Date.parse(request.expiresAt)) {
      hideQr('Payment QR hidden', request.status === 'MANUALLY_CONFIRMED' ? 'This checkout is already confirmed. Do not pay again.' : 'The payment window is closed or candidate evidence requires review. Do not pay again while awaiting the receiver.');
      session.qrChecked = false;
      return;
    }
    if (request.evidence === 'SYNTHETIC') {
      hideQr('Synthetic demonstration — no payment needed', 'Invented evidence only. No actual funds were received; do not make a transfer for this demonstration.');
      session.qrChecked = false;
      return;
    }
    if (!session.qrChecked) {
      const result = await fetch('/api/checkout/' + session.id + '/qr', {method: 'GET', headers: {Authorization: 'Bearer ' + session.key}, credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal});
      if (!current(session)) return;
      if (result.status === 401 || result.status === 403) { accessRejected(); return; }
      if (result.status === 404) {
        hideQr('No payment QR configured', 'Nonpayable placeholder · ask the receiver to configure their genuine receiving QR privately.');
        session.qrChecked = true;
        return;
      }
      if (!result.ok || result.headers.get('Content-Type')?.split(';')[0].trim() !== 'image/png') throw Error('QR_UNAVAILABLE');
      const bytes = await result.blob();
      if (!current(session) || signal.aborted) return;
      const nextUrl = URL.createObjectURL(bytes);
      try {
        const image = new Image();
        image.src = nextUrl;
        await image.decode();
        if (!current(session) || signal.aborted) { URL.revokeObjectURL(nextUrl); return; }
        if (Date.now() >= Date.parse(request.expiresAt)) {
          URL.revokeObjectURL(nextUrl);
          hideQr('Payment QR hidden', 'The displayed payment deadline has passed. Wait for current server status; do not pay again automatically.');
          return;
        }
        if (qrUrl) URL.revokeObjectURL(qrUrl);
        qrUrl = nextUrl;
        byId('receiving-qr').src = nextUrl;
        session.qrChecked = true;
        byId('qr-display').classList.remove('hidden');
        byId('qr-placeholder').classList.add('hidden');
      } catch { URL.revokeObjectURL(nextUrl); throw Error('QR_UNAVAILABLE'); }
    }
    if (qrUrl && current(session)) byId('payment-instructions').textContent = request.status === 'AWAITING_PAYMENT'
      ? 'Scan this receiver-supplied QR using the official TNG app, with another screen if needed. Check the receiving destination yourself. Enter exactly RM ' + (request.amountMinor / 100).toFixed(2) + ' manually unless the official app already shows that exact amount. Independently review and authorize in TNG. Keep this checkout open for status updates.'
      : 'Potential payment evidence has been detected. Do not pay again. Wait for the receiver to check actual TNG history and confirm this checkout.';
  }
  async function refresh() {
    const session = active;
    if (!session || session.busy) return;
    session.busy = true;
    const attempt = new AbortController();
    const cancel = () => attempt.abort();
    session.controller.signal.addEventListener('abort', cancel, {once: true});
    const timeout = setTimeout(cancel, 10000);
    try {
      const result = await fetch('/api/checkout/' + session.id, {method: 'GET', headers: {Authorization: 'Bearer ' + session.key}, credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: attempt.signal});
      if (!current(session)) return;
      if ([401, 403, 404].includes(result.status)) { accessRejected(); return; }
      if (!result.ok) throw Error('STATUS_UNAVAILABLE');
      const request = await result.json();
      if (!current(session)) return;
      if (request.id !== session.id || !Number.isInteger(request.amountMinor) || request.amountMinor < 1 || request.amountMinor > 1000000 || !Object.hasOwn(states, request.status) || !['NONE', 'SYNTHETIC', 'NOTIFICATION_UNVERIFIED'].includes(request.evidence) || !Number.isFinite(Date.parse(request.expiresAt)) || typeof request.paymentWindowClosed !== 'boolean' || typeof request.reviewAvailable !== 'boolean' || (request.reviewExpiresAt !== null && !Number.isFinite(Date.parse(request.reviewExpiresAt)))) throw Error('RESPONSE_INVALID');
      if (synthetic && request.evidence === 'NOTIFICATION_UNVERIFIED') {
        // A synthetic-only process never describes genuine evidence as part of a demo; stop instead.
        clearAccess('Not a synthetic demo checkout', 'This process serves synthetic demo checkouts only. Ask the receiver about this request.');
        return;
      }
      byId('checkout-amount').textContent = 'RM ' + (request.amountMinor / 100).toFixed(2);
      byId('checkout-reference').textContent = request.id;
      session.paymentDeadline = Date.parse(request.expiresAt);
      const demo = synthetic, copy = demo ? syntheticStates : states;
      byId('checkout-expiry').textContent = (demo ? 'Demo evidence deadline: ' : 'Payment deadline: ') + new Date(request.expiresAt).toLocaleString() + (request.paymentWindowClosed ? ' · closed to new candidates' : '');
      byId('checkout-review').textContent = request.reviewExpiresAt ? (demo ? 'Organizer review deadline: ' : 'Receiver review deadline: ') + new Date(request.reviewExpiresAt).toLocaleString() + (request.reviewAvailable ? ' · review available, still unconfirmed' : request.status === 'MANUALLY_CONFIRMED' ? (demo ? ' · organizer confirmation recorded' : ' · receiver confirmation recorded') : ' · review unavailable') : demo ? 'No extended organizer-review period has been granted.' : 'No extended receiver-review period has been granted.';
      byId('status-title').textContent = request.evidence === 'SYNTHETIC' && request.status === 'MANUALLY_CONFIRMED' ? 'Demo manually confirmed' : copy[request.status][0];
      byId('status-detail').textContent = request.evidence === 'SYNTHETIC' && request.status === 'MANUALLY_CONFIRMED' ? (demo ? copy.MANUALLY_CONFIRMED[1] : 'The receiver manually confirmed an invented demonstration scenario. No actual funds were received.') : copy[request.status][1];
      if (demo && request.paymentWindowClosed && request.reviewAvailable && ['PAYMENT_DETECTED', 'AWAITING_VERIFICATION'].includes(request.status)) {
        byId('status-title').textContent = request.status === 'PAYMENT_DETECTED' ? 'Demo window closed — organizer review pending (synthetic, unverified)' : 'Demo window closed — awaiting organizer confirmation (synthetic)';
        byId('status-detail').textContent = request.status === 'PAYMENT_DETECTED' ? 'An invented candidate was accepted before the demo deadline. The organizer must acknowledge the synthetic scenario and explicitly confirm before the review deadline. No payment is involved.' : 'The organizer recorded the review step, but explicit confirmation is still outstanding. The review deadline has not changed. No money moves.';
      } else if (request.paymentWindowClosed && request.reviewAvailable && ['PAYMENT_DETECTED', 'AWAITING_VERIFICATION'].includes(request.status)) {
        byId('status-title').textContent = request.status === 'PAYMENT_DETECTED' ? 'Payment window closed — receiver review pending (unverified)' : 'Payment window closed — awaiting receiver confirmation';
        byId('status-detail').textContent = request.status === 'PAYMENT_DETECTED' ? (request.evidence === 'SYNTHETIC' ? 'An invented candidate was accepted before the payment deadline. The receiver must acknowledge the synthetic scenario and explicitly confirm before the review deadline. No actual history check or funds are asserted. Do not make a payment for this demo.' : 'An eligible candidate was accepted before the payment deadline. It remains unverified. The receiver must independently check actual history and explicitly confirm before the review deadline. Do not pay again.') : 'Verification is recorded, but explicit receiver confirmation is still outstanding. The review deadline has not changed. Do not pay again.';
      }
      byId('checkout-status').className = 'checkout-status' + (request.status === 'MANUALLY_CONFIRMED' ? ' confirmed' : '');
      byId('evidence-note').textContent = request.evidence === 'SYNTHETIC' ? 'SYNTHETIC DEMO · invented evidence · no actual funds received.' : request.evidence === 'NOTIFICATION_UNVERIFIED' ? 'Android notification candidate evidence is unverified and does not prove settlement.' : '';
      byId('evidence-note').className = 'notice' + (request.evidence === 'NONE' ? ' hidden' : '');
      await loadQr(request, session, attempt.signal);
      if (!current(session)) return;
      byId('updated').textContent = 'Updated ' + new Date().toLocaleTimeString() + ' · checks automatically every 3 seconds';
    } catch {
      if (current(session)) unavailable('Unable to retrieve current status', synthetic ? 'The connection or current demo status is unavailable. Retrying automatically; no payment is involved.' : 'The connection or current status is unavailable. Retrying automatically; do not pay while status is unavailable.');
    } finally {
      clearTimeout(timeout);
      session.controller.signal.removeEventListener('abort', cancel);
      session.busy = false;
    }
  }
  function openAccess(id, key) {
    clearAccess('Loading current status', 'Checking this request with the server.');
    active = {id, key, controller: new AbortController(), busy: false, qrChecked: false, paymentDeadline: null};
    byId('checkout-reference').textContent = 'Checking private request…';
    byId('retry').disabled = false;
    byId('access-message').textContent = 'Access stays in this tab only. Reloading or leaving clears it.';
    void refresh(); timer = setInterval(refresh, 3000);
    deadlineTimer = setInterval(() => {
      if (active && active.paymentDeadline !== null && Date.now() >= active.paymentDeadline) {
        hideQr('Payment QR hidden', 'The displayed payment deadline has passed. Wait for a current server status and contact the receiver; do not pay again automatically.');
        active.qrChecked = false;
      }
    }, 250);
  }
  byId('checkout-access-form').onsubmit = event => {
    event.preventDefault();
    let value = byId('checkout-code').value.trim();
    byId('checkout-code').value = '';
    const match = code.exec(value);
    value = null;
    clearAccess('Enter your checkout code', 'Paste the complete checkout code from the receiver.');
    if (!allowedTransport || location.pathname !== '/checkout') {
      clearAccess('Secure checkout required', 'Use the clean HTTPS checkout URL supplied by the receiver.');
      return;
    }
    if (location.search || location.hash) {
      history.replaceState(null, '', location.pathname);
      clearAccess('Use a clean checkout URL', 'Codes in URLs are not accepted. Ask the receiver for a new code and paste it into this form.');
      return;
    }
    if (!match) {
      clearAccess('Checkout code required', 'Enter the complete KP1 checkout code from the receiver. URLs and shortened codes are not accepted.');
      return;
    }
    openAccess(match[1], match[2]);
  };
  byId('retry').onclick = refresh;
  byId('forget').onclick = () => clearAccess('Private checkout cleared', 'Paste a checkout code to open another request.');
  window.addEventListener('pagehide', () => clearAccess('Private checkout cleared', 'Paste your checkout code again to view this request.'));
  window.addEventListener('pageshow', event => {
    byId('checkout-code').value = '';
    if (event.persisted) clearAccess('Private checkout cleared', 'Paste your checkout code again after returning to this page.');
  });
  clearAccess('Enter your checkout code', 'Open the clean checkout URL and finish any access-provider login first. Paste your code only into this KaiPay form.');
  if (!allowedTransport || location.pathname !== '/checkout') {
    clearAccess('Secure checkout required', 'Use the clean HTTPS checkout URL supplied by the receiver.');
    byId('checkout-open').disabled = true;
  } else if (hadQuery) {
    clearAccess('Use a clean checkout URL', 'Codes in URLs are not accepted. Ask the receiver for a new code and paste it into this form.');
  } else if (hadFragment && privateEntry && uuid.test(legacyId || '') && bearer.test(legacyKey || '')) {
    openAccess(legacyId, legacyKey);
    byId('access-message').textContent = 'Legacy private link opened directly. Do not use fragment links through a public access provider; use a clean checkout URL and separate code instead.';
  } else if (hadFragment) {
    clearAccess('Legacy link not accepted here', 'The URL fragment has been cleared. It may already have reached a provider page. Ask the receiver to revoke that link and issue a separate checkout code.');
  }
  legacyId = null; legacyKey = null;
})();
