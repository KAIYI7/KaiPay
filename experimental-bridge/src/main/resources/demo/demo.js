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
    const names = {PAYMENT_DETECTED: 'Payment detected — awaiting verification', AWAITING_VERIFICATION: 'Awaiting separate manual confirmation', MANUALLY_CONFIRMED: 'Payment confirmed (manual, experimental)', AWAITING_PAYMENT: 'Awaiting payment (synthetic)', AMBIGUOUS: 'Ambiguous — cannot confirm', EXPIRED: 'Expired — cannot confirm'};
    label.textContent = 'Synthetic MYR ' + (request.amountMinor / 100).toFixed(2) + ' • ' + names[request.status] + ' • ' + request.id;
    row.append(label);
    if (request.status === 'PAYMENT_DETECTED' || request.status === 'AWAITING_VERIFICATION') {
      const button = document.createElement('button');
      button.textContent = request.status === 'PAYMENT_DETECTED' ? 'Begin manual verification' : 'Confirm after independent history check';
      button.onclick = () => run(async () => {
        if (!window.confirm('Receiver only: acknowledge independent verification. This page uses synthetic events. Detection alone is insufficient.')) return;
        await api('/api/requests/' + request.id + (request.status === 'PAYMENT_DETECTED' ? '/verify' : '/confirm'), {historyChecked: true});
        await refresh();
      });
      row.append(button);
    }
    byId('requests').append(row);
  }
  byId('observations').textContent = state.observations.map(item => item.disposition + ' • synthetic event ' + item.candidate.eventId).join('\n');
}
async function run(action) { try { await action(); byId('message').textContent = ''; } catch (error) { byId('message').textContent = error.message; } }
byId('create').onclick = () => run(async () => { await api('/api/requests', {amountMinor: amountMinor()}); await refresh(); });
byId('observe').onclick = () => run(async () => { await api('/api/observations', {eventId: crypto.randomUUID(), notificationId: crypto.randomUUID(), sequence: 1, observedAt: new Date().toISOString(), amountMinor: amountMinor(), source: 'SIMULATED'}, 'device'); await refresh(); });
byId('refresh').onclick = () => run(async () => { await refresh(); clearInterval(polling); polling = setInterval(() => run(refresh), 3000); });
byId('clear').onclick = () => { clearInterval(polling); byId('receiver').value = ''; byId('device').value = ''; byId('requests').replaceChildren(); byId('observations').textContent = ''; byId('message').textContent = 'Credentials cleared; polling stopped.'; };
window.addEventListener('pagehide', () => { clearInterval(polling); byId('receiver').value = ''; byId('device').value = ''; });
