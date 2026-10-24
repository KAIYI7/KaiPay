import React from 'react';
import { useAuth } from '../../context/AuthContext';
import { NavLink } from 'react-router-dom';
import { LayoutDashboard, CreditCard, FlaskConical, Layers, AlertOctagon, BookOpen, Webhook, ListChecks } from 'lucide-react';

export const Sidebar: React.FC<{ open: boolean; onNavigate: () => void }> = ({ open, onNavigate }) => {
  const { user } = useAuth();
  const isAdmin = user?.roles.includes('ADMIN');
  const isMerchant = user?.roles.includes('MERCHANT');
  const paymentCoreItems = [
    { label: 'Dashboard', path: '/dashboard', icon: LayoutDashboard },
    { label: 'Payments', path: '/payments', icon: CreditCard },
    { label: 'Testing Sandbox', path: '/sandbox', icon: FlaskConical },
    { label: 'Webhooks', path: '/webhooks', icon: Webhook },
    { label: 'Outbox Stream', path: '/events/outbox', icon: Layers },
    { label: 'DLT Explorer', path: '/events/dlt', icon: AlertOctagon },
  ].filter((item) => item.path.startsWith('/events') ? isAdmin : isMerchant);

  const financialCoreItems = [
    { label: 'Ledger & Accounts', path: '/ledger', icon: BookOpen },
    { label: 'Reconciliation', path: isAdmin ? '/admin/reconciliation' : '/reconciliation', icon: ListChecks },
  ];

  return (
    <aside id="workspace-navigation" className={`${open ? 'flex' : 'hidden'} lg:flex w-full lg:w-60 bg-slate-900/95 border-b lg:border-b-0 lg:border-r border-slate-800 flex-col justify-between gap-6 p-4 shrink-0`}>
      <div className="space-y-6">
        {/* Payment Core Navigation */}
        <div>
          <div className="text-xs font-semibold text-slate-400 uppercase tracking-wider px-3 mb-2">
            {isMerchant ? 'Merchant workspace' : 'Operator diagnostics'}
          </div>
          <nav aria-label="Payment workspace" className="space-y-1">
            {paymentCoreItems.map((item) => {
              const Icon = item.icon;
              return (
                <NavLink
                  key={item.path}
                  to={item.path}
                  onClick={onNavigate}
                  className={({ isActive }) =>
                    `flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${
                      isActive
                        ? 'bg-emerald-600/15 text-emerald-400 border border-emerald-500/20'
                        : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
                    }`
                  }
                >
                  <Icon className="w-4 h-4" />
                  <span>{item.label}</span>
                </NavLink>
              );
            })}
          </nav>
        </div>

        {/* Accounting and report navigation */}
        <div>
          <div className="text-xs font-semibold text-slate-400 uppercase tracking-wider px-3 mb-2">
            Accounting & reports
          </div>
          <nav aria-label="Accounting and reports" className="space-y-1">
            {financialCoreItems.map((item) => {
              const Icon = item.icon;
              return (
                <NavLink
                  key={item.path}
                  to={item.path}
                  onClick={onNavigate}
                  className={({ isActive }) =>
                    `flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${
                      isActive
                        ? 'bg-emerald-600/15 text-emerald-400 border border-emerald-500/20'
                        : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
                    }`
                  }
                >
                  <Icon className="w-4 h-4" />
                  <span>{item.label}</span>
                </NavLink>
              );
            })}
          </nav>
        </div>
      </div>

      <div className="p-3 bg-slate-800/40 border border-slate-800 rounded-xl">
        <div className="text-xs font-semibold text-slate-300">Simulated payments</div>
        <div className="text-xs text-slate-400 mt-0.5">No real money movement</div>
      </div>
    </aside>
  );
};
