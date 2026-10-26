# Roles and permissions

Three roles: `cashier`, `manager`, `admin`. Capabilities are declared once in
`src/domain/permissions.ts` and enforced in the domain layer, not in the UI — a
missed screen cannot grant a permission the domain refuses.

## Capability matrix

| Capability | Cashier | Manager | Admin |
| --- | :---: | :---: | :---: |
| `sale.create` | yes | yes | yes |
| `sale.discount` (within limit) | yes | yes | yes |
| `sale.discount.any` (no limit) | — | yes | yes |
| `price.override` | — | yes | yes |
| `sale.void` | — | yes | yes |
| `sale.refund` | — | yes | yes |
| `stock.adjust` (small) | — | yes | yes |
| `stock.adjust.large` | — | — | yes |
| `stock.disburse` | — | yes | yes |
| `grn.receive` | yes | yes | yes |
| `shift.open` / `shift.close` | yes | yes | yes |
| `shift.approve` (variance) | — | yes | yes |
| `payment.reconcile` | — | yes | yes |
| `report.view` | yes | yes | yes |
| `user.manage`, `device.register` | — | register | yes |

## Thresholds

| Rule | Value | Behaviour |
| --- | --- | --- |
| Cashier unaided discount ceiling | 5% (`CASHIER_MAX_DISCOUNT_BP`) | Enforced by `checkDiscount` |
| Discount needing approval | above 20% (`MANAGER_APPROVAL_DISCOUNT_BP`) | Returns `needsApproval`, requires an `approvals` row |
| Large stock adjustment | above 10 units (`LARGE_ADJUSTMENT_QTY`) | Requires `stock.adjust.large` or an approval |
| Cash variance needing approval | above GHS 5.00 (`VARIANCE_APPROVAL_THRESHOLD`) | Shift cannot close without an approver |

## Why approvals are persisted, not booleans

`checkDiscount` returns *"allowed but needs approval"* rather than throwing, so
the UI can prompt for a manager PIN instead of dead-ending the cashier. The
approval itself is a row in `approvals` recording requester, approver, action and
reason. `assertApproval` re-checks that the approval exists, matches the action,
and belongs to the same device — a boolean passed from the client would not.

The approver's *role* is re-validated at use time from the database, so demoting
a user revokes their ability to sign off immediately.

## Notes on the current state

- A cashier PIN is bound to a device at enrolment (`users.device_id`). Presenting
  a valid PIN on another till returns `device-mismatch` without revealing whether
  the PIN was correct.
- Lockout: 5 failed attempts (`MAX_FAILED_ATTEMPTS`) locks the account for 15
  minutes (`LOCKOUT_MS`). A successful PIN clears the counter.
- `mfa_enabled` is stored on the user and set for admins in fixtures, but no MFA
  challenge flow is implemented yet — see the roadmap.
