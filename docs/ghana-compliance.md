# Ghana compliance notes

Researched October 2026. Regulatory positions change and this is not legal advice.
Each item ends with the question to confirm with the GRA, the Commission, or the
aggregator before the pilot goes live.

## 1. GRA E-VAT / electronic invoicing

**Position as researched.** The Ghana Revenue Authority's E-VAT system — an
electronic system for issuing VAT receipts — took effect on 1 January 2026, and
2026 is described as full enforcement, phasing out manual VAT booklets. Under the
certified invoicing scheme, a legally valid VAT invoice is one that has received
a **clearance number, digital signature, and QR code from the GRA**; invoices
issued outside the system are not valid VAT invoices. B2B e-invoicing is described
as involving real-time validation.

Sources:
- GRA, E-VAT — <https://gra.gov.gh/e-services/e-vat/>
- GRA, VAT (incl. "scan the QR code to verify") — <https://gra.gov.gh/domestic-tax/tax-types/vat/>
- E-invoicing in Ghana: E-VAT requirements guide — <https://dddinvoices.com/learn/e-invoicing-in-ghana>
- Ghana E-VAT guide 2026 — <https://www.powersoftsystem.com/post/ghana-e-vat-guide-2026>

**What this means for the build.** Our receipt (`src/domain/receipts.ts`) renders
a correct-looking thermal document with VAT broken out per line, but it is *not*
an E-VAT invoice: there is no clearance number, no GRA digital signature, and no
QR code. Two shapes of integration are possible and they are not equivalent:

- **API submission per invoice** — the till (or the server) submits each sale to
  GRA and stores the returned clearance number and signature on the sale.
- **Certified device/SDK** — the invoice is produced through GRA-certified
  hardware or software.

**Confirm before pilot:** whether this shop's VAT registration triggers E-VAT
obligation, which of the two integration shapes GRA requires for retail POS, the
offline behaviour when a clearance cannot be fetched (does an offline receipt
still count as issued?), and the current statutory VAT rate and any COVID-levy or
GETFund components to model. Note that `tax_rate_bp` lives per product, so rate
changes are a data change, not a code change.

## 2. Mobile money aggregators

**Position as researched.** Ghanaian retail mobile money is normally taken
through an aggregator rather than direct integrations per network. Commonly cited
options with Ghana mobile-money support and webhooks: **Hubtel**, **Paystack**,
and **Flutterwave** (Flutterwave documents collecting GHS directly from MoMo
wallets across networks). MTN's own MoMo API is also available but is
single-network, which means three integrations instead of one.

Sources:
- Flutterwave, Ghana mobile money — <https://developer.flutterwave.com/docs/ghana>
- Paystack, payment channels — <https://paystack.com/docs/payments/payment-channels/>
- Aggregator vs direct comparison — <https://www.facebook.com/zachryIT/posts/27766229239628563/>

**How the code is already shaped for this.** `payments` is a separate state
machine (`pending -> confirmed|failed`) with a `UNIQUE (idempotency_key)`, a
`provider`/`provider_ref` pair, and `reconcilePayment()` which compares against
the provider's statement and reports an `amount-mismatch` or `reference-mismatch`
*without mutating the sale*. A webhook handler only needs to call
`confirmPayment(paymentId, providerRef)`; duplicate webhook deliveries are
no-ops, which is the exact failure mode these providers exhibit.

**Confirm before pilot:** which aggregator, their settlement/payout timing and
statement format for reconciliation, their webhook retry and signature-verification
scheme, and per-transaction fees so the drawer maths in `expectedCash` treats
settled-net vs gross correctly.

## 3. Data Protection Act, 2012 (Act 843)

**Position as researched.** Section 27(1) makes it mandatory for a data
controller who intends to process personal data to register with the Data
Protection Commission; registration is renewed every two years, and appointing a
Data Protection Officer is expected.

Sources:
- Data Protection Act 2012 (Act 843), full text — <https://nca.org.gh/wp-content/uploads/2020/09/Data-Protection-Act-2012.pdf>
- Ghana DPA overview (registration + 2-year renewal + DPO) — <https://www.aosphere.com/products/data-privacy/rulefinder-data-privacy/data-privacy-ghana/>
- DLA Piper, data protection laws in Ghana — <https://www.dlapiperdataprotection.com/index.html?t=law&c=GH>

**How this touches the POS.** Personal data here is limited but real: `users`
(full name, PIN hash, password hash), `devices` (model, last seen), and any
`customer_ref` or loyalty/MSISDN attached to a sale. Design choices that limit
exposure: PINs and passwords are never stored in the clear, device tokens are
stored hashed, customer identifiers on a sale are an opaque `customer_ref` rather
than a name, and the audit log stores JSON that the app controls.

**Confirm before pilot:** whether the shop must register as a data controller,
retention periods for sales and audit data, whether an MSISDN captured for a MoMo
payment counts as personal data requiring a lawful basis, and breach-notification
duties.

## 4. Not yet researched

- Payment-card data (if card acceptance is added, PCI DSS scope applies).
- Local taxation beyond VAT (e.g. GETFund/COVID levy composition of the rate).
- Employment/record-retention duties for shift and payroll-adjacent data.
