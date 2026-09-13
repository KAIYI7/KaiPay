export interface DevCustomer {
  id: string;
  name: string;
  email: string;
}

export interface DevMerchant {
  id: string;
  name: string;
  status: 'ACTIVE' | 'SUSPENDED';
  apiKeyPrefix: string;
  description: string;
  defaultCustomers: DevCustomer[];
}

/**
 * Pre-seeded Development Merchants and Customers
 * Used exclusively for the Phase 1 Simulation Mode / Multi-Tenant Test Harness.
 */
export const DEV_MERCHANTS: DevMerchant[] = [
  {
    id: '11111111-1111-1111-1111-111111111111',
    name: 'Acme E-Commerce Corp',
    status: 'ACTIVE',
    apiKeyPrefix: 'kp_live_acme_***',
    description: 'Primary active merchant for sandbox payment flows',
    defaultCustomers: [
      {
        id: '22222222-2222-2222-2222-222222222221',
        name: 'Alice Wonderland',
        email: 'alice@example.com',
      },
      {
        id: '22222222-2222-2222-2222-222222222222',
        name: 'Bob Miller',
        email: 'bob@example.com',
      },
    ],
  },
  {
    id: '33333333-3333-3333-3333-333333333333',
    name: 'Global Retailers Ltd',
    status: 'ACTIVE',
    apiKeyPrefix: 'kp_live_global_***',
    description: 'Secondary merchant to verify cross-tenant data isolation',
    defaultCustomers: [
      {
        id: '44444444-4444-4444-4444-444444444441',
        name: 'Charlie Brown',
        email: 'charlie@example.com',
      },
    ],
  },
];
