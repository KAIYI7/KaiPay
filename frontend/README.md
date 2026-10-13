# KaiPay frontend

From this directory:

```sh
npm ci
npm run dev
npm test
npm run build
```

The development server proxies `/v1` to the local backend on port 28080.

Payment detail and refund caches include the selected merchant and payment ID.
Requests and capture/refund completion use the merchant captured when the action
started. Switching merchant or payment resets the detail view's forms, modals and
action messages, while retaining separate merchant caches.

The component regressions use the real merchant context and query client with
mock HTTP responses, including foreign-merchant 404s and delayed reads/mutations.
They verify frontend cache and state behavior; backend authorization and payment
processing require their own checks. The development merchant selector and
`X-Merchant-Id` header do not authenticate users.
