import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { webhookApi } from '../../api/webhookApi';
import { useMerchant } from '../../context/MerchantContext';
import { WebhookState } from '../../models/webhook';
import { Card } from '../../components/common/Card';
import { Button } from '../../components/common/Button';
import { formatDate } from '../../utils/formatters';

const states: WebhookState[] = ['PENDING', 'IN_FLIGHT', 'DELIVERED', 'TERMINAL', 'EXHAUSTED', 'CANCELLED'];
const date = (value: string | null) => value ? formatDate(value) : '—';

export function WebhooksPage() {
  const { activeMerchant } = useMerchant();
  return activeMerchant.id ? <MerchantWebhooks key={activeMerchant.id} merchantId={activeMerchant.id} /> : null;
}

function MerchantWebhooks({ merchantId }: { merchantId: string }) {
  const [page, setPage] = useState(0);
  const [state, setState] = useState<WebhookState | ''>('');
  const [selected, setSelected] = useState<string | null>(null);
  const client = useQueryClient();
  const endpoint = useQuery({ queryKey: ['webhooks', merchantId, 'endpoint'],
    queryFn: ({ signal }) => webhookApi.endpoint(merchantId, signal) });
  const deliveries = useQuery({ queryKey: ['webhooks', merchantId, 'deliveries', page, state],
    queryFn: ({ signal }) => webhookApi.deliveries(merchantId, page, state || undefined, signal) });
  const detail = useQuery({ queryKey: ['webhooks', merchantId, 'detail', selected], enabled: selected !== null,
    queryFn: ({ signal }) => webhookApi.detail(merchantId, selected!, signal) });

  return <div className="space-y-6">
    <div className="flex flex-wrap justify-between items-center gap-4">
      <div><h1 className="text-2xl font-semibold text-slate-100">Webhooks</h1>
        <p className="text-sm text-slate-400 mt-2">Signed lifecycle notifications for your merchant.</p></div>
      <Button variant="outline" onClick={() => void client.invalidateQueries({ queryKey: ['webhooks', merchantId] })}
        disabled={deliveries.isFetching}>Refresh deliveries</Button>
    </div>
    <Card title="Delivery contract">
      <p className="text-sm text-slate-300">Notifications may arrive more than once or out of order. Verify the signature
        and deduplicate the event ID before processing. A successful response acknowledges delivery; it does not change payment state.</p>
      <p className="text-sm text-slate-400 mt-2">Up to eight attempts within 24 hours. Terminal, expired and cancelled
        deliveries are retained for troubleshooting. Contact the operator for endpoint changes.</p>
      {endpoint.isError ? <p role="alert" className="mt-3 text-rose-300">Endpoint status could not be loaded.</p> :
        endpoint.isPending ? <p className="mt-3 text-slate-400">Loading endpoint status…</p> :
          <p className="mt-3 text-sm text-slate-300">{endpoint.data.enabled ?
            <>Endpoint enrolled · revision <span className="font-mono break-all">{endpoint.data.endpointId}</span></> :
            'No endpoint is enrolled. New notifications start after operator enrollment; historical events are not backfilled.'}</p>}
    </Card>
    <Card title="Delivery history">
      <label className="text-sm text-slate-300">Delivery state
        <select value={state} onChange={(event) => { setState(event.target.value as WebhookState | ''); setPage(0); setSelected(null); }}
          className="ml-3 bg-slate-900 border border-slate-700 rounded p-2">
          <option value="">All states</option>{states.map((value) => <option key={value}>{value}</option>)}
        </select>
      </label>
      {deliveries.isError ? <p role="alert" className="mt-5 text-rose-300">Delivery history could not be loaded.</p> :
        deliveries.isPending ? <p role="status" className="mt-5 text-slate-400">Loading deliveries…</p> : <>
          {!deliveries.data.content.length && <p className="mt-5 text-slate-400">No deliveries match this view.</p>}
          <div className="overflow-x-auto mt-5"><table className="w-full text-left text-sm">
            <thead className="text-slate-400"><tr><th className="py-2 pr-4">Event</th><th className="pr-4">Payment</th>
              <th className="pr-4">State / attempts</th><th className="pr-4">Timing</th><th>Details</th></tr></thead>
            <tbody>{deliveries.data.content.map((delivery) => <tr key={delivery.id} className="border-t border-slate-700 text-slate-300 align-top">
              <td className="py-3 pr-4"><div>{delivery.type}</div><div className="text-xs font-mono break-all">{delivery.eventId}</div>
                {delivery.refundId && <div className="text-xs mt-1">Refund: {delivery.refundId}</div>}</td>
              <td className="py-3 pr-4"><Link className="text-emerald-400 underline break-all" to={`/payments/${delivery.paymentId}`}>{delivery.paymentId}</Link></td>
              <td className="py-3 pr-4"><div>{delivery.state} · {delivery.attempts}/8</div><div className="text-xs">{delivery.lastResult || 'Not attempted'}</div></td>
              <td className="py-3 pr-4 text-xs"><div>Occurred: {date(delivery.occurredAt)}</div><div>Next retry: {date(delivery.nextAttemptAt)}</div>
                <div>Delivered: {date(delivery.deliveredAt)}</div></td>
              <td className="py-3"><Button size="sm" variant="outline" onClick={() => setSelected(delivery.id)}
                aria-label={`Inspect ${delivery.eventId}`}>Inspect</Button></td>
            </tr>)}</tbody>
          </table></div>
          <div className="flex flex-wrap items-center justify-between gap-3 mt-5 text-sm text-slate-400">
            <span>{deliveries.data.totalElements} deliveries · Page {page + 1} of {Math.max(1, deliveries.data.totalPages)}</span>
            <div className="flex gap-2"><Button size="sm" variant="outline" disabled={page === 0} onClick={() => { setPage(page - 1); setSelected(null); }}>Previous</Button>
              <Button size="sm" variant="outline" disabled={deliveries.data.last} onClick={() => { setPage(page + 1); setSelected(null); }}>Next</Button></div>
          </div>
        </>}
    </Card>
    {selected && <Card title="Delivery attempts" action={<Button size="sm" variant="ghost" onClick={() => setSelected(null)}>Close details</Button>}>
      {detail.isError ? <p role="alert" className="text-rose-300">Delivery details could not be loaded.</p> : detail.isPending ?
        <p role="status">Loading attempts…</p> : detail.data && <>
          <dl className="grid grid-cols-1 sm:grid-cols-2 gap-3 text-sm text-slate-300 break-all">
            <div><dt className="text-slate-500">Event</dt><dd>{detail.data.delivery.type} · v{detail.data.delivery.version}</dd><dd>{detail.data.delivery.eventId}</dd></div>
            <div><dt className="text-slate-500">Endpoint revision / key ID</dt><dd>{detail.data.delivery.endpointId}</dd><dd>{detail.data.delivery.keyId}</dd></div>
            <div><dt className="text-slate-500">Expires</dt><dd>{date(detail.data.delivery.expiresAt)}</dd></div>
            <div><dt className="text-slate-500">Delivery ID</dt><dd>{detail.data.delivery.id}</dd></div>
          </dl>
          {!detail.data.attempts.length ? <p className="mt-4 text-slate-400">No attempt has started.</p> :
            <ol className="mt-5 space-y-3">{detail.data.attempts.map((attempt) => <li key={attempt.ordinal} className="border-t border-slate-700 pt-3 text-sm text-slate-300">
              <div>Attempt {attempt.ordinal} · {attempt.result} · HTTP {attempt.httpStatus ?? '—'} · {attempt.durationMs ?? '—'} ms</div>
              <div className="text-xs text-slate-400">Started: {date(attempt.startedAt)} · Completed: {date(attempt.completedAt)}</div>
            </li>)}</ol>}
        </>}
    </Card>}
  </div>;
}
