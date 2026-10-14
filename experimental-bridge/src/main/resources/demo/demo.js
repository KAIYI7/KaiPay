'use strict';
const byId = id => document.getElementById(id);
let polling;
function amountMinor() {
  const value = byId('amount').value;
  if (!/^(0|[1-9][0-9]{0,4})\.[0-9]{2}$/.test(value)) throw Error('Use a synthetic amount with exactly two decimal places.');
  const [whole, fraction] = value.split('.');
  const minor = Number(whole) * 100 + Number(fraction);
  if (minor < 1 || minor > 1000000) throw Error('Amount must be between 0.01 and 10000.00.');
  return minor;
}
async function api(path, body, role = 'receiver') {
  const token = byId(role).value;
  const result = await fetch(path, {method: body === undefined ? 'GET' : 'POST', credentials: 'omit', cache: 'no-store',
    headers: {Authorization: 'Bearer ' + token, ...(body === undefined ? {} : {'Content-Type': 'application/json'})},
    body: body === undefined ? undefined : JSON.stringify(body)});
  const payload = await result.json();
  if (!result.ok) throw Error(payload.error || 'Request failed');
  return payload;
}
async function refresh() {
  const state = await api('/api/state');
  byId('requests').replaceChildren();
  for (const request of state.requests) {
    const row = document.createElement('article');
    const label = document.createElement('p');
    const names = {PAYMENT_DETECTED: 'Potential incoming payment — unverified', AWAITING_VERIFICATION: 'Awaiting separate manual confirmation', MANUALLY_CONFIRMED: 'Receiver manually confirmed (experimental)', AWAITING_PAYMENT: 'Awaiting payment (experimental checkout)', AMBIGUOUS: 'Ambiguous — receiver review required', EXPIRED: 'Expired — cannot confirm'};
    label.textContent = 'Synthetic MYR ' + (request.amountMinor / 100).toFixed(2) + ' • ' + names[request.status] + ' • ' + request.id;
    row.append(label);
    if (request.status === 'PAYMENT_DETECTED' || request.status === 'AWAITING_VERIFICATION') {
      const button = document.createElement('button');
      button.textContent = request.status === 'PAYMENT_DETECTED' ? 'Begin manual verification' : 'Confirm after independent history check';
      button.onclick = () => run(async () => {
        if (!window.confirm('Receiver only: check actual TNG transaction history independently before verifying a real candidate. For synthetic evidence, acknowledge the invented scenario. Matching amount and notification detection do not prove settlement.')) return;
        await api('/api/requests/' + request.id + (request.status === 'PAYMENT_DETECTED' ? '/verify' : '/confirm'), {historyChecked: true});
        await refresh();
      });
      row.append(button);
    }
    byId('requests').append(row);
  }
  byId('observations').replaceChildren();
  for (const item of state.observations) {
    const row = document.createElement('article');
    const label = document.createElement('p');
    const candidate = item.candidate;
    const source = candidate.source === 'TNG_NOTIFICATION_CANDIDATE' ? 'TNG notification evidence (UNVERIFIED)' : 'SYNTHETIC invented evidence';
    label.textContent = source + ' • ' + item.disposition + ' • MYR ' + (candidate.amountMinor / 100).toFixed(2) + ' • ' + candidate.observedAt + ' • event ' + candidate.eventId;
    row.append(label);
    if (candidate.source !== 'SIMULATED' && ['UNMATCHED', 'AMBIGUOUS'].includes(item.disposition)) {
      const eligible = state.requests.filter(request => ['AWAITING_PAYMENT', 'AMBIGUOUS'].includes(request.status) && request.amountMinor === candidate.amountMinor
        && new Date(candidate.observedAt) >= new Date(request.createdAt) && new Date(candidate.observedAt) < new Date(request.expiresAt));
      if (eligible.length) {
        const choices = document.createElement('select');
        choices.setAttribute('aria-label', 'Request for independent receiver resolution');
        for (const request of eligible) { const option = document.createElement('option'); option.value = request.id; option.textContent = request.id; choices.append(option); }
        const button = document.createElement('button');
        button.textContent = 'Resolve after independent history check';
        button.onclick = () => run(async () => {
          if (!window.confirm('Receiver only: independently check transaction history and the correct request. This selects an unverified candidate; separate verification and confirmation are still required. Delayed, obsolete, changed or consumed evidence cannot be resolved.')) return;
          await api('/api/observations/' + candidate.eventId + '/resolve', {requestId: choices.value, historyChecked: true});
          await refresh();
        });
        row.append(choices, button);
      }
    }
    byId('observations').append(row);
  }
}
function startPolling() { clearInterval(polling); polling = setInterval(() => run(refresh), 3000); }
async function run(action) { try { await action(); byId('message').textContent = ''; } catch (error) { byId('message').textContent = error.message; } }
byId('create').onclick = () => run(async () => { await api('/api/requests', {amountMinor: amountMinor()}); await refresh(); startPolling(); });
byId('observe').onclick = () => run(async () => { await api('/api/observations', {eventId: crypto.randomUUID(), notificationId: crypto.randomUUID(), sequence: 1, observedAt: new Date().toISOString(), amountMinor: amountMinor(), source: 'SIMULATED'}, 'device'); await refresh(); });
byId('refresh').onclick = () => run(async () => { await refresh(); startPolling(); });
byId('clear').onclick = () => { clearInterval(polling); byId('receiver').value = ''; byId('device').value = ''; byId('requests').replaceChildren(); byId('observations').textContent = ''; byId('message').textContent = 'Credentials cleared; polling stopped.'; };
window.addEventListener('pagehide', () => { clearInterval(polling); byId('receiver').value = ''; byId('device').value = ''; });
