'use strict';
const byId = id => document.getElementById(id);
const names = {AWAITING_PAYMENT: 'Awaiting payment', PAYMENT_DETECTED: 'Potential payment detected — unverified', AWAITING_VERIFICATION: 'Awaiting receiver confirmation', MANUALLY_CONFIRMED: 'Manually confirmed', AMBIGUOUS: 'Ambiguous — receiver review required', EXPIRED: 'Payment window closed — unconfirmed', REVIEW_EXPIRED: 'Receiver review ended — unconfirmed'};
const payerLinks = new Map();
const pendingConfirmations = new Set();
let polling, generation = 0, refreshSequence = 0, lastRendered = '', controller = new AbortController();
const money = minor => 'RM ' + (minor / 100).toFixed(2);
function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}
function amountMinor() {
  const value = byId('amount').value.trim();
  if (!/^(0|[1-9][0-9]{0,4})\.[0-9]{2}$/.test(value)) throw Error('Enter an RM amount with exactly two decimal places.');
  const [whole, fraction] = value.split('.');
  const minor = Number(whole) * 100 + Number(fraction);
  if (minor < 1 || minor > 1000000) throw Error('Amount must be between RM 0.01 and RM 10,000.00.');
  return minor;
}
function ensureCurrent(epoch) {
  if (epoch !== generation) throw new DOMException('Private session cleared', 'AbortError');
}
async function api(path, body, role = 'receiver') {
  const epoch = generation;
  const token = byId(role).value.trim();
  if (!token) throw Error('Enter the ' + (role === 'receiver' ? 'receiver' : 'simulation device') + ' credential first.');
  const sessionSignal = controller.signal;
  const attempt = new AbortController();
  const cancel = () => attempt.abort();
  sessionSignal.addEventListener('abort', cancel, {once: true});
  const timeout = setTimeout(cancel, 10000);
  try {
    const result = await fetch(path, {method: body === undefined ? 'GET' : 'POST', credentials: 'omit', cache: 'no-store', redirect: 'error', signal: attempt.signal,
      headers: {Authorization: 'Bearer ' + token, ...(body === undefined ? {} : {'Content-Type': 'application/json'})},
      body: body === undefined ? undefined : JSON.stringify(body)});
    const payload = await result.json();
    ensureCurrent(epoch);
    if (!result.ok) throw Error(payload.error || 'Request failed. Check credentials and retry.');
    return payload;
  } catch (error) {
    if (epoch === generation && error.name === 'AbortError') throw Error('The connection timed out. Refresh to check the current state before retrying an action.');
    throw error;
  } finally { clearTimeout(timeout); sessionSignal.removeEventListener('abort', cancel); }
}
function addPrivateLink(row, request) {
  const linkValue = payerLinks.get(request.id);
  if (!linkValue) return;
  const box = element('div', undefined, 'private-link');
  const link = element('a', 'Open private payer checkout');
  link.href = linkValue;
  link.target = '_blank';
  link.rel = 'noopener noreferrer';
  const input = element('input');
  input.value = linkValue;
  input.readOnly = true;
  input.setAttribute('aria-label', 'Private payer checkout link for ' + request.id);
  input.addEventListener('focus', () => input.select());
  box.append(link, input, element('p', 'Sensitive read-only link: anyone holding it can view this request. Share only with the intended private payer. Server restart invalidates it; generate another link if needed.', 'muted'));
  row.append(box);
}
function render(state) {
  const requestRows = [];
  const evidenceCapacityReached = state.observations.length >= 1000;
  for (const request of [...state.requests].reverse()) {
    const row = element('article');
    const evidence = state.observations.find(item => item.candidate.eventId === (request.observationId || request.reviewGrant?.eventId));
    const synthetic = evidence && evidence.candidate.source !== 'TNG_NOTIFICATION_CANDIDATE';
    const reviewAvailable = request.reviewGrant && !request.reviewGrant.revoked && ['PAYMENT_DETECTED', 'AWAITING_VERIFICATION'].includes(request.status);
    const statusName = request.status === 'MANUALLY_CONFIRMED' ? (synthetic ? 'Demo manually confirmed — no funds received' : 'Payment confirmed (receiver verified)') : request.paymentClosed && reviewAvailable ? (request.status === 'PAYMENT_DETECTED' ? 'Payment window closed — receiver review pending (unverified)' : 'Payment window closed — awaiting receiver confirmation') : names[request.status];
    row.append(element('p', money(request.amountMinor), 'amount'), element('p', statusName || 'Unknown state — refresh required', 'status ' + (request.status === 'MANUALLY_CONFIRMED' ? 'confirmed' : '')),
      element('p', 'Request / order reference', 'muted'), element('p', request.id, 'reference'), element('p', 'Payment deadline: ' + new Date(request.expiresAt).toLocaleString() + (request.paymentClosed ? ' · closed to new candidates' : ''), 'muted'));
    if (request.reviewGrant) row.append(element('p', 'Receiver review deadline: ' + new Date(request.reviewGrant.reviewExpiresAt).toLocaleString() + (request.reviewGrant.revoked ? ' · grant revoked; cannot be restored' : reviewAvailable ? ' · grant active; server rechecks eligibility on submission' : request.status === 'MANUALLY_CONFIRMED' ? ' · receiver confirmation recorded' : ' · review unavailable'), 'muted'));
    else row.append(element('p', 'No extended receiver-review period has been granted.', 'muted'));
    if (synthetic) row.append(element('p', 'SYNTHETIC DEMO — invented evidence; no actual funds received.', 'notice'));
    else if (evidence) row.append(element('p', 'Notification candidate — unverified evidence, not settlement proof.', 'notice'));
    if (request.status === 'AMBIGUOUS') row.append(element('p', 'Competing or changed evidence needs receiver review. Equal amount and time cannot identify the correct payer.'));
    if (request.status === 'EXPIRED' || request.status === 'REVIEW_EXPIRED') row.append(element('p', 'This request is unconfirmed and can no longer be verified or confirmed. Contact the payer to resolve any actual transfer. Do not request another payment automatically.'));
    if (request.paymentClosed && reviewAvailable) row.append(element('p', 'The payment window is closed. Review only the previously accepted candidate; it remains unverified until your independent check and explicit confirmation. Do not ask the payer to pay again.', 'notice'));
    if (evidenceCapacityReached && ['PAYMENT_DETECTED', 'AWAITING_VERIFICATION'].includes(request.status)) row.append(element('p', 'Evidence capacity is reached. Verification and confirmation are unavailable until explicit offline recovery.', 'notice'));
    const linkButton = element('button', payerLinks.has(request.id) ? 'Show private payer link' : 'Get private payer link', 'secondary');
    linkButton.type = 'button';
    linkButton.onclick = () => run(async epoch => {
      const access = await api('/api/requests/' + request.id + '/payer-access', {});
      ensureCurrent(epoch);
      if (access.requestId !== request.id || !/^[A-Za-z0-9_-]{43}$/.test(access.token)) throw Error('Invalid payer access response.');
      payerLinks.set(request.id, location.origin + '/checkout#id=' + encodeURIComponent(request.id) + '&key=' + encodeURIComponent(access.token));
      await refresh();
    });
    row.append(linkButton);
    addPrivateLink(row, request);
    if (!evidenceCapacityReached && ['PAYMENT_DETECTED', 'AWAITING_VERIFICATION'].includes(request.status) && evidence?.disposition === 'MATCHED' && request.observationId === evidence.candidate.eventId && (!request.paymentClosed || reviewAvailable)) {
      const form = element('form', undefined, 'confirmation-form');
      const label = element('label', undefined, 'attestation');
      const checkbox = element('input');
      checkbox.type = 'checkbox';
      checkbox.required = true;
      checkbox.disabled = pendingConfirmations.has(request.id);
      label.append(checkbox, element('span', synthetic
        ? 'I acknowledge this is invented synthetic evidence for this checkout. No actual TNG transaction or funds are being asserted, and no unresolved ambiguity prevents this demo confirmation.'
        : 'I independently checked actual TNG transaction history: the incoming transaction exists, its actual amount matches this checkout, I identified this intended checkout, and no unresolved ambiguity or conflicting evidence prevents confirmation.'));
      const button = element('button', synthetic ? 'Confirm synthetic demo — no funds received' : "I've checked TNG history — Confirm payment");
      button.type = 'submit';
      button.disabled = true;
      checkbox.onchange = () => { button.disabled = !checkbox.checked || pendingConfirmations.has(request.id); };
      form.onsubmit = event => {
        event.preventDefault();
        if (!checkbox.checked || pendingConfirmations.has(request.id)) return;
        pendingConfirmations.add(request.id);
        button.disabled = true;
        checkbox.disabled = true;
        run(async epoch => {
          try {
            let alreadyConfirmed = false;
            const confirmation = {historyChecked: true, expectedEventId: evidence.candidate.eventId};
            if (request.status === 'PAYMENT_DETECTED') {
              const verified = await api('/api/requests/' + request.id + '/verify', confirmation);
              ensureCurrent(epoch);
              if (verified.id !== request.id || !['AWAITING_VERIFICATION', 'MANUALLY_CONFIRMED'].includes(verified.status)) throw Error('Verification was not acknowledged in the expected state.');
              alreadyConfirmed = verified.status === 'MANUALLY_CONFIRMED';
            }
            if (!alreadyConfirmed) {
              const confirmed = await api('/api/requests/' + request.id + '/confirm', confirmation);
              ensureCurrent(epoch);
              if (confirmed.id !== request.id || confirmed.status !== 'MANUALLY_CONFIRMED') throw Error('Confirmation was not acknowledged in the expected state.');
            }
          } catch (error) {
            if (epoch !== generation) throw error;
            try { await refresh(); } catch { /* Keep the failure explicit if the current state is unavailable. */ }
            throw Error('Confirmation response unavailable or rejected. Check the current server status before retrying; verification may have succeeded separately. ' + error.message);
          } finally {
            pendingConfirmations.delete(request.id);
            checkbox.checked = false;
            checkbox.disabled = false;
            button.disabled = true;
            lastRendered = '';
          }
          await refresh();
        });
      };
      form.append(label, button, element('p', request.status === 'AWAITING_VERIFICATION'
        ? 'Verification is recorded; confirmation is still outstanding. Check the attestation again to resume.'
        : 'This submission records verification, then separately requests confirmation for this candidate. The server validates each transition and deadline.', 'muted'));
      row.append(form);
    }
    requestRows.push(row);
  }
  byId('requests').replaceChildren(...(requestRows.length ? requestRows : [element('p', 'No requests yet. Create a checkout above.', 'empty')]));
  const observationRows = [];
  for (const item of [...state.observations].reverse()) {
    const row = element('article');
    const candidate = item.candidate;
    const synthetic = candidate.source !== 'TNG_NOTIFICATION_CANDIDATE';
    row.append(element('p', synthetic ? 'SYNTHETIC · invented evidence · no funds received' : 'TNG notification candidate · UNVERIFIED', 'eyebrow'), element('p', money(candidate.amountMinor) + ' · ' + item.disposition, 'status'), element('p', 'Device-reported observation: ' + new Date(candidate.observedAt).toLocaleString() + ' · not proof of payment time', 'muted'), element('p', item.acceptedAt ? 'Server admission: ' + new Date(item.acceptedAt).toLocaleString() : 'Historical evidence: server admission time unknown', 'muted'), element('p', 'Event ' + candidate.eventId, 'reference'));
    if (item.disposition === 'UNMATCHED') row.append(element('p', 'No eligible matching request. This evidence does not change any checkout to paid.'));
    if (['EXPIRED', 'DELAYED', 'INVALIDATED', 'UPDATE_IGNORED'].includes(item.disposition)) row.append(element('p', 'Review-only evidence. This observation cannot establish payment completion.'));
    if (candidate.source !== 'SIMULATED' && ['UNMATCHED', 'AMBIGUOUS'].includes(item.disposition)) {
      const eligible = state.requests.filter(request => !request.paymentClosed && !request.reviewGrant && ['AWAITING_PAYMENT', 'AMBIGUOUS'].includes(request.status) && request.amountMinor === candidate.amountMinor
        && new Date(candidate.observedAt) >= new Date(request.createdAt) && new Date(candidate.observedAt) < new Date(request.expiresAt));
      if (eligible.length) {
        const choices = element('select');
        choices.setAttribute('aria-label', 'Request for independent receiver resolution');
        for (const request of eligible) { const option = element('option', request.id); option.value = request.id; choices.append(option); }
        const button = element('button', 'Resolve to selected request after independent check', 'secondary');
        button.type = 'button';
        button.onclick = () => run(async () => {
          if (!window.confirm((synthetic ? 'Invented synthetic scenario only; no actual funds received. ' : 'Independently check actual TNG history and the correct request. ') + 'Resolve this unverified evidence? Separate verification and confirmation remain required. Stale or consumed evidence is rejected by the server.')) return;
          await api('/api/observations/' + candidate.eventId + '/resolve', {requestId: choices.value, historyChecked: true});
          await refresh();
        });
        row.append(choices, button);
      }
    }
    observationRows.push(row);
  }
  byId('observations').replaceChildren(...(observationRows.length ? observationRows : [element('p', 'No candidate evidence yet.', 'empty')]));
}
async function refresh() {
  const sequence = ++refreshSequence;
  const state = await api('/api/state');
  if (sequence !== refreshSequence) return;
  const fingerprint = JSON.stringify([state, [...payerLinks]]);
  if (fingerprint !== lastRendered) { render(state); lastRendered = fingerprint; }
  byId('connection').textContent = 'Updated ' + new Date().toLocaleTimeString();
}
function startPolling() { clearInterval(polling); polling = setInterval(() => run(refresh), 3000); }
async function run(action) {
  const epoch = generation;
  try { await action(epoch); ensureCurrent(epoch); byId('message').textContent = ''; }
  catch (error) { if (epoch === generation && error.name !== 'AbortError') { byId('message').textContent = error.message; byId('connection').textContent = 'Refresh failed — displayed evidence may be stale'; } }
}
function clearSession() {
  generation++;
  refreshSequence++;
  clearInterval(polling);
  controller.abort();
  controller = new AbortController();
  payerLinks.clear();
  pendingConfirmations.clear();
  lastRendered = '';
  byId('receiver').value = '';
  byId('device').value = '';
  byId('requests').replaceChildren();
  byId('observations').replaceChildren();
  byId('connection').textContent = 'Disconnected';
  byId('message').textContent = 'Private data and credentials cleared; polling stopped.';
}
byId('create').onclick = () => run(async () => { await api('/api/requests', {amountMinor: amountMinor()}); await refresh(); startPolling(); });
byId('observe').onclick = () => run(async () => { await api('/api/observations', {eventId: crypto.randomUUID(), notificationId: crypto.randomUUID(), sequence: 1, observedAt: new Date().toISOString(), amountMinor: amountMinor(), source: 'SIMULATED'}, 'device'); await refresh(); });
byId('refresh').onclick = () => run(async () => { await refresh(); startPolling(); });
byId('clear').onclick = clearSession;
window.addEventListener('pagehide', clearSession);
