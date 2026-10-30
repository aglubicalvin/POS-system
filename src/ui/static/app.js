/**
 * Till UI client. Thin by design: it displays catalog data and cart arithmetic
 * for the CASHIER's eyes only, but the totals that matter are the ones the
 * domain layer reprices and returns at checkout (`/api/checkout`), shown on the
 * receipt as-is.
 */
const money = (minor) => `GHS ${(minor / 100).toFixed(2)}`;

const state = {
  token: sessionStorage.getItem('till-token'),
  user: JSON.parse(sessionStorage.getItem('till-user') ?? 'null'),
  products: [],
  cart: new Map(), // productId -> { product, quantity, discount }
  /** Role-based visibility */
  showManagerActions: false,
  showAdminActions: false,
  showReports: false
};

const $ = (id) => document.getElementById(id);

async function api(path, options = {}) {
  const headers = { 'content-type': 'application/json' };
  if (state.token) headers.authorization = `Bearer ${state.token}`;
  const response = await fetch(path, { ...options, headers });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw Object.assign(new Error(body.error ?? 'request failed'), { status: response.status, body });
  }
  return body;
}

/* ------------------------------------------------------------- login/logout */

async function initSession() {
  if (!state.token || !state.user) return showLogin();
  try {
    await api('/api/products');
    showTill();
  } catch {
    showLogin();
  }
}

function showLogin() {
  state.token = null;
  state.user = null;
  sessionStorage.clear();
  $('login-view').hidden = false;
  $('till-view').hidden = true;
  api('/api/users')
    .then((users) => {
      $('login-user').innerHTML = users
        .map((u) => `<option value="${u.userId}">${u.fullName} (${u.role})</option>`)
        .join('');
    })
    .catch(() => {
      $('login-error').textContent = 'Till server unreachable.';
    });
}

async function login(event) {
  event.preventDefault();
  $('login-error').textContent = '';
  try {
    const result = await api('/api/login', {
      method: 'POST',
      body: JSON.stringify({ userId: $('login-user').value, pin: $('login-pin').value }),
    });
    state.token = result.token;
    state.user = result.user;
    sessionStorage.setItem('till-token', result.token);
    sessionStorage.setItem('till-user', JSON.stringify(result.user));
    showTill();
  } catch (error) {
    const reason = error.body?.reason;
    $('login-error').textContent =
      reason === 'bad-pin' ? 'Wrong PIN.' : reason === 'locked' ? 'Locked — wait 15 minutes.' : error.message;
  }
}

function showTill() {
  $('login-view').hidden = true;
  $('till-view').hidden = false;
  $('cashier-name').textContent = state.user.fullName;
  // Set role-based visibility
  state.showManagerActions = state.user.role === 'manager' || state.user.role === 'admin';
  state.showAdminActions = state.user.role === 'admin';
  state.showReports = state.user.role === 'admin';
  renderManagerButtons();
  state.cart.clear();
  renderCart();
  refreshProducts();
  refreshShift();
}

async function refreshShift() {
  const shift = await api('/api/shift');
  $('shift-status').textContent = shift.shiftId
    ? `Shift open ${new Date(shift.openedAt).toLocaleTimeString()}`
    : 'No open shift';
  $('open-shift').hidden = Boolean(shift.shiftId);
}

async function openShift() {
  const input = prompt('Opening float (GHS, e.g. 50.00):', '0.00');
  if (input === null) return;
  try {
    await api('/api/shift', {
      method: 'POST',
      body: JSON.stringify({ openingFloat: Math.round(Number(input) * 100) }),
    });
    refreshShift();
  } catch (error) {
    alert(error.message);
  }
}

/* ------------------------------------------------------------------ catalog */

async function refreshProducts() {
  state.products = await api('/api/products');
  $('catalog').innerHTML = state.products
    .map((p) => {
      const out = p.onHand <= 0;
      return `
      <button class="product ${out ? 'out-of-stock' : ''}" data-product="${p.productId}">
        <h3>${p.name}</h3>
        <p class="price">${money(p.unitPrice)}${p.isTaxed ? ` +${p.taxRateBp / 100}% VAT` : ''}</p>
        <p>${p.sku} &middot; ${out ? 'out of stock' : `${p.onHand} in stock`}</p>
      </button>`;
    })
    .join('');
}

/* --------------------------------------------------------------------- cart */

function addToCart(productId) {
  const product = state.products.find((p) => p.productId === productId);
  if (!product) return;
  const line = state.cart.get(productId) ?? { product, quantity: 0, discount: 0 };
  line.quantity += 1;
  state.cart.set(productId, line);
  renderCart();
}

function renderCart() {
  const lines = [...state.cart.values()];
  $('cart-lines').innerHTML = lines
    .map(
      ({ product, quantity, discount }) => `
    <li data-product="${product.productId}">
      <span class="name">${product.name}</span>
      <button data-cart="dec">&minus;</button>
      <strong>${quantity}</strong>
      <button data-cart="inc">+</button>
      <span>${money(product.unitPrice * quantity - discount)}</span>
      <button data-cart="del">&times;</button>
    </li>`,
    )
    .join('');
  $('cart-empty').hidden = lines.length > 0;
  const total = lines.reduce((sum, { product, quantity, discount }) => sum + product.unitPrice * quantity - discount, 0);
  $('cart-total').textContent = lines.length ? `Total (pre-till pricing): ${money(total)}` : '';
  $('checkout').disabled = lines.length === 0;
}

/* ----------------------------------------------------------------- checkout */

async function checkout() {
  $('checkout-error').textContent = '';
  const lines = [...state.cart.values()].map(({ product, quantity, discount }) => ({
    productId: product.productId,
    quantity,
    discount,
  }));
  const paymentMethod = $('payment-method').value;
  try {
    const receipt = await api('/api/checkout', { method: 'POST', body: JSON.stringify({ lines, paymentMethod }) });
    state.cart.clear();
    renderCart();
    showReceipt(receipt);
    refreshProducts();
    refreshShift();
    // Mobile money is confirmed by the provider, not by the cashier: show the
    // pending attempt and hand it to the provider, then follow its verdict.
    if (receipt.paymentStatus === 'pending' && receipt.paymentId) {
      await followUpMobileMoney(receipt.paymentId);
    }
  } catch (error) {
    $('checkout-error').textContent = error.message;
  }
}

/* ------------------------------------------------------------------- momo */

const MOMO_POLL_INTERVAL_MS = 2000;
const MOMO_POLL_MAX_ATTEMPTS = 5;

async function followUpMobileMoney(paymentId) {
  const status = $('payment-status');
  try {
    const initiated = await api('/api/payments/mobile-money/initiate', {
      method: 'POST',
      body: JSON.stringify({ paymentId }),
    });
    status.textContent = initiated.instructions
      ? `MoMo: awaiting approval — ${initiated.instructions}`
      : 'MoMo: awaiting provider confirmation…';
    // The payment stays pending until the PROVIDER's verdict arrives; polling
    // re-checks it, and a later sale cycle can always poll again.
    for (let attempt = 1; attempt <= MOMO_POLL_MAX_ATTEMPTS; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, MOMO_POLL_INTERVAL_MS));
      const payment = await api(`/api/payments/${encodeURIComponent(paymentId)}`);
      if (payment.status === 'confirmed') {
        status.textContent = `MoMo payment confirmed${payment.providerRef ? ` — ref ${payment.providerRef}` : ''}`;
        return;
      }
      if (payment.status === 'failed') {
        status.textContent = `MoMo payment failed: ${payment.failureReason ?? 'provider reported failure'}`;
        $('checkout-error').textContent = 'Take another form of payment or retry mobile money.';
        return;
      }
      if (payment.status === 'pending') status.textContent = `MoMo: still awaiting confirmation (check ${attempt}/${MOMO_POLL_MAX_ATTEMPTS})…`;
    }
    status.textContent = 'MoMo: still pending — the provider will confirm it; check again from this receipt.';
  } catch (error) {
    status.textContent = '';
    $('checkout-error').textContent = `Mobile money initiation failed: ${error.message}`;
  }
}

async function showReceipt(receipt) {
  // Show basic receipt info first
  $('receipt-number').textContent = `Receipt ${receipt.receiptNumber}`;
  $('receipt-lines').innerHTML = receipt.items
    .map((item) => `<li><span>${item.quantity} \u00d7 ${item.name}</span><span>${money(item.lineTotal)}</span></li>`)
    .join('');
  const parts = [];
  if (receipt.discountTotal > 0) parts.push(`Discount &minus;${money(receipt.discountTotal)}`);
  if (receipt.taxTotal > 0) parts.push(`VAT ${money(receipt.taxTotal)}`);
  parts.push(`<strong>Total ${money(receipt.total)}</strong>`);
  $('receipt-totals').innerHTML = parts.join(' &middot; ');
  $('receipt').showModal();
  
  // Fetch detailed sale data for full receipt formats
  try {
    const sale = await api(`/api/sales/${receipt.saleId}`);
    // Populate detailed receipt fields
    document.getElementById('receiptDate').textContent = new Date(sale.date).toLocaleDateString();
    document.getElementById('cashierName').textContent = sale.cashierName || 'Unknown';
    document.getElementById('customerRef').textContent = sale.customerRef || '-';
    const methodSelect = document.getElementById('payment-method');
    if (methodSelect) {
      document.getElementById('paymentOption').textContent = methodSelect.options[methodSelect.selectedIndex].text;
    } else {
      document.getElementById('paymentOption').textContent = 'Cash';
    }
    
    // Populate items with brand/serial format
    const itemTbody = document.getElementById('detailedItems');
    if (itemTbody) {
      itemTbody.innerHTML = sale.items.map((it, i) => `
        <tr>
          <td>${i + 1}</td>
          <td>${it.name}</td>
          <td>${it.brandName || '-'}</td>
          <td>${it.serialNo || '-'}</td>
          <td>${it.quantity}</td>
          <td>${money(it.costPrice)}</td>
          <td>5%</td>
          <td>${money(it.quantity * it.unit_price + it.quantity * it.unit_price * 0.05)}</td>
        </tr>
      `).join('');
    }
    // Show grand total with interest
    const grandTotal = sale.items.reduce((sum, it) => sum + it.quantity * it.unit_price + it.quantity * it.unit_price * 0.05, 0);
    document.getElementById('grandTotal').textContent = money(grandTotal);
    // Store last sale ID for future reference
    localStorage.setItem('lastSaleId', sale.saleId);
  } catch (error) {
    console.error('Failed to fetch detailed receipt:', error);
  }
}


/* -------------------------------------------------------------------- wiring */

$('login-form').addEventListener('submit', login);
$('logout').addEventListener('click', () => api('/api/logout', { method: 'POST' }).finally(showLogin));
$('open-shift').addEventListener('click', openShift);
$('checkout').addEventListener('click', checkout);
$('receipt-close').addEventListener('click', () => $('receipt').close());
$('cart-lines').addEventListener('click', (event) => {
  const button = event.target.closest('button');
  if (!button) return;
  const productId = button.closest('li').dataset.product;
  const line = state.cart.get(productId);
  if (button.dataset.cart === 'inc') line.quantity += 1;
  if (button.dataset.cart === 'dec' && line.quantity > 0) line.quantity -= 1;
  if (button.dataset.cart === 'del' || line.quantity === 0) state.cart.delete(productId);
  renderCart();
});
$('catalog').addEventListener('click', (event) => {
  const card = event.target.closest('.product');
  if (card && !card.classList.contains('out-of-stock')) addToCart(card.dataset.product);
});

function renderManagementButtons() {
  const mBtns = $('manager-actions');
  const aBtns = $('admin-actions');
  const sBtn = $('sales-report');
  if (state.showManagerActions) {
    mBtns.hidden = false;
  } else {
    mBtns.hidden = true;
  }
  if (state.showAdminActions) {
    aBtns.hidden = false;
  } else {
    aBtns.hidden = true;
  }
  if (state.showReports) {
    sBtn.hidden = false;
  } else {
    sBtn.hidden = true;
  }
}

initSession();

// Sales report click handler
$('sales-report').addEventListener('click', async () => {
  const report = await api('/api/reports/sales');
  // Simple table display in an alert for now
  let rows = '';
  report.forEach((sale, i) => {
    rows += `<tr><td>${i + 1}</td><td>${sale.receiptNumber}</td><td>${new Date(sale.date).toLocaleDateString()}</td><td>${sale.cashierName}</td><td>${money(sale.total)}</td></tr>`;
  });
  const table = `<table style="width: 100%; border-collapse: collapse; margin-top: 1rem;"><thead><tr><th style="padding: 0.5rem; border: 1px solid #000; text-align: left;">#</th><th style="padding: 0.5rem; border: 1px solid #000; text-align: left;">Receipt</th><th style="padding: 0.5rem; border: 1px solid #000; text-align: left;">Date</th><th style="padding: 0.5rem; border: 1px solid #000; text-align: left;">Cashier</th><th style="padding: 0.5rem; border: 1px solid #000; text-align: left;">Total</th></tr></thead><tbody>${rows}</tbody></table>`;
  alert(`Sales Report\n\n${table}`);
});
