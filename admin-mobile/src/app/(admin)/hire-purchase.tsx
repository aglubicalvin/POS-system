import { useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';

export default function HirePurchaseScreen() {
  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.title}>Hire Purchase</Text>
      <Text style={styles.subtitle}>Products bought on hire purchase with duration, installments, and countdown</Text>

      <View style={styles.card}>
        <Text style={styles.cardTitle}>HP Contracts</Text>
        <Text style={styles.cardBody}>
          Track: Product name, description, quantity, retail price, duration (months), deposit, installment amount, remaining balance, start/end dates, status.
        </Text>
        <Text style={styles.cardBody}>
          Countdown display: Days/months remaining until completion. Overdue contracts highlighted.
        </Text>
        <View style={styles.tableHeader}>
          <Text style={[styles.th, { flex: 2 }]}>Product</Text>
          <Text style={styles.th}>Qty</Text>
          <Text style={styles.th}>Retail Price</Text>
          <Text style={styles.th}>Duration</Text>
          <Text style={styles.th}>Installment</Text>
          <Text style={styles.th}>Remaining</Text>
          <Text style={styles.th}>Countdown</Text>
        </View>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f7f7f7' },
  content: { padding: 16, gap: 12 },
  title: { fontSize: 22, fontWeight: '700' },
  subtitle: { color: '#555' },
  card: { backgroundColor: '#fff', borderRadius: 12, padding: 16, gap: 12, shadowColor: '#000', shadowOpacity: 0.05, shadowRadius: 8 },
  cardTitle: { fontSize: 18, fontWeight: '600' },
  cardBody: { color: '#444', lineHeight: 20 },
  tableHeader: { flexDirection: 'row', borderBottomWidth: 1, borderBottomColor: '#eee', paddingBottom: 8, gap: 4 },
  th: { flex: 1, fontWeight: '600', color: '#333', fontSize: 12 }
});
