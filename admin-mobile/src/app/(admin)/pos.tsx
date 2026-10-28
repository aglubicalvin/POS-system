import { useState } from 'react';
import { ScrollView, StyleSheet, Text, View, Pressable } from 'react-native';

export default function POSScreen() {
  const [cart, setCart] = useState<Array<{id: string, name: string, price: number, qty: number}>>([]);

  const addItem = () => {
    setCart([...cart, { id: Date.now().toString(), name: 'Sample Product', price: 1500, qty: 1 }]);
  };

  const adjustQty = (id: string, delta: number) => {
    setCart(cart.map(item => item.id === id ? { ...item, qty: Math.max(0, item.qty + delta) } : item).filter(item => item.qty > 0));
  };

  const removeItem = (id: string) => {
    setCart(cart.filter(item => item.id !== id));
  };

  const total = cart.reduce((sum, item) => sum + item.price * item.qty, 0);

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.title}>POS - Cart & Checkout</Text>
      <Text style={styles.subtitle}>Display products with images, add to cart, adjust quantities (+/-), remove items, checkout, generate receipt</Text>

      <View style={styles.row}>
        <View style={[styles.card, styles.flex2]}>
          <Text style={styles.cardTitle}>Products</Text>
          <Pressable style={styles.button} onPress={addItem}>
            <Text style={styles.buttonText}>Add Sample Product (with image support)</Text>
          </Pressable>
          <Text style={styles.cardBody}>Products displayed with images. Search and filter by category.</Text>
        </View>
        <View style={[styles.card, styles.flex1]}>
          <Text style={styles.cardTitle}>Cart</Text>
          {cart.length === 0 ? (
            <Text style={styles.cardBody}>Cart is empty</Text>
          ) : (
            cart.map(item => (
              <View key={item.id} style={styles.cartItem}>
                <View style={styles.cartItemInfo}>
                  <Text style={styles.cartItemName}>{item.name}</Text>
                  <Text style={styles.cartItemPrice}>GHS {(item.price / 100).toFixed(2)}</Text>
                </View>
                <View style={styles.cartItemActions}>
                  <Pressable style={styles.qtyBtn} onPress={() => adjustQty(item.id, -1)}><Text>-</Text></Pressable>
                  <Text style={styles.qty}>{item.qty}</Text>
                  <Pressable style={styles.qtyBtn} onPress={() => adjustQty(item.id, 1)}><Text>+</Text></Pressable>
                  <Pressable style={styles.removeBtn} onPress={() => removeItem(item.id)}><Text style={styles.removeText}>Remove</Text></Pressable>
                </View>
              </View>
            ))
          )}
          <View style={styles.totalRow}>
            <Text style={styles.totalLabel}>Total:</Text>
            <Text style={styles.totalAmount}>GHS {(total / 100).toFixed(2)}</Text>
          </View>
          <Pressable style={[styles.button, styles.checkoutBtn]} disabled={cart.length === 0}>
            <Text style={styles.buttonText}>Process Checkout & Generate Receipt</Text>
          </Pressable>
          <Text style={styles.cardBody}>Payment methods: Cash, Ghana Card, Mobile Money (MoMo), Payroll Deductions</Text>
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
  row: { flexDirection: 'row', gap: 12, flexWrap: 'wrap' },
  flex2: { flex: 2, minWidth: 300 },
  flex1: { flex: 1, minWidth: 250 },
  card: { backgroundColor: '#fff', borderRadius: 12, padding: 16, gap: 12, shadowColor: '#000', shadowOpacity: 0.05, shadowRadius: 8 },
  cardTitle: { fontSize: 18, fontWeight: '600' },
  cardBody: { color: '#444', lineHeight: 20 },
  button: { backgroundColor: '#1f6feb', padding: 12, borderRadius: 8, alignItems: 'center' },
  buttonText: { color: '#fff', fontWeight: '600' },
  checkoutBtn: { backgroundColor: '#238636' },
  cartItem: { borderBottomWidth: 1, borderBottomColor: '#eee', paddingBottom: 8, gap: 8 },
  cartItemInfo: { flexDirection: 'row', justifyContent: 'space-between' },
  cartItemName: { fontWeight: '600' },
  cartItemPrice: { color: '#666' },
  cartItemActions: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  qtyBtn: { width: 32, height: 32, backgroundColor: '#eee', borderRadius: 16, alignItems: 'center', justifyContent: 'center' },
  qty: { minWidth: 30, textAlign: 'center' },
  removeBtn: { marginLeft: 'auto', padding: 6 },
  removeText: { color: '#d1242f' },
  totalRow: { flexDirection: 'row', justifyContent: 'space-between', paddingTop: 8, borderTopWidth: 1, borderTopColor: '#eee' },
  totalLabel: { fontSize: 16, fontWeight: '600' },
  totalAmount: { fontSize: 16, fontWeight: '700' }
});
