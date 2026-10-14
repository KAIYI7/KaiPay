import React, { createContext, useContext, useState } from 'react';
import { MerchantMembership } from './AuthContext';
interface MerchantContextType {
  activeMerchant: MerchantMembership;
  setActiveMerchant: (merchant: MerchantMembership) => void;
  availableMerchants: MerchantMembership[];
}
const MerchantContext = createContext<MerchantContextType | undefined>(undefined);
export function MerchantProvider({ children, memberships }: { children: React.ReactNode; memberships: MerchantMembership[] }) {
  const [selected, setSelected] = useState(memberships[0]?.id);
  const activeMerchant = memberships.find((merchant) => merchant.id === selected) || memberships[0] || { id: '', name: 'Administrator' };
  const setActiveMerchant = (merchant: MerchantMembership) => {
    if (!memberships.some((membership) => membership.id === merchant.id)) throw new Error('Merchant is not an assigned membership.');
    setSelected(merchant.id);
  };
  return <MerchantContext.Provider value={{ activeMerchant, setActiveMerchant, availableMerchants: memberships }}>{children}</MerchantContext.Provider>;
}
export const useMerchant = (): MerchantContextType => {
  const context = useContext(MerchantContext);
  if (!context) throw new Error('useMerchant must be used within a MerchantProvider');
  return context;
};
