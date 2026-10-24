import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { reconciliationApi } from '../../api/reconciliationApi';
import { useMerchant } from '../../context/MerchantContext';
import { reconciliationClasses, ReconciliationEvidence, ReconciliationFilter, ReconciliationObservation } from '../../models/reconciliation';
import { Card } from '../../components/common/Card';
import { Button } from '../../components/common/Button';
import { formatCentsToCurrency, formatDate } from '../../utils/formatters';

const date = (value?: string) => value ? formatDate(value) : '—';
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const label = (value: string) => value.replace(/_/g, ' ');
function amount(value?: number, currency?: string) {
  if (value === undefined || !Number.isSafeInteger(value) || !currency || !/^[A-Z]{3}$/.test(currency)) return 'Unavailable';
  return currency === 'MYR' ? `${formatCentsToCurrency(value, currency)} · ${value} sen (MYR)` : `${currency} ${value} minor units · historical evidence`;
}

export function ReconciliationPage({ admin = false }: { admin?: boolean }) {
  const { activeMerchant } = useMerchant();
  const [draft, setDraft] = useState('');
  const [scope, setScope] = useState('');
  const merchantId = admin ? scope : activeMerchant.id;
  return <div className="space-y-6">
    <div><h1 className="text-2xl font-semibold text-slate-100">Reconciliation</h1>
      <p className="text-sm text-slate-400 mt-2">Stored simulated authorization reports. Detection and reporting only.</p></div>
    <Card title="Assessment scope"><p className="text-sm text-slate-300">Authorization comparison; capture/refund accounting not assessed.</p>
      <p className="text-sm text-slate-400 mt-2">Each report observes a payment creation cohort in one current database snapshot.
        The cutoff is not a historical balance or settlement date. Pending, unknown and unavailable evidence do not prove financial failure.</p>
      <p className="text-sm text-slate-400 mt-2">Reports are created manually by an operator. Viewing a report does not execute reconciliation or change financial records.</p>
    </Card>
    {admin && <Card title="Operator diagnostics"><form onSubmit={(event) => { event.preventDefault(); if (uuid.test(draft)) setScope(draft.toLowerCase()); }} className="flex flex-wrap gap-3 items-end">
      <label className="text-sm text-slate-300">Merchant UUID<input aria-label="Merchant UUID" value={draft} onChange={(event) => { setDraft(event.target.value); setScope(''); }}
        className="block bg-slate-900 border border-slate-700 rounded p-2 mt-1 w-80 max-w-full" spellCheck={false} /></label>
      <Button type="submit" variant="outline" disabled={!uuid.test(draft)}>View reports</Button>
    </form></Card>}
    {merchantId ? <ScopedReports key={`${admin ? 'admin' : 'merchant'}:${merchantId}`} merchantId={merchantId} admin={admin} /> :
      <p className="text-slate-400">{admin ? 'Enter one merchant UUID to view its stored reports.' : 'No merchant membership is assigned.'}</p>}
  </div>;
}
function ScopedReports({ merchantId, admin }: { merchantId: string; admin: boolean }) {
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const role = admin ? 'admin' : 'merchant';
  const runs = useQuery({ queryKey: ['reconciliation', role, merchantId, 'runs', page],
    queryFn: ({ signal }) => reconciliationApi.runs(merchantId, admin, page, signal), refetchOnWindowFocus: false, refetchOnReconnect: false });
  return <>
    <Card title="Operator-created reports">
      {runs.isError ? <p role="alert" className="text-rose-300">Reports could not be loaded.</p> : runs.isPending ?
        <p role="status">Loading reports…</p> : <>
          {!runs.data.content.length && <p className="text-slate-400">No operator-created reports.</p>}
          <div className="data-scroll" tabIndex={0} role="region" aria-label="Stored reports table"><table className="w-full text-left text-sm"><thead className="text-slate-400"><tr>
            <th className="py-2 pr-4">Run / observed time</th><th className="pr-4">Creation cohort</th><th className="pr-4">Coverage</th><th>Report</th></tr></thead>
            <tbody>{runs.data.content.map((run) => <tr key={run.runId} className="border-t border-slate-700 text-slate-300 align-top">
              <td className="py-3 pr-4"><div className="font-mono break-all">{run.runId}</div><div>{date(run.snapshotObservedAt)}</div></td>
              <td className="py-3 pr-4">{date(run.fromInclusive)} inclusive<br />{date(run.cutoffExclusive)} exclusive</td>
              <td className="py-3 pr-4">{run.cohortCount} examined<br />{run.counts.MATCHED || 0} authorization matches<br />{run.counts.EVIDENCE_UNAVAILABLE || 0} unavailable</td>
              <td className="py-3"><Button size="sm" variant="outline" onClick={() => setSelected(run.runId)} aria-label={`Inspect run ${run.runId}`}>Inspect</Button></td>
            </tr>)}</tbody></table></div>
          <div className="flex flex-wrap items-center justify-between gap-3 mt-4 text-sm text-slate-400"><span>{runs.data.totalElements} reports · Page {page + 1}</span>
            <div className="flex gap-2"><Button size="sm" variant="outline" disabled={!page} onClick={() => { setPage(page - 1); setSelected(null); }}>Previous reports</Button>
              <Button size="sm" variant="outline" disabled={runs.data.last || page >= 100} onClick={() => { setPage(page + 1); setSelected(null); }}>Next reports</Button></div></div>
        </>}
    </Card>
    {selected && <ReportDetail key={selected} merchantId={merchantId} admin={admin} runId={selected} close={() => setSelected(null)} />}
  </>;
}
function ReportDetail({ merchantId, admin, runId, close }: { merchantId: string; admin: boolean; runId: string; close: () => void }) {
  const [page, setPage] = useState(0);
  const [filter, setFilter] = useState<ReconciliationFilter>('NON_MATCHED');
  const [selected, setSelected] = useState<ReconciliationObservation | null>(null);
  const role = admin ? 'admin' : 'merchant';
  const detail = useQuery({ queryKey: ['reconciliation', role, merchantId, 'detail', runId],
    queryFn: ({ signal }) => reconciliationApi.detail(merchantId, admin, runId, signal), staleTime: Infinity,
    refetchOnWindowFocus: false, refetchOnReconnect: false });
  const observations = useQuery({ queryKey: ['reconciliation', role, merchantId, 'observations', runId, filter, page],
    queryFn: ({ signal }) => reconciliationApi.observations(merchantId, admin, runId, page, filter, signal), staleTime: Infinity,
    refetchOnWindowFocus: false, refetchOnReconnect: false });
  return <Card title="Immutable report" action={<Button size="sm" variant="ghost" onClick={close}>Close report</Button>}>
    {detail.isError ? <p role="alert" className="text-rose-300">Report details could not be loaded.</p> : detail.isPending ? <p role="status">Loading report…</p> : <>
      <p className="text-sm text-slate-300">{detail.data.ruleset} · {detail.data.status} · {detail.data.cohortCount} payments examined</p>
      <p className="text-sm text-slate-400 mt-2">Observed: {date(detail.data.snapshotObservedAt)} · Completed: {date(detail.data.completedAt)}</p>
      <p className="text-sm text-slate-400 mt-2">Limits: {detail.data.paymentLimit} payments, seven-day creation window, {detail.data.graceSeconds} second diagnostic grace, {detail.data.executionLimitSeconds} second execution deadline.</p>
      {!detail.data.cohortCount && <p className="text-amber-300 mt-2">No eligible records. This report makes no financial-health claim.</p>}
      <dl aria-label="Classifications with observations" className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-3 my-5">
        {reconciliationClasses.filter((type) => detail.data.counts[type] > 0).map((type) => <div key={type} className="rounded-lg border border-slate-600 bg-slate-900 p-3">
          <dt className="text-sm text-slate-300">{label(type)}</dt><dd className="mt-1 text-2xl font-semibold tabular-nums text-slate-100">{detail.data.counts[type]}</dd>
        </div>)}
      </dl>
      <details className="mb-5 text-sm text-slate-400"><summary>All classifications ({reconciliationClasses.length})</summary>
        <dl className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-x-5 gap-y-3 mt-3">{reconciliationClasses.map((type) => <div key={type} className="flex justify-between gap-3"><dt>{label(type)}</dt><dd className="font-semibold tabular-nums text-slate-200">{detail.data.counts[type] || 0}</dd></div>)}</dl>
      </details>
    </>}
    <label className="block text-sm text-slate-300">Observation class<select value={filter} onChange={(event) => { setFilter(event.target.value as ReconciliationFilter); setPage(0); setSelected(null); }}
      className="block mt-2 w-full sm:w-auto max-w-full bg-slate-900 border border-slate-700 rounded-lg p-2.5"><option value="NON_MATCHED">Nonmatched observations</option><option value="">All observations</option>
      {reconciliationClasses.map((type) => <option key={type} value={type}>{label(type)}</option>)}</select></label>
    {observations.isError ? <p role="alert" className="text-rose-300 mt-4">Observations could not be loaded.</p> : observations.isPending ? <p role="status" className="mt-4">Loading observations…</p> : <>
      {!observations.data.content.length && <p className="text-slate-400 mt-4">No observations match this view. Other classes may contain pending or unavailable evidence.</p>}
      <ul className="mt-4 divide-y divide-slate-700">{observations.data.content.map((observation) => <li key={observation.paymentId} className="py-4 text-sm text-slate-300">
        <div className="flex flex-wrap items-center justify-between gap-3"><div>{!admin ? <Link className="text-emerald-400 underline break-all" to={`/payments/${observation.paymentId}`}>{observation.paymentId}</Link> : <span className="font-mono break-all">{observation.paymentId}</span>}
          <div>{label(observation.primaryClass)} · {observation.severity}</div></div><Button variant="outline" size="sm" onClick={() => setSelected(observation)} aria-label={`Evidence ${observation.paymentId}`}>Evidence</Button></div>
        <p className="mt-2">{observation.explanation}</p>
      </li>)}</ul>
      <div className="flex flex-wrap items-center justify-between gap-3 mt-4 text-sm text-slate-400"><span>{observations.data.totalElements} observations · Page {page + 1}</span><div className="flex gap-2">
        <Button size="sm" variant="outline" disabled={!page} onClick={() => { setPage(page - 1); setSelected(null); }}>Previous observations</Button>
        <Button size="sm" variant="outline" disabled={observations.data.last || page >= 100} onClick={() => { setPage(page + 1); setSelected(null); }}>Next observations</Button></div></div>
    </>}
    {selected && <div className="mt-5 border-t border-slate-700 pt-4"><div className="flex justify-between gap-2"><h3 className="font-semibold">Observed evidence</h3>
      <Button size="sm" variant="ghost" onClick={() => setSelected(null)}>Close evidence</Button></div><Evidence evidence={selected.evidence} />
      <p className="text-xs text-slate-400 mt-3">Reasons: {selected.reasonCodes.join(', ') || 'No additional diagnostic reasons'}</p></div>}
  </Card>;
}
function Evidence({ evidence: e }: { evidence: ReconciliationEvidence }) {
  const bool = (value?: boolean) => value === undefined ? 'Not assessed' : value ? 'Yes' : 'No';
  return <dl className="grid grid-cols-1 md:grid-cols-2 gap-4 mt-3 text-sm text-slate-300">
    <div className="bg-slate-900 border border-slate-700 rounded-lg p-4"><dt className="text-slate-400">Payment amount / state</dt><dd className="text-lg font-semibold tabular-nums text-slate-100 mt-2">{amount(e.paymentAmountCents, e.paymentCurrency)}</dd><dd className="mt-1">{e.paymentStatus || 'Unavailable'} · version {e.paymentVersion ?? '—'}</dd></div>
    {e.merchantMatches && e.operationPresent && <div className="bg-slate-900 border border-slate-700 rounded-lg p-4"><dt className="text-slate-400">Simulator request / outcome</dt><dd className="text-lg font-semibold tabular-nums text-slate-100 mt-2">{amount(e.operationAmountCents, e.operationCurrency)}</dd><dd className="mt-1">{e.disposition || 'Unavailable'} · {e.evaluationCount ?? 0} evaluations</dd></div>}
    <div><dt className="text-slate-400">Stored operation / merchant binding</dt><dd>{bool(e.operationPresent)} / {bool(e.merchantMatches)}</dd></div>
    {e.merchantMatches && e.operationPresent && <div><dt className="text-slate-400">Result observed / fields agree</dt><dd>{date(e.resultAt)}</dd><dd>Reference: {bool(e.referenceMatches)} · Code: {bool(e.codeMatches)} · Message: {bool(e.messageMatches)}</dd></div>}
    <div><dt className="text-slate-400">Initiation event identities</dt><dd className="break-all">{e.eventIds?.join(', ') || 'Unavailable'}</dd></div>
    <div><dt className="text-slate-400">Diagnostic context</dt><dd>{e.contextIncomplete ? 'Incomplete; omitted or unavailable transport context does not prove financial failure.' : 'Bounded context only; transport is not financial truth.'}</dd></div>
  </dl>;
}
