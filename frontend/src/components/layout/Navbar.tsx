import React from 'react';
import { useAuth } from '../../context/AuthContext';
import { useMerchant } from '../../context/MerchantContext';
import { Menu, X } from 'lucide-react';

interface NavbarProps { navigationOpen: boolean; toggleNavigation: () => void }
export const Navbar: React.FC<NavbarProps> = ({ navigationOpen, toggleNavigation }) => {
  const { user, logout } = useAuth();
  const { activeMerchant, setActiveMerchant, availableMerchants } = useMerchant();
  return (
    <header className="min-h-16 bg-slate-900 border-b border-slate-800 px-4 md:px-6 py-3 flex flex-wrap items-center justify-between gap-3 sticky top-0 z-30">
      <div className="flex items-center gap-2">
        <button type="button" aria-label="Toggle navigation" aria-expanded={navigationOpen} aria-controls="workspace-navigation"
          onClick={toggleNavigation} className="lg:hidden p-2 rounded-lg text-slate-300 hover:bg-slate-800">
          {navigationOpen ? <X className="w-5 h-5" /> : <Menu className="w-5 h-5" />}
        </button>
        <div className="w-8 h-8 rounded-lg bg-emerald-700 flex items-center justify-center font-bold text-white">K</div>
        <span className="font-bold text-slate-100 text-lg tracking-tight">KaiPay</span>
        <span className="text-xs text-emerald-300 bg-emerald-950/60 border border-emerald-800/50 px-1.5 py-0.5 rounded">Simulation</span>
      </div>
      <div className="flex items-center gap-3 min-w-0 max-w-full ml-auto sm:ml-0 order-2 sm:order-3">
        <span title={user?.username} className="text-sm text-slate-300 truncate max-w-40">{user?.username}</span>
        <button className="text-sm text-emerald-400 shrink-0 min-h-10 px-1" onClick={() => void logout()}>Sign out</button>
      </div>
      {availableMerchants.length > 0 && <label className="flex items-center gap-2 w-full sm:w-auto sm:ml-auto order-3 sm:order-2 text-xs text-slate-400 min-w-0">
        <span className="shrink-0">Merchant</span>
        <select aria-label="Active merchant" title={activeMerchant.id} value={activeMerchant.id}
          onChange={(event) => { const merchant = availableMerchants.find((item) => item.id === event.target.value); if (merchant) setActiveMerchant(merchant); }}
          className="min-w-0 w-full sm:w-64 max-w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2.5 text-sm text-slate-100">
          {availableMerchants.map((merchant) => <option key={merchant.id} value={merchant.id}>{merchant.name}</option>)}
        </select>
      </label>}
    </header>
  );
};
