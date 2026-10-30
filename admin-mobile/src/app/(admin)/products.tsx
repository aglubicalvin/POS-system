import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  FlatList,
  Modal,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { Image } from 'expo-image';
import {
  applyCategory,
  applyPriceChange,
  categoriesOf,
  fetchCatalog,
  type CatalogProduct,
} from '@/lib/admin-products';
import { updateProductMedia } from '@/lib/phase43';
import {
  formatPeso,
} from '@/lib/summary';
import {
  isPriceChange,
  validateProductEdit,
  type EditValidation,
} from '@/lib/product-management';

/**
 * Product & price management (Phase 4.2). Reads the admin catalog view and
 * writes ONLY through the authorized RPCs, which record a price audit row
 * server-side in the same transaction — the app itself holds no write grant
 * on `products`.
 */
export default function Products() {
  const [products, setProducts] = useState<CatalogProduct[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [category, setCategory] = useState<string | null>(null);
  const [editing, setEditing] = useState<CatalogProduct | null>(null);

  const load = useCallback(async () => {
    try {
      setError(null);
      setProducts(await fetchCatalog());
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load catalog');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const categories = useMemo(() => categoriesOf(products), [products]);
  const filtered = useMemo(
    () =>
      products.filter((p) => {
        if (category && p.category !== category) return false;
        const q = search.trim().toLowerCase();
        if (!q) return true;
        return p.name.toLowerCase().includes(q) || p.sku.toLowerCase().includes(q);
      }),
    [products, category, search],
  );

  if (loading) {
    return (
      <View style={styles.center}>
        <ActivityIndicator size="large" />
      </View>
    );
  }

  return (
    <View style={styles.container}>
      {error ? <Text style={styles.error}>{error}</Text> : null}
      <TextInput
        style={styles.search}
        placeholder="Search name or SKU"
        placeholderTextColor="#888"
        value={search}
        onChangeText={setSearch}
        autoCapitalize="none"
      />
      <View style={styles.chips}>
        <Chip label="All" active={category === null} onPress={() => setCategory(null)} />
        {categories.map((c) => (
          <Chip key={c} label={c} active={category === c} onPress={() => setCategory(c)} />
        ))}
      </View>
      <FlatList
        data={filtered}
        keyExtractor={(p) => p.product_id}
        ItemSeparatorComponent={Separator}
        renderItem={({ item }) => (
          <ProductRow
            product={item}
            onEdit={() => setEditing(item)}
          />
        )}
      />
      {editing ? (
        <EditModal
          product={editing}
          onClose={() => setEditing(null)}
          onSaved={async () => {
            setEditing(null);
            await load();
          }}
        />
      ) : null}
    </View>
  );
}

function ProductRow({ product, onEdit }: { product: CatalogProduct; onEdit: () => void }) {
  const low = product.quantity !== null && product.quantity <= product.low_stock_threshold;
  return (
    <View style={styles.row}>
      {product.thumbnail_url || product.image_url ? (
        <Image source={{ uri: product.thumbnail_url ?? product.image_url ?? undefined }} style={styles.productImage} contentFit="cover" />
      ) : null}
      <View style={styles.rowLeft}>
        <Text style={styles.name} numberOfLines={1}>
          {product.name}
          {!product.is_active ? '  (inactive)' : ''}
        </Text>
        <Text style={styles.sku}>
          {product.sku}
          {product.category ? ` · ${product.category}` : ''}
          {product.quantity !== null ? ` · ${product.quantity} on hand` : ''}
          {low ? '  ⚠ low' : ''}
        </Text>
      </View>
      <TouchableOpacity onPress={onEdit}>
        <Text style={styles.price}>{formatPeso(product.unit_price)}</Text>
      </TouchableOpacity>
    </View>
  );
}

function EditModal({
  product,
  onClose,
  onSaved,
}: {
  product: CatalogProduct;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const [priceText, setPriceText] = useState((product.unit_price / 100).toFixed(2));
  const [category, setCategory] = useState(product.category ?? '');
  const [reason, setReason] = useState('');
  const [description, setDescription] = useState(product.description ?? '');
  const [imageUrl, setImageUrl] = useState(product.image_url ?? '');
  const [thumbnailUrl, setThumbnailUrl] = useState(product.thumbnail_url ?? '');
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<EditValidation | null>(null);

  async  function save() {
    const v = validateProductEdit({ priceText, category, reason });
    if (!v.ok) {
      setErrors({ ...v, ok: false });
      return;
    }
    setBusy(true);
    try {
      if (v.newPrice != null && isPriceChange(product.unit_price, v.newPrice)) {
        await applyPriceChange(product.product_id, v.newPrice, v.reason ?? 'price change');
      }
      if (v.category && v.category !== (product.category ?? '')) {
        await applyCategory(product.product_id, v.category);
      }
      if (description !== (product.description ?? '') || imageUrl !== (product.image_url ?? '') || thumbnailUrl !== (product.thumbnail_url ?? '')) {
        await updateProductMedia({ productId: product.product_id, description: description.trim(), imageUrl: imageUrl.trim(), thumbnailUrl: thumbnailUrl.trim() });
      }
      await onSaved();
    } catch (e) {
      Alert.alert('Change rejected', e instanceof Error ? e.message : 'Update failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal transparent animationType="fade" onRequestClose={onClose}>
      <View style={styles.overlay}>
        <View style={styles.modal}>
          <Text style={styles.modalTitle}>{product.name}</Text>
          <Text style={styles.modalSubtitle}>
            {product.sku} · current {formatPeso(product.unit_price)} · cost {formatPeso(product.cost_price)}
          </Text>

          <Text style={styles.label}>New selling price (GHS)</Text>
          <TextInput
            style={styles.input}
            value={priceText}
            onChangeText={(t) => setPriceText(t)}
            keyboardType="decimal-pad"
            placeholder="leave empty to keep current price"
            placeholderTextColor="#888"
          />
          {errors?.priceTextError ? <Text style={styles.error}>{errors.priceTextError}</Text> : null}

          <Text style={styles.label}>Category</Text>
          <TextInput
            style={styles.input}
            value={category}
            onChangeText={setCategory}
            autoCapitalize="words"
            placeholder="e.g. Audio"
            placeholderTextColor="#888"
            maxLength={64}
          />
          {errors?.categoryError ? <Text style={styles.error}>{errors.categoryError}</Text> : null}

          <Text style={styles.label}>Description</Text>
          <TextInput style={styles.input} value={description} onChangeText={setDescription} placeholder="Product description" placeholderTextColor="#888" multiline />
          <Text style={styles.label}>Product image URL (HTTPS)</Text>
          <TextInput style={styles.input} value={imageUrl} onChangeText={setImageUrl} placeholder="https://…" placeholderTextColor="#888" autoCapitalize="none" keyboardType="url" />
          <Text style={styles.label}>Thumbnail URL (optional, HTTPS)</Text>
          <TextInput style={styles.input} value={thumbnailUrl} onChangeText={setThumbnailUrl} placeholder="https://…" placeholderTextColor="#888" autoCapitalize="none" keyboardType="url" />

          <Text style={styles.label}>Reason (required)</Text>
          <TextInput
            style={styles.input}
            value={reason}
            onChangeText={setReason}
            placeholder="why is this changing?"
            placeholderTextColor="#888"
          />
          {errors?.reasonError ? <Text style={styles.error}>{errors.reasonError}</Text> : null}

          <View style={styles.modalButtons}>
            <TouchableOpacity style={[styles.modalButton, styles.secondaryButton]} onPress={onClose}>
              <Text style={styles.secondaryText}>Cancel</Text>
            </TouchableOpacity>
            <TouchableOpacity style={styles.modalButton} onPress={save} disabled={busy}>
              {busy ? <ActivityIndicator color="#fff" /> : <Text style={styles.buttonText}>Save</Text>}
            </TouchableOpacity>
          </View>
        </View>
      </View>
    </Modal>
  );
}

function Chip({ label, active, onPress }: { label: string; active: boolean; onPress: () => void }) {
  return (
    <TouchableOpacity onPress={onPress} style={[styles.chip, active && styles.chipActive]}>
      <Text style={[styles.chipText, active && styles.chipTextActive]}>{label}</Text>
    </TouchableOpacity>
  );
}

const Separator = () => <View style={styles.sep} />;

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f7f8fa' },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: '#f7f8fa' },
  search: {
    backgroundColor: '#fff',
    borderWidth: 1,
    borderColor: '#ccc',
    borderRadius: 8,
    padding: 10,
    margin: 12,
  },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, paddingHorizontal: 12, marginBottom: 8 },
  chip: { backgroundColor: '#e6eff8', borderRadius: 16, paddingVertical: 4, paddingHorizontal: 12 },
  chipActive: { backgroundColor: '#208AEF' },
  chipText: { color: '#33597a', fontSize: 13 },
  chipTextActive: { color: '#fff' },
  productImage: { width: 48, height: 48, borderRadius: 8, marginRight: 10 },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#fff',
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  rowLeft: { flex: 1, marginRight: 8 },
  name: { fontSize: 15, fontWeight: '600', color: '#222' },
  sku: { fontSize: 12, color: '#777', marginTop: 2 },
  price: { color: '#208AEF', fontWeight: '700', fontSize: 15, fontVariant: ['tabular-nums'] },
  sep: { height: 1, backgroundColor: '#e4e4e8' },
  error: { color: '#c0392b', fontSize: 12, marginTop: 2 },
  overlay: { flex: 1, backgroundColor: 'rgba(0,0,0,0.45)', justifyContent: 'center', padding: 24 },
  modal: { backgroundColor: '#fff', borderRadius: 14, padding: 20 },
  modalTitle: { fontSize: 17, fontWeight: '700' },
  modalSubtitle: { fontSize: 12, color: '#666', marginBottom: 12 },
  label: { fontSize: 13, color: '#444', marginTop: 10, marginBottom: 4 },
  input: {
    borderWidth: 1,
    borderColor: '#ccc',
    borderRadius: 8,
    padding: 10,
    fontSize: 15,
  },
  modalButtons: { flexDirection: 'row', gap: 12, marginTop: 16 },
  modalButton: {
    flex: 1,
    backgroundColor: '#208AEF',
    borderRadius: 8,
    padding: 12,
    alignItems: 'center',
  },
  secondaryButton: { backgroundColor: '#eef0f4' },
  secondaryText: { color: '#33597a', fontWeight: '600' },
  buttonText: { color: '#fff', fontWeight: '600' },
});
