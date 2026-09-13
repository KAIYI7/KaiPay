import React, { createContext, useContext, useState, useEffect } from 'react';
import { DEV_MERCHANTS, DevMerchant } from '../config/devMerchants';

interface MerchantContextType {
  activeMerchant: DevMerchant;
  setActiveMerchant: (merchant: DevMerchant) => void;
  availableMerchants: DevMerchant[];
}

const LOCAL_STORAGE_KEY = 'kaipay_active_merchant_id';

const MerchantContext = createContext<MerchantContextType | undefined>(undefined);

export const MerchantProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [activeMerchant, setActiveMerchantState] = useState<DevMerchant>(() => {
    const savedId = localStorage.getItem(LOCAL_STORAGE_KEY);
    const found = DEV_MERCHANTS.find((m) => m.id === savedId);
    return found || DEV_MERCHANTS[0];
  });

  const setActiveMerchant = (merchant: DevMerchant) => {
    setActiveMerchantState(merchant);
    localStorage.setItem(LOCAL_STORAGE_KEY, merchant.id);
  };

  useEffect(() => {
    localStorage.setItem(LOCAL_STORAGE_KEY, activeMerchant.id);
  }, [activeMerchant]);

  return (
    <MerchantContext.Provider
      value={{
        activeMerchant,
        setActiveMerchant,
        availableMerchants: DEV_MERCHANTS,
      }}
    >
      {children}
    </MerchantContext.Provider>
  );
};

export const useMerchant = (): MerchantContextType => {
  const context = useContext(MerchantContext);
  if (!context) {
    throw new Error('useMerchant must be used within a MerchantProvider');
  }
  return context;
};
