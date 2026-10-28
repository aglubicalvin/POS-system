import { useState } from 'react';
import { ScrollView, StyleSheet, Text, TextInput, View, Pressable } from 'react-native';

export default function CustomersScreen() {
  const [search, setSearch] = useState('');
  const [tab, setTab] = useState<'customers' | 'payroll' | 'history'>('customers');

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.title}>Customers & Payroll Deductions</Text>
      <Text style={styles.subtitle}>Track customers by staff number, payment method (Cash/Ghana Card/Payroll/MoMo), and monthly deductions</Text>

      <View style={styles.tabs}>
        <Pressable style={[styles.tab, tab === 'customers' && styles.tabActive]} onPress={() => setTab('customers')}>
          <Text style={[styles.tabText, tab === 'customers' && styles.tabTextActive]}>Customers</Text>
        </Pressable>
        <Pressable style={[styles.tab, tab === 'payroll' && styles.tabActive]} onPress={() => setTab('payroll')}>
          <Text style={[styles.tabText, tab === 'payroll' && styles.tabTextActive]}>Payroll Deductions</Text>
        </Pressable>
        <Pressable style={[styles.tab, tab === 'history' && styles.tabActive]} onPress={() => setTab('history')}>
          <Text style={[styles.tabText, tab === 'history' && styles.tabTextActive]}>Purchase History</Text>
        </Pressable>
      </View>

      <TextInput
        style={styles.search}
        placeholder="Search by staff number, name, or company..."
        value={search}
        onChangeText={setSearch}
      />

      <View style={styles.card}>
        <Text style={styles.cardTitle}>
          {tab === 'customers' && 'Customer Registry'}
          {tab === 'payroll' && 'Monthly Payroll Deductions'}
          {tab === 'history' && 'Customer Purchase History'}
        </Text>
        <Text style={styles.cardBody}>
          {tab === 'customers' && 'Manage customers identified by company staff numbers. Track payment preferences (Cash, Ghana Card, Payroll Deductions, MoMo).'}
          {tab === 'payroll' && 'Report and history of customers on monthly deductions of items bought through payroll deductions.'}
          {tab === 'history' && 'View purchase history filtered by customer, staff number, and payment method.'}
        </Text>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f7f7f7' },
  content: { padding: 16, gap: 12 },
  title: { fontSize: 22, fontWeight: '700' },
  subtitle: { color: '#555' },
  tabs: { flexDirection: 'row', gap: 8, flexWrap: 'wrap' },
  tab: { paddingHorizontal: 12, paddingVertical: 8, backgroundColor: '#e5e5e5', borderRadius: 8 },
  tabActive: { backgroundColor: '#1f6feb' },
  tabText: { color: '#333' },
  tabTextActive: { color: '#fff', fontWeight: '600' },
  search: { backgroundColor: '#fff', borderRadius: 8, padding: 12, borderWidth: 1, borderColor: '#ddd' },
  card: { backgroundColor: '#fff', borderRadius: 12, padding: 16, gap: 8, shadowColor: '#000', shadowOpacity: 0.05, shadowRadius: 8 },
  cardTitle: { fontSize: 18, fontWeight: '600' },
  cardBody: { color: '#444', lineHeight: 20 }
});
