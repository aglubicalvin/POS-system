import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, Alert, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { formatPeso } from '@/lib/summary';
import { listCustomers, listPayroll, listPurchases, recordPayrollDeduction, saveCustomer, setPayrollStatus, type Customer, type PaymentChannel, type PayrollDeduction, type Purchase } from '@/lib/phase43';

type Tab = 'customers' | 'payroll' | 'history';
const channels: PaymentChannel[] = ['cash', 'ghana_card', 'payroll_deduction', 'momo'];
const monthNow = () => new Date().toISOString().slice(0, 7);

export default function CustomersScreen() {
  const [tab, setTab] = useState<Tab>('customers');
  const [search, setSearch] = useState('');
  const [month, setMonth] = useState(monthNow());
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [deductions, setDeductions] = useState<PayrollDeduction[]>([]);
  const [purchases, setPurchases] = useState<Purchase[]>([]);
  const [selected, setSelected] = useState<Customer | null>(null);
  const [name, setName] = useState('');
  const [staff, setStaff] = useState('');
  const [company, setCompany] = useState('');
  const [phone, setPhone] = useState('');
  const [channel, setChannel] = useState<PaymentChannel>('cash');
  const [amount, setAmount] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    setError(null);
    try {
      const [c, h] = await Promise.all([listCustomers(search), listPurchases(search)]);
      setCustomers(c);
      setPurchases(h);
      if (tab === 'payroll') setDeductions(await listPayroll(month));
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not load customer data'); }
  }, [month, search, tab]);
  useEffect(() => { void reload(); }, [reload]);

  function chooseCustomer(customer: Customer) {
    setSelected(customer); setName(customer.full_name); setStaff(customer.staff_number ?? '');
    setCompany(customer.company ?? ''); setPhone(customer.phone ?? ''); setChannel(customer.payment_method_preference);
  }
  async function save() {
    if (!name.trim()) { setError('Customer name is required.'); return; }
    setBusy(true); setError(null);
    try {
      await saveCustomer({ full_name: name.trim(), staff_number: staff.trim() || null, company: company.trim() || null,
        phone: phone.trim() || null, email: selected?.email ?? null, payment_method_preference: channel, is_active: selected?.is_active ?? true }, selected?.customer_id);
      setSelected(null); setName(''); setStaff(''); setCompany(''); setPhone(''); await reload();
    } catch (e) { setError(e instanceof Error ? e.message : 'Customer save failed'); }
    finally { setBusy(false); }
  }
  async function addDeduction() {
    if (!selected) { setError('Select a customer from the registry first.'); return; }
    const parsed = Number(amount);
    if (!Number.isFinite(parsed) || parsed <= 0) { setError('Enter a positive GHS amount.'); return; }
    setBusy(true); setError(null);
    try {
      await recordPayrollDeduction({ customer_id: selected.customer_id, sale_id: null, amount: Math.round(parsed * 100), deduction_month: month, notes: null });
      setAmount(''); setDeductions(await listPayroll(month));
    } catch (e) { setError(e instanceof Error ? e.message : 'Deduction could not be created'); }
    finally { setBusy(false); }
  }
  async function updateStatus(row: PayrollDeduction, status: PayrollDeduction['status']) {
    try { await setPayrollStatus(row.deduction_id, status); setDeductions(await listPayroll(month)); }
    catch (e) { Alert.alert('Update failed', e instanceof Error ? e.message : 'Could not update deduction'); }
  }

  return <ScrollView style={styles.container} contentContainerStyle={styles.content}>
    <Text style={styles.title}>Customers & Payroll</Text>
    <Text style={styles.subtitle}>Identify payroll customers with company staff numbers. All amounts are stored in pesewas.</Text>
    <View style={styles.tabs}>{(['customers', 'payroll', 'history'] as Tab[]).map((value) => <Pressable key={value} style={[styles.tab, tab === value && styles.activeTab]} onPress={() => setTab(value)}><Text style={[styles.tabText, tab === value && styles.activeText]}>{value === 'customers' ? 'Customers' : value === 'payroll' ? 'Payroll' : 'Purchase history'}</Text></Pressable>)}</View>
    {error ? <Text style={styles.error}>{error}</Text> : null}
    {tab !== 'payroll' ? <TextInput style={styles.input} placeholder="Search name, staff number, receipt" value={search} onChangeText={setSearch} /> : null}

    {tab === 'customers' ? <>
      <View style={styles.card}>
        <Text style={styles.section}>Customer registry {customers.length ? `(${customers.length})` : ''}</Text>
        <TextInput style={styles.input} placeholder="Full name" value={name} onChangeText={setName} />
        <TextInput style={styles.input} placeholder="Company staff number (optional)" value={staff} onChangeText={setStaff} autoCapitalize="characters" />
        <TextInput style={styles.input} placeholder="Company" value={company} onChangeText={setCompany} />
        <TextInput style={styles.input} placeholder="Phone" value={phone} onChangeText={setPhone} keyboardType="phone-pad" />
        <Text style={styles.muted}>Preferred payment channel</Text>
        <View style={styles.tabs}>{channels.map((item) => <Pressable key={item} onPress={() => setChannel(item)} style={[styles.tab, channel === item && styles.activeTab]}><Text style={[styles.tabText, channel === item && styles.activeText]}>{item.replace('_', ' ')}</Text></Pressable>)}</View>
        <View style={styles.tabs}><Pressable style={styles.button} onPress={() => void save()} disabled={busy}><Text style={styles.buttonText}>{busy ? 'Saving…' : selected ? 'Save customer' : 'Add customer'}</Text></Pressable>{selected ? <Pressable style={styles.buttonSecondary} onPress={() => { setSelected(null); setName(''); setStaff(''); setCompany(''); setPhone(''); }}><Text>Clear selection</Text></Pressable> : null}</View>
      </View>
      {customers.map((row) => <Pressable key={row.customer_id} style={styles.card} onPress={() => chooseCustomer(row)}>
        <Text style={styles.section}>{row.full_name}</Text><Text style={styles.muted}>Staff #: {row.staff_number ?? '—'} · {row.company ?? 'No company'}</Text><Text style={styles.muted}>{row.phone ?? 'No phone'} · {row.payment_method_preference.replaceAll('_', ' ')}</Text>
      </Pressable>)}
      {!customers.length ? <Text style={styles.muted}>No matching customers.</Text> : null}
    </> : null}

    {tab === 'payroll' ? <>
      <View style={styles.card}>
        <Text style={styles.section}>Monthly deduction report</Text>
        <TextInput style={styles.input} value={month} onChangeText={setMonth} placeholder="YYYY-MM" maxLength={7} />
        <Text style={styles.muted}>Create a pending deduction for the selected customer</Text>
        <TextInput style={styles.input} placeholder="Search customer by name/staff number" value={search} onChangeText={setSearch} />
        {customers.map((row) => <Pressable key={row.customer_id} style={[styles.pick, selected?.customer_id === row.customer_id && styles.selectedPick]} onPress={() => chooseCustomer(row)}><Text>{row.full_name} · #{row.staff_number ?? 'no staff number'}</Text></Pressable>)}
        <TextInput style={styles.input} placeholder="Amount (GHS)" value={amount} onChangeText={setAmount} keyboardType="decimal-pad" />
        <Pressable style={styles.button} disabled={busy || !selected} onPress={() => void addDeduction()}><Text style={styles.buttonText}>{busy ? 'Saving…' : `Add deduction${selected ? ` for ${selected.full_name}` : ''}`}</Text></Pressable>
      </View>
      <Text style={styles.muted}>Report total: {formatPeso(deductions.filter((d) => d.status !== 'cancelled').reduce((sum, d) => sum + Number(d.amount), 0))}</Text>
      {deductions.map((row) => <View key={row.deduction_id} style={styles.card}><Text style={styles.section}>{row.customers?.full_name ?? row.customer_id} · {formatPeso(Number(row.amount))}</Text><Text style={styles.muted}>Staff #: {row.customers?.staff_number ?? '—'} · {row.deduction_month} · {row.status}</Text><View style={styles.tabs}>{row.status === 'pending' ? <Pressable style={styles.button} onPress={() => void updateStatus(row, 'processed')}><Text style={styles.buttonText}>Mark processed</Text></Pressable> : null}{row.status === 'processed' ? <Pressable style={styles.button} onPress={() => void updateStatus(row, 'paid')}><Text style={styles.buttonText}>Mark paid</Text></Pressable> : null}</View></View>)}
      {!deductions.length ? <Text style={styles.muted}>No deductions recorded for {month}.</Text> : null}
    </> : null}

    {tab === 'history' ? <>
      {customers.filter((c) => c.staff_number || search).map((c) => <Pressable key={c.customer_id} style={[styles.pick, selected?.customer_id === c.customer_id && styles.selectedPick]} onPress={() => setSelected(c)}><Text>{c.full_name} · staff #{c.staff_number ?? '—'}</Text></Pressable>)}
      {purchases.filter((p) => !selected || p.customer_id === selected.customer_id).map((p) => <View key={p.sale_id} style={styles.card}><Text style={styles.section}>{p.receipt_number} · {formatPeso(Number(p.total))}</Text><Text style={styles.muted}>{new Date(p.created_at).toLocaleString()} · {p.staff_number ?? 'No staff number'} · {p.payment_channel?.replaceAll('_', ' ') ?? 'unknown'}</Text></View>)}
      {!purchases.length ? <Text style={styles.muted}>No matching purchase history.</Text> : null}
    </> : null}
    {busy && tab !== 'customers' ? <ActivityIndicator /> : null}
  </ScrollView>;
}

const styles = StyleSheet.create({ container: { flex: 1, backgroundColor: '#f7f7f7' }, content: { padding: 16, gap: 12 }, title: { fontSize: 22, fontWeight: '700' }, subtitle: { color: '#555' }, tabs: { flexDirection: 'row', gap: 8, flexWrap: 'wrap' }, tab: { paddingHorizontal: 10, paddingVertical: 8, backgroundColor: '#e5e5e5', borderRadius: 8 }, activeTab: { backgroundColor: '#1f6feb' }, tabText: { color: '#333', textTransform: 'capitalize' }, activeText: { color: '#fff', fontWeight: '600' }, input: { backgroundColor: '#fff', borderRadius: 8, padding: 11, borderWidth: 1, borderColor: '#ddd' }, card: { backgroundColor: '#fff', borderRadius: 12, padding: 14, gap: 8 }, section: { fontSize: 16, fontWeight: '600' }, muted: { color: '#666' }, error: { color: '#c0392b' }, button: { backgroundColor: '#1f6feb', borderRadius: 8, padding: 10 }, buttonText: { color: '#fff', fontWeight: '600' }, buttonSecondary: { backgroundColor: '#eee', borderRadius: 8, padding: 10 }, pick: { backgroundColor: '#fff', borderRadius: 8, padding: 10 }, selectedPick: { borderWidth: 2, borderColor: '#1f6feb' } });
