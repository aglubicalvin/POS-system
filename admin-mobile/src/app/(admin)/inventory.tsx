import { ScrollView, StyleSheet, Text, View } from 'react-native';

export default function InventoryScreen() {
  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.title}>Inventory Tracking</Text>
      <Text style={styles.subtitle}>Stock levels, movements, alerts, and valuation</Text>

      <View style={styles.card}>
        <Text style={styles.cardTitle}>Inventory Overview</Text>
        <Text style={styles.cardBody}>
          Track stock on hand, stock movements, low stock alerts, reorder points, and inventory valuation. Improved tracking with product images and better visibility.
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
  card: { backgroundColor: '#fff', borderRadius: 12, padding: 16, gap: 8, shadowColor: '#000', shadowOpacity: 0.05, shadowRadius: 8 },
  cardTitle: { fontSize: 18, fontWeight: '600' },
  cardBody: { color: '#444', lineHeight: 20 }
});
