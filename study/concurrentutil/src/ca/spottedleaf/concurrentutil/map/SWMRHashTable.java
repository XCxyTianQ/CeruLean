package ca.spottedleaf.concurrentutil.map;

import ca.spottedleaf.common.util.CollectionUtil;
import ca.spottedleaf.common.util.HashUtil;
import ca.spottedleaf.common.util.IntegerUtil;
import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;

public class SWMRHashTable<K, V> implements Map<K, V>, Iterable<Entry<K, V>> {
   protected int size;
   protected SWMRHashTable.TableEntry<K, V>[] table;
   protected final float loadFactor;
   protected static final VarHandle SIZE_HANDLE = ConcurrentUtil.getVarHandle(SWMRHashTable.class, "size", int.class);
   protected static final VarHandle TABLE_HANDLE = ConcurrentUtil.getVarHandle(SWMRHashTable.class, "table", SWMRHashTable.TableEntry[].class);
   protected static final int DEFAULT_CAPACITY = 16;
   protected static final float DEFAULT_LOAD_FACTOR = 0.75F;
   protected static final int MAXIMUM_CAPACITY = 1073741824;
   protected SWMRHashTable.KeySet<K, V> keyset;
   protected SWMRHashTable.ValueCollection<K, V> values;
   protected SWMRHashTable.EntrySet<K, V> entrySet;
   protected int threshold;

   protected final int getSizePlain() {
      return (int)SIZE_HANDLE.get((SWMRHashTable)this);
   }

   protected final int getSizeOpaque() {
      return (int)SIZE_HANDLE.getOpaque((SWMRHashTable)this);
   }

   protected final int getSizeAcquire() {
      return (int)SIZE_HANDLE.getAcquire((SWMRHashTable)this);
   }

   protected final void setSizePlain(final int value) {
      SIZE_HANDLE.set((SWMRHashTable)this, (int)value);
   }

   protected final void setSizeOpaque(final int value) {
      SIZE_HANDLE.setOpaque((SWMRHashTable)this, (int)value);
   }

   protected final void setSizeRelease(final int value) {
      SIZE_HANDLE.setRelease((SWMRHashTable)this, (int)value);
   }

   protected final SWMRHashTable.TableEntry<K, V>[] getTablePlain() {
      return (SWMRHashTable.TableEntry[])TABLE_HANDLE.get((SWMRHashTable)this);
   }

   protected final SWMRHashTable.TableEntry<K, V>[] getTableAcquire() {
      return (SWMRHashTable.TableEntry[])TABLE_HANDLE.getAcquire((SWMRHashTable)this);
   }

   protected final void setTablePlain(final SWMRHashTable.TableEntry<K, V>[] table) {
      TABLE_HANDLE.set((SWMRHashTable)this, (SWMRHashTable.TableEntry[])table);
   }

   protected final void setTableRelease(final SWMRHashTable.TableEntry<K, V>[] table) {
      TABLE_HANDLE.setRelease((SWMRHashTable)this, (SWMRHashTable.TableEntry[])table);
   }

   public SWMRHashTable() {
      this(16, 0.75F);
   }

   public SWMRHashTable(final int capacity) {
      this(capacity, 0.75F);
   }

   public SWMRHashTable(final int capacity, final float loadFactor) {
      super();
      int tableSize = getCapacityFor(capacity);
      if (!(loadFactor <= 0.0) && Float.isFinite(loadFactor)) {
         SWMRHashTable.TableEntry<K, V>[] table = new SWMRHashTable.TableEntry[tableSize];
         this.setTablePlain(table);
         if (tableSize == 1073741824) {
            this.threshold = -1;
         } else {
            this.threshold = getTargetCapacity(tableSize, loadFactor);
         }

         this.loadFactor = loadFactor;
      } else {
         throw new IllegalArgumentException("Invalid load factor: " + loadFactor);
      }
   }

   public SWMRHashTable(final Map<K, V> other) {
      this(16, 0.75F, other);
   }

   public SWMRHashTable(final int capacity, final Map<K, V> other) {
      this(capacity, 0.75F, other);
   }

   public SWMRHashTable(final int capacity, final float loadFactor, final Map<K, V> other) {
      this(Math.max(Objects.requireNonNull(other, "Null map").size(), capacity), loadFactor);
      this.putAll(other);
   }

   protected static <K, V> SWMRHashTable.TableEntry<K, V> getAtIndexOpaque(final SWMRHashTable.TableEntry<K, V>[] table, final int index) {
      return (SWMRHashTable.TableEntry)SWMRHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE.getOpaque((SWMRHashTable.TableEntry[])table, (int)index);
   }

   protected static <K, V> void setAtIndexRelease(final SWMRHashTable.TableEntry<K, V>[] table, final int index, final SWMRHashTable.TableEntry<K, V> value) {
      SWMRHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE.setRelease((SWMRHashTable.TableEntry[])table, (int)index, (SWMRHashTable.TableEntry)value);
   }

   public final float getLoadFactor() {
      return this.loadFactor;
   }

   protected static int getCapacityFor(final int capacity) {
      if (capacity <= 0) {
         throw new IllegalArgumentException("Invalid capacity: " + capacity);
      } else {
         return capacity >= 1073741824 ? 1073741824 : IntegerUtil.roundCeilLog2(capacity);
      }
   }

   protected final SWMRHashTable.TableEntry<K, V> getEntryForOpaque(final K key) {
      int hash = getHash(key);
      SWMRHashTable.TableEntry<K, V>[] table = this.getTableAcquire();

      for (SWMRHashTable.TableEntry<K, V> curr = getAtIndexOpaque(table, hash & table.length - 1); curr != null; curr = curr.getNextOpaque()) {
         if (hash == curr.hash && (key == curr.key || curr.key.equals(key))) {
            return curr;
         }
      }

      return null;
   }

   protected final SWMRHashTable.TableEntry<K, V> getEntryForPlain(final K key) {
      int hash = getHash(key);
      SWMRHashTable.TableEntry<K, V>[] table = this.getTablePlain();

      for (SWMRHashTable.TableEntry<K, V> curr = table[hash & table.length - 1]; curr != null; curr = curr.getNextPlain()) {
         if (hash == curr.hash && (key == curr.key || curr.key.equals(key))) {
            return curr;
         }
      }

      return null;
   }

   private static int getHash(final Object key) {
      int hash = key == null ? 0 : key.hashCode();
      return HashUtil.mix(hash);
   }

   protected static int getTargetCapacity(final int capacity, final float loadFactor) {
      double ret = (double)capacity * loadFactor;
      return !Double.isInfinite(ret) && !(ret >= 2.147483647E9) ? (int)ret : -1;
   }

   @Override
   public boolean equals(final Object obj) {
      if (this == obj) {
         return true;
      } else if (!(obj instanceof Map<?, ?> other)) {
         return false;
      } else {
         if (this.size() != other.size()) {
            return false;
         }

         SWMRHashTable.TableEntry<K, V>[] table = this.getTableAcquire();
         int i = 0;

         for (int len = table.length; i < len; i++) {
            for (SWMRHashTable.TableEntry<K, V> curr = getAtIndexOpaque(table, i); curr != null; curr = curr.getNextOpaque()) {
               V value = curr.getValueAcquire();
               Object otherValue = other.get(curr.key);
               if (otherValue == null || value != otherValue && !value.equals(otherValue)) {
                  return false;
               }
            }
         }

         return true;
      }
   }

   @Override
   public int hashCode() {
      int hash = 0;
      SWMRHashTable.TableEntry<K, V>[] table = this.getTableAcquire();
      int i = 0;

      for (int len = table.length; i < len; i++) {
         for (SWMRHashTable.TableEntry<K, V> curr = getAtIndexOpaque(table, i); curr != null; curr = curr.getNextOpaque()) {
            hash += curr.hashCode();
         }
      }

      return hash;
   }

   @Override
   public String toString() {
      StringBuilder builder = new StringBuilder(64);
      builder.append("SWMRHashTable:{");
      this.forEach((key, value) -> builder.append("{key: \"").append(key).append("\", value: \"").append(value).append("\"}"));
      return builder.append('}').toString();
   }

   public SWMRHashTable<K, V> clone() {
      return new SWMRHashTable<>(this.getTableAcquire().length, this.loadFactor, this);
   }

   @Override
   public Iterator<Entry<K, V>> iterator() {
      return new SWMRHashTable.EntryIterator<>(this.getTableAcquire(), this);
   }

   @Override
   public void forEach(final Consumer<? super Entry<K, V>> action) {
      Objects.requireNonNull(action, "Null action");
      SWMRHashTable.TableEntry<K, V>[] table = this.getTableAcquire();
      int i = 0;

      for (int len = table.length; i < len; i++) {
         for (SWMRHashTable.TableEntry<K, V> curr = getAtIndexOpaque(table, i); curr != null; curr = curr.getNextOpaque()) {
            action.accept(curr);
         }
      }
   }

   @Override
   public void forEach(final BiConsumer<? super K, ? super V> action) {
      Objects.requireNonNull(action, "Null action");
      SWMRHashTable.TableEntry<K, V>[] table = this.getTableAcquire();
      int i = 0;

      for (int len = table.length; i < len; i++) {
         for (SWMRHashTable.TableEntry<K, V> curr = getAtIndexOpaque(table, i); curr != null; curr = curr.getNextOpaque()) {
            V value = curr.getValueAcquire();
            action.accept(curr.key, value);
         }
      }
   }

   public void forEachKey(final Consumer<? super K> action) {
      Objects.requireNonNull(action, "Null action");
      SWMRHashTable.TableEntry<K, V>[] table = this.getTableAcquire();
      int i = 0;

      for (int len = table.length; i < len; i++) {
         for (SWMRHashTable.TableEntry<K, V> curr = getAtIndexOpaque(table, i); curr != null; curr = curr.getNextOpaque()) {
            action.accept(curr.key);
         }
      }
   }

   public void forEachValue(final Consumer<? super V> action) {
      Objects.requireNonNull(action, "Null action");
      SWMRHashTable.TableEntry<K, V>[] table = this.getTableAcquire();
      int i = 0;

      for (int len = table.length; i < len; i++) {
         for (SWMRHashTable.TableEntry<K, V> curr = getAtIndexOpaque(table, i); curr != null; curr = curr.getNextOpaque()) {
            V value = curr.getValueAcquire();
            action.accept(value);
         }
      }
   }

   @Override
   public V get(final Object key) {
      Objects.requireNonNull(key, "Null key");
      SWMRHashTable.TableEntry<K, V> entry = this.getEntryForOpaque((K)key);
      return entry == null ? null : entry.getValueAcquire();
   }

   @Override
   public boolean containsKey(final Object key) {
      Objects.requireNonNull(key, "Null key");
      return this.get(key) != null;
   }

   public boolean contains(final Object key, final Object value) {
      Objects.requireNonNull(key, "Null key");
      SWMRHashTable.TableEntry<K, V> entry = this.getEntryForOpaque((K)key);
      if (entry == null) {
         return false;
      }

      V entryVal = entry.getValueAcquire();
      return entryVal == value || entryVal.equals(value);
   }

   @Override
   public boolean containsValue(final Object value) {
      Objects.requireNonNull(value, "Null value");
      SWMRHashTable.TableEntry<K, V>[] table = this.getTableAcquire();
      int i = 0;

      for (int len = table.length; i < len; i++) {
         for (SWMRHashTable.TableEntry<K, V> curr = getAtIndexOpaque(table, i); curr != null; curr = curr.getNextOpaque()) {
            V currVal = curr.getValueAcquire();
            if (currVal == value || currVal.equals(value)) {
               return true;
            }
         }
      }

      return false;
   }

   @Override
   public V getOrDefault(final Object key, final V defaultValue) {
      Objects.requireNonNull(key, "Null key");
      SWMRHashTable.TableEntry<K, V> entry = this.getEntryForOpaque((K)key);
      return entry == null ? defaultValue : entry.getValueAcquire();
   }

   @Override
   public int size() {
      return this.getSizeAcquire();
   }

   @Override
   public boolean isEmpty() {
      return this.getSizeAcquire() == 0;
   }

   @Override
   public Set<K> keySet() {
      return this.keyset == null ? (this.keyset = new SWMRHashTable.KeySet<>(this)) : this.keyset;
   }

   @Override
   public Collection<V> values() {
      return this.values == null ? (this.values = new SWMRHashTable.ValueCollection<>(this)) : this.values;
   }

   @Override
   public Set<Entry<K, V>> entrySet() {
      return this.entrySet == null ? (this.entrySet = new SWMRHashTable.EntrySet<>(this)) : this.entrySet;
   }

   protected final void checkResize(final int minCapacity) {
      if (minCapacity > this.threshold && this.threshold >= 0) {
         SWMRHashTable.TableEntry<K, V>[] table = this.getTablePlain();
         int newCapacity = minCapacity >= 1073741824 ? 1073741824 : IntegerUtil.roundCeilLog2(minCapacity);
         if (newCapacity < 0) {
            newCapacity = 1073741824;
         }

         if (newCapacity <= table.length) {
            if (newCapacity == 1073741824) {
               return;
            }

            newCapacity = table.length << 1;
         }

         SWMRHashTable.TableEntry<K, V>[] newTable = new SWMRHashTable.TableEntry[newCapacity];
         int indexMask = newCapacity - 1;
         int i = 0;

         for (int len = table.length; i < len; i++) {
            for (SWMRHashTable.TableEntry<K, V> entry = table[i]; entry != null; entry = entry.getNextPlain()) {
               int hash = entry.hash;
               int index = hash & indexMask;
               SWMRHashTable.TableEntry<K, V> insert = new SWMRHashTable.TableEntry<>(hash, entry.key, entry.getValuePlain());
               SWMRHashTable.TableEntry<K, V> prev = newTable[index];
               newTable[index] = insert;
               insert.setNextPlain(prev);
            }
         }

         if (newCapacity == 1073741824) {
            this.threshold = -1;
         } else {
            this.threshold = getTargetCapacity(newCapacity, this.loadFactor);
         }

         this.setTableRelease(newTable);
      }
   }

   protected final int addToSize(final int num) {
      int newSize = this.getSizePlain() + num;
      this.setSizeOpaque(newSize);
      this.checkResize(newSize);
      return newSize;
   }

   protected final int removeFromSize(final int num) {
      int newSize = this.getSizePlain() - num;
      this.setSizeOpaque(newSize);
      return newSize;
   }

   protected final int removeFromSizePlain(final int num) {
      int newSize = this.getSizePlain() - num;
      this.setSizePlain(newSize);
      return newSize;
   }

   protected final V put(final K key, final V value, final boolean onlyIfAbsent) {
      SWMRHashTable.TableEntry<K, V>[] table = this.getTablePlain();
      int hash = getHash(key);
      int index = hash & table.length - 1;
      SWMRHashTable.TableEntry<K, V> head = table[index];
      if (head == null) {
         SWMRHashTable.TableEntry<K, V> insert = new SWMRHashTable.TableEntry<>(hash, key, value);
         setAtIndexRelease(table, index, insert);
         this.addToSize(1);
         return null;
      }

      SWMRHashTable.TableEntry<K, V> curr = head;

      while (curr.hash != hash || key != curr.key && !curr.key.equals(key)) {
         SWMRHashTable.TableEntry<K, V> next = curr.getNextPlain();
         if (next == null) {
            SWMRHashTable.TableEntry<K, V> insert = new SWMRHashTable.TableEntry<>(hash, key, value);
            curr.setNextRelease(insert);
            this.addToSize(1);
            return null;
         }

         curr = next;
      }

      if (onlyIfAbsent) {
         return curr.getValuePlain();
      }

      V currVal = curr.getValuePlain();
      curr.setValueRelease(value);
      return currVal;
   }

   public int removeIf(final BiPredicate<K, V> predicate) {
      Objects.requireNonNull(predicate, "Null predicate");
      int removed = 0;
      SWMRHashTable.TableEntry<K, V>[] table = this.getTablePlain();
      int i = 0;

      label33:
      for (int len = table.length; i < len; i++) {
         SWMRHashTable.TableEntry<K, V> curr = table[i];
         if (curr != null) {
            while (predicate.test(curr.key, curr.getValuePlain())) {
               removed++;
               this.removeFromSizePlain(1);
               setAtIndexRelease(table, i, curr = curr.getNextPlain());
               if (curr == null) {
                  continue label33;
               }
            }

            SWMRHashTable.TableEntry<K, V> prev = curr;
            curr = curr.getNextPlain();

            while (curr != null) {
               if (predicate.test(curr.key, curr.getValuePlain())) {
                  removed++;
                  this.removeFromSizePlain(1);
                  prev.setNextRelease(curr = curr.getNextPlain());
               } else {
                  prev = curr;
                  curr = curr.getNextPlain();
               }
            }
         }
      }

      return removed;
   }

   public int removeEntryIf(final Predicate<? super Entry<K, V>> predicate) {
      Objects.requireNonNull(predicate, "Null predicate");
      int removed = 0;
      SWMRHashTable.TableEntry<K, V>[] table = this.getTablePlain();
      int i = 0;

      label33:
      for (int len = table.length; i < len; i++) {
         SWMRHashTable.TableEntry<K, V> curr = table[i];
         if (curr != null) {
            while (predicate.test(curr)) {
               removed++;
               this.removeFromSizePlain(1);
               setAtIndexRelease(table, i, curr = curr.getNextPlain());
               if (curr == null) {
                  continue label33;
               }
            }

            SWMRHashTable.TableEntry<K, V> prev = curr;
            curr = curr.getNextPlain();

            while (curr != null) {
               if (predicate.test(curr)) {
                  removed++;
                  this.removeFromSizePlain(1);
                  prev.setNextRelease(curr = curr.getNextPlain());
               } else {
                  prev = curr;
                  curr = curr.getNextPlain();
               }
            }
         }
      }

      return removed;
   }

   @Override
   public V put(final K key, final V value) {
      Objects.requireNonNull(key, "Null key");
      Objects.requireNonNull(value, "Null value");
      return this.put(key, value, false);
   }

   @Override
   public V putIfAbsent(final K key, final V value) {
      Objects.requireNonNull(key, "Null key");
      Objects.requireNonNull(value, "Null value");
      return this.put(key, value, true);
   }

   @Override
   public boolean remove(final Object key, final Object value) {
      Objects.requireNonNull(key, "Null key");
      Objects.requireNonNull(value, "Null value");
      SWMRHashTable.TableEntry<K, V>[] table = this.getTablePlain();
      int hash = getHash(key);
      int index = hash & table.length - 1;
      SWMRHashTable.TableEntry<K, V> head = table[index];
      if (head == null) {
         return false;
      }

      if (head.hash != hash || head.key != key && !head.key.equals(key)) {
         SWMRHashTable.TableEntry<K, V> curr = head.getNextPlain();
         SWMRHashTable.TableEntry<K, V> prev = head;

         while (curr != null) {
            if (curr.hash == hash && (curr.key == key || curr.key.equals(key))) {
               V currVal = curr.getValuePlain();
               if (currVal != value && !currVal.equals(value)) {
                  return false;
               }

               prev.setNextRelease(curr.getNextPlain());
               this.removeFromSize(1);
               return true;
            }

            prev = curr;
            curr = curr.getNextPlain();
         }

         return false;
      } else {
         V currVal = head.getValuePlain();
         if (currVal != value && !currVal.equals(value)) {
            return false;
         }

         setAtIndexRelease(table, index, head.getNextPlain());
         this.removeFromSize(1);
         return true;
      }
   }

   protected final V remove(final Object key, final int hash) {
      SWMRHashTable.TableEntry<K, V>[] table = this.getTablePlain();
      int index = table.length - 1 & hash;
      SWMRHashTable.TableEntry<K, V> head = table[index];
      if (head == null) {
         return null;
      }

      if (hash != head.hash || head.key != key && !head.key.equals(key)) {
         SWMRHashTable.TableEntry<K, V> curr = head.getNextPlain();
         SWMRHashTable.TableEntry<K, V> prev = head;

         while (curr != null) {
            if (curr.hash == hash && (key == curr.key || curr.key.equals(key))) {
               prev.setNextRelease(curr.getNextPlain());
               this.removeFromSize(1);
               return curr.getValuePlain();
            }

            prev = curr;
            curr = curr.getNextPlain();
         }

         return null;
      } else {
         setAtIndexRelease(table, index, head.getNextPlain());
         this.removeFromSize(1);
         return head.getValuePlain();
      }
   }

   @Override
   public V remove(final Object key) {
      Objects.requireNonNull(key, "Null key");
      return this.remove(key, getHash(key));
   }

   @Override
   public boolean replace(final K key, final V oldValue, final V newValue) {
      Objects.requireNonNull(key, "Null key");
      Objects.requireNonNull(oldValue, "Null oldValue");
      Objects.requireNonNull(newValue, "Null newValue");
      SWMRHashTable.TableEntry<K, V> entry = this.getEntryForPlain(key);
      if (entry == null) {
         return false;
      }

      V currValue = entry.getValuePlain();
      if (currValue != oldValue && !currValue.equals(oldValue)) {
         return false;
      }

      entry.setValueRelease(newValue);
      return true;
   }

   @Override
   public V replace(final K key, final V value) {
      Objects.requireNonNull(key, "Null key");
      Objects.requireNonNull(value, "Null value");
      SWMRHashTable.TableEntry<K, V> entry = this.getEntryForPlain(key);
      if (entry == null) {
         return null;
      }

      V prev = entry.getValuePlain();
      entry.setValueRelease(value);
      return prev;
   }

   @Override
   public void replaceAll(final BiFunction<? super K, ? super V, ? extends V> function) {
      Objects.requireNonNull(function, "Null function");
      SWMRHashTable.TableEntry<K, V>[] table = this.getTablePlain();
      int i = 0;

      for (int len = table.length; i < len; i++) {
         for (SWMRHashTable.TableEntry<K, V> curr = table[i]; curr != null; curr = curr.getNextPlain()) {
            V value = curr.getValuePlain();
            V newValue = (V)function.apply(curr.key, value);
            if (newValue == null) {
               throw new NullPointerException();
            }

            curr.setValueRelease(newValue);
         }
      }
   }

   @Override
   public void putAll(final Map<? extends K, ? extends V> map) {
      Objects.requireNonNull(map, "Null map");
      int size = map.size();
      this.checkResize(Math.max(this.getSizePlain() + size / 2, size));
      map.forEach(this::put);
   }

   @Override
   public void clear() {
      Arrays.fill(this.getTablePlain(), null);
      this.setSizeRelease(0);
   }

   @Override
   public V compute(final K key, final BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
      Objects.requireNonNull(key, "Null key");
      Objects.requireNonNull(remappingFunction, "Null remappingFunction");
      int hash = getHash(key);
      SWMRHashTable.TableEntry<K, V>[] table = this.getTablePlain();
      int index = hash & table.length - 1;
      SWMRHashTable.TableEntry<K, V> curr = table[index];
      SWMRHashTable.TableEntry<K, V> prev = null;

      while (curr != null) {
         if (curr.hash == hash && (curr.key == key || curr.key.equals(key))) {
            V newVal = (V)remappingFunction.apply(key, curr.getValuePlain());
            if (newVal != null) {
               curr.setValueRelease(newVal);
               return newVal;
            }

            if (prev == null) {
               setAtIndexRelease(table, index, curr.getNextPlain());
            } else {
               prev.setNextRelease(curr.getNextPlain());
            }

            this.removeFromSize(1);
            return null;
         }

         prev = curr;
         curr = curr.getNextPlain();
      }

      V newVal = (V)remappingFunction.apply(key, null);
      if (newVal == null) {
         return null;
      }

      SWMRHashTable.TableEntry<K, V> insert = new SWMRHashTable.TableEntry<>(hash, key, newVal);
      if (prev == null) {
         setAtIndexRelease(table, index, insert);
      } else {
         prev.setNextRelease(insert);
      }

      this.addToSize(1);
      return newVal;
   }

   @Override
   public V computeIfPresent(final K key, final BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
      Objects.requireNonNull(key, "Null key");
      Objects.requireNonNull(remappingFunction, "Null remappingFunction");
      int hash = getHash(key);
      SWMRHashTable.TableEntry<K, V>[] table = this.getTablePlain();
      int index = hash & table.length - 1;
      SWMRHashTable.TableEntry<K, V> curr = table[index];
      SWMRHashTable.TableEntry<K, V> prev = null;

      while (curr != null) {
         if (curr.hash == hash && (curr.key == key || curr.key.equals(key))) {
            V newVal = (V)remappingFunction.apply(key, curr.getValuePlain());
            if (newVal != null) {
               curr.setValueRelease(newVal);
               return newVal;
            }

            if (prev == null) {
               setAtIndexRelease(table, index, curr.getNextPlain());
            } else {
               prev.setNextRelease(curr.getNextPlain());
            }

            this.removeFromSize(1);
            return null;
         }

         prev = curr;
         curr = curr.getNextPlain();
      }

      return null;
   }

   @Override
   public V computeIfAbsent(final K key, final Function<? super K, ? extends V> mappingFunction) {
      Objects.requireNonNull(key, "Null key");
      Objects.requireNonNull(mappingFunction, "Null mappingFunction");
      int hash = getHash(key);
      SWMRHashTable.TableEntry<K, V>[] table = this.getTablePlain();
      int index = hash & table.length - 1;
      SWMRHashTable.TableEntry<K, V> curr = table[index];
      SWMRHashTable.TableEntry<K, V> prev = null;

      while (curr != null) {
         if (curr.hash == hash && (curr.key == key || curr.key.equals(key))) {
            return curr.getValuePlain();
         }

         prev = curr;
         curr = curr.getNextPlain();
      }

      V newVal = (V)mappingFunction.apply(key);
      if (newVal == null) {
         return null;
      }

      SWMRHashTable.TableEntry<K, V> insert = new SWMRHashTable.TableEntry<>(hash, key, newVal);
      if (prev == null) {
         setAtIndexRelease(table, index, insert);
      } else {
         prev.setNextRelease(insert);
      }

      this.addToSize(1);
      return newVal;
   }

   @Override
   public V merge(final K key, final V value, final BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
      Objects.requireNonNull(key, "Null key");
      Objects.requireNonNull(value, "Null value");
      Objects.requireNonNull(remappingFunction, "Null remappingFunction");
      int hash = getHash(key);
      SWMRHashTable.TableEntry<K, V>[] table = this.getTablePlain();
      int index = hash & table.length - 1;
      SWMRHashTable.TableEntry<K, V> curr = table[index];
      SWMRHashTable.TableEntry<K, V> prev = null;

      while (curr != null) {
         if (curr.hash == hash && (curr.key == key || curr.key.equals(key))) {
            V newVal = (V)remappingFunction.apply(curr.getValuePlain(), value);
            if (newVal != null) {
               curr.setValueRelease(newVal);
               return newVal;
            }

            if (prev == null) {
               setAtIndexRelease(table, index, curr.getNextPlain());
            } else {
               prev.setNextRelease(curr.getNextPlain());
            }

            this.removeFromSize(1);
            return null;
         }

         prev = curr;
         curr = curr.getNextPlain();
      }

      SWMRHashTable.TableEntry<K, V> insert = new SWMRHashTable.TableEntry<>(hash, key, value);
      if (prev == null) {
         setAtIndexRelease(table, index, insert);
      } else {
         prev.setNextRelease(insert);
      }

      this.addToSize(1);
      return value;
   }

   protected static final class EntryIterator<K, V> extends SWMRHashTable.TableEntryIterator<K, V, Entry<K, V>> {
      protected EntryIterator(final SWMRHashTable.TableEntry<K, V>[] table, final SWMRHashTable<K, V> map) {
         super(table, map);
      }

      public Entry<K, V> next() {
         SWMRHashTable.TableEntry<K, V> curr = this.advanceEntry();
         if (curr == null) {
            throw new NoSuchElementException();
         } else {
            return curr;
         }
      }
   }

   protected static final class EntrySet<K, V> extends SWMRHashTable.ViewSet<K, V, Entry<K, V>> implements Set<Entry<K, V>> {
      protected EntrySet(final SWMRHashTable<K, V> map) {
         super(map);
      }

      @Override
      public boolean remove(final Object object) {
         if (object instanceof Entry<?, ?> entry) {
            Object key;
            Object value;
            try {
               key = entry.getKey();
               value = entry.getValue();
            } catch (IllegalStateException ex) {
               return false;
            }

            return this.map.remove(key, value);
         } else {
            return false;
         }
      }

      @Override
      public boolean removeIf(final Predicate<? super Entry<K, V>> filter) {
         Objects.requireNonNull(filter, "Null filter");
         return this.map.removeEntryIf(filter) != 0;
      }

      @Override
      public boolean retainAll(final Collection<?> collection) {
         Objects.requireNonNull(collection, "Null collection");
         return this.map.removeEntryIf(entry -> !collection.contains(entry)) != 0;
      }

      @Override
      public Iterator<Entry<K, V>> iterator() {
         return new SWMRHashTable.EntryIterator<>(this.map.getTableAcquire(), this.map);
      }

      @Override
      public void forEach(final Consumer<? super Entry<K, V>> action) {
         this.map.forEach(action);
      }

      @Override
      public boolean contains(final Object object) {
         if (object instanceof Entry<?, ?> entry) {
            Object key;
            Object value;
            try {
               key = entry.getKey();
               value = entry.getValue();
            } catch (IllegalStateException ex) {
               return false;
            }

            return this.map.contains(key, value);
         } else {
            return false;
         }
      }

      @Override
      public String toString() {
         return CollectionUtil.toString(this, "SWMRHashTableEntrySet");
      }
   }

   protected static final class KeyIterator<K, V> extends SWMRHashTable.TableEntryIterator<K, V, K> {
      protected KeyIterator(final SWMRHashTable.TableEntry<K, V>[] table, final SWMRHashTable<K, V> map) {
         super(table, map);
      }

      @Override
      public K next() {
         SWMRHashTable.TableEntry<K, V> curr = this.advanceEntry();
         if (curr == null) {
            throw new NoSuchElementException();
         } else {
            return curr.key;
         }
      }
   }

   protected static final class KeySet<K, V> extends SWMRHashTable.ViewSet<K, V, K> {
      protected KeySet(final SWMRHashTable<K, V> map) {
         super(map);
      }

      @Override
      public Iterator<K> iterator() {
         return new SWMRHashTable.KeyIterator<>(this.map.getTableAcquire(), this.map);
      }

      @Override
      public void forEach(final Consumer<? super K> action) {
         Objects.requireNonNull(action, "Null action");
         this.map.forEachKey(action);
      }

      @Override
      public boolean contains(final Object key) {
         Objects.requireNonNull(key, "Null key");
         return this.map.containsKey(key);
      }

      @Override
      public boolean remove(final Object key) {
         Objects.requireNonNull(key, "Null key");
         return this.map.remove(key) != null;
      }

      @Override
      public boolean retainAll(final Collection<?> collection) {
         Objects.requireNonNull(collection, "Null collection");
         return this.map.removeIf((key, value) -> !collection.contains(key)) != 0;
      }

      @Override
      public boolean removeIf(final Predicate<? super K> filter) {
         Objects.requireNonNull(filter, "Null filter");
         return this.map.removeIf((key, value) -> filter.test(key)) != 0;
      }

      @Override
      public String toString() {
         return CollectionUtil.toString(this, "SWMRHashTableKeySet");
      }
   }

   protected static final class TableEntry<K, V> implements Entry<K, V> {
      protected static final VarHandle TABLE_ENTRY_ARRAY_HANDLE = ConcurrentUtil.getArrayHandle(SWMRHashTable.TableEntry[].class);
      protected final int hash;
      protected final K key;
      protected V value;
      protected SWMRHashTable.TableEntry<K, V> next;
      protected static final VarHandle VALUE_HANDLE = ConcurrentUtil.getVarHandle(SWMRHashTable.TableEntry.class, "value", Object.class);
      protected static final VarHandle NEXT_HANDLE = ConcurrentUtil.getVarHandle(SWMRHashTable.TableEntry.class, "next", SWMRHashTable.TableEntry.class);

      protected final V getValuePlain() {
         return (V)(Object)VALUE_HANDLE.get((SWMRHashTable.TableEntry)this);
      }

      protected final V getValueAcquire() {
         return (V)(Object)VALUE_HANDLE.getAcquire((SWMRHashTable.TableEntry)this);
      }

      protected final void setValueRelease(final V to) {
         VALUE_HANDLE.setRelease((SWMRHashTable.TableEntry)this, (Object)to);
      }

      protected final SWMRHashTable.TableEntry<K, V> getNextPlain() {
         return (SWMRHashTable.TableEntry)NEXT_HANDLE.get((SWMRHashTable.TableEntry)this);
      }

      protected final SWMRHashTable.TableEntry<K, V> getNextOpaque() {
         return (SWMRHashTable.TableEntry)NEXT_HANDLE.getOpaque((SWMRHashTable.TableEntry)this);
      }

      protected final void setNextPlain(final SWMRHashTable.TableEntry<K, V> next) {
         NEXT_HANDLE.set((SWMRHashTable.TableEntry)this, (SWMRHashTable.TableEntry)next);
      }

      protected final void setNextRelease(final SWMRHashTable.TableEntry<K, V> next) {
         NEXT_HANDLE.setRelease((SWMRHashTable.TableEntry)this, (SWMRHashTable.TableEntry)next);
      }

      protected TableEntry(final int hash, final K key, final V value) {
         super();
         this.hash = hash;
         this.key = key;
         this.value = value;
      }

      @Override
      public K getKey() {
         return this.key;
      }

      @Override
      public V getValue() {
         return this.getValueAcquire();
      }

      @Override
      public V setValue(final V value) {
         throw new UnsupportedOperationException();
      }

      protected static int hash(final Object key, final Object value) {
         return key.hashCode() ^ (value == null ? 0 : value.hashCode());
      }

      @Override
      public int hashCode() {
         return hash(this.key, this.getValueAcquire());
      }

      @Override
      public boolean equals(final Object obj) {
         if (this == obj) {
            return true;
         } else if (!(obj instanceof Entry<?, ?> other)) {
            return false;
         } else {
            Object otherKey = other.getKey();
            Object otherValue = other.getValue();
            Object thisKey = this.getKey();
            Object thisVal = this.getValueAcquire();
            return (thisKey == otherKey || thisKey.equals(otherKey)) && (thisVal == otherValue || thisVal.equals(otherValue));
         }
      }
   }

   protected abstract static class TableEntryIterator<K, V, T> implements Iterator<T> {
      protected final SWMRHashTable.TableEntry<K, V>[] table;
      protected final SWMRHashTable<K, V> map;
      protected int tableIndex;
      protected SWMRHashTable.TableEntry<K, V> currEntry;
      protected SWMRHashTable.TableEntry<K, V> nextEntry;

      protected TableEntryIterator(final SWMRHashTable.TableEntry<K, V>[] table, final SWMRHashTable<K, V> map) {
         super();
         this.table = table;
         this.map = map;
         int tableIndex = 0;

         for (int len = table.length; tableIndex < len; tableIndex++) {
            SWMRHashTable.TableEntry<K, V> entry = SWMRHashTable.getAtIndexOpaque(table, tableIndex);
            if (entry != null) {
               this.nextEntry = entry;
               this.tableIndex = tableIndex + 1;
               return;
            }
         }

         this.tableIndex = tableIndex;
      }

      @Override
      public boolean hasNext() {
         return this.nextEntry != null;
      }

      protected final SWMRHashTable.TableEntry<K, V> advanceEntry() {
         SWMRHashTable.TableEntry<K, V>[] table = this.table;
         int tableLength = table.length;
         int tableIndex = this.tableIndex;
         SWMRHashTable.TableEntry<K, V> curr = this.nextEntry;
         if (curr == null) {
            return null;
         }

         this.currEntry = curr;
         SWMRHashTable.TableEntry<K, V> next = curr.getNextOpaque();
         if (next != null) {
            this.nextEntry = next;
            return curr;
         }

         while (tableIndex < tableLength) {
            next = SWMRHashTable.getAtIndexOpaque(table, tableIndex);
            if (next != null) {
               this.nextEntry = next;
               this.tableIndex = tableIndex + 1;
               return curr;
            }

            tableIndex++;
         }

         this.nextEntry = null;
         this.tableIndex = tableIndex;
         return curr;
      }

      @Override
      public void remove() {
         SWMRHashTable.TableEntry<K, V> curr = this.currEntry;
         if (curr == null) {
            throw new IllegalStateException();
         }

         this.map.remove(curr.key, curr.hash);
         this.currEntry = null;
      }
   }

   protected static final class ValueCollection<K, V> extends SWMRHashTable.ViewSet<K, V, V> implements Collection<V> {
      protected ValueCollection(final SWMRHashTable<K, V> map) {
         super(map);
      }

      @Override
      public Iterator<V> iterator() {
         return new SWMRHashTable.ValueIterator<>(this.map.getTableAcquire(), this.map);
      }

      @Override
      public void forEach(final Consumer<? super V> action) {
         Objects.requireNonNull(action, "Null action");
         this.map.forEachValue(action);
      }

      @Override
      public boolean contains(final Object object) {
         Objects.requireNonNull(object, "Null object");
         return this.map.containsValue(object);
      }

      @Override
      public boolean remove(final Object object) {
         Objects.requireNonNull(object, "Null object");
         Iterator<V> itr = this.iterator();

         while (itr.hasNext()) {
            V val = itr.next();
            if (val == object || val.equals(object)) {
               itr.remove();
               return true;
            }
         }

         return false;
      }

      @Override
      public boolean removeIf(final Predicate<? super V> filter) {
         Objects.requireNonNull(filter, "Null filter");
         return this.map.removeIf((key, value) -> filter.test(value)) != 0;
      }

      @Override
      public boolean retainAll(final Collection<?> collection) {
         Objects.requireNonNull(collection, "Null collection");
         return this.map.removeIf((key, value) -> !collection.contains(value)) != 0;
      }

      @Override
      public String toString() {
         return CollectionUtil.toString(this, "SWMRHashTableValues");
      }
   }

   protected static final class ValueIterator<K, V> extends SWMRHashTable.TableEntryIterator<K, V, V> {
      protected ValueIterator(final SWMRHashTable.TableEntry<K, V>[] table, final SWMRHashTable<K, V> map) {
         super(table, map);
      }

      @Override
      public V next() {
         SWMRHashTable.TableEntry<K, V> entry = this.advanceEntry();
         if (entry == null) {
            throw new NoSuchElementException();
         } else {
            return entry.getValueAcquire();
         }
      }
   }

   protected abstract static class ViewCollection<K, V, T> implements Collection<T> {
      protected final SWMRHashTable<K, V> map;

      protected ViewCollection(final SWMRHashTable<K, V> map) {
         super();
         this.map = map;
      }

      @Override
      public boolean add(final T element) {
         throw new UnsupportedOperationException();
      }

      @Override
      public boolean addAll(final Collection<? extends T> collections) {
         throw new UnsupportedOperationException();
      }

      @Override
      public boolean removeAll(final Collection<?> collection) {
         Objects.requireNonNull(collection, "Null collection");
         boolean modified = false;

         for (Object element : collection) {
            modified |= this.remove(element);
         }

         return modified;
      }

      @Override
      public int size() {
         return this.map.size();
      }

      @Override
      public boolean isEmpty() {
         return this.size() == 0;
      }

      @Override
      public void clear() {
         this.map.clear();
      }

      @Override
      public boolean containsAll(final Collection<?> collection) {
         Objects.requireNonNull(collection, "Null collection");

         for (Object element : collection) {
            if (!this.contains(element)) {
               return false;
            }
         }

         return true;
      }

      @Override
      public Object[] toArray() {
         List<T> list = new ArrayList<>(this.size());
         this.forEach(list::add);
         return list.toArray();
      }

      @Override
      public <E> E[] toArray(final E[] array) {
         List<T> list = new ArrayList(this.size());
         this.forEach(list::add);
         return list.toArray(array);
      }

      @Override
      public <E> E[] toArray(final IntFunction<E[]> generator) {
         List<T> list = new ArrayList(this.size());
         this.forEach(list::add);
         return list.toArray(generator);
      }

      @Override
      public int hashCode() {
         int hash = 0;

         for (T element : this) {
            hash += element == null ? 0 : element.hashCode();
         }

         return hash;
      }

      @Override
      public Spliterator<T> spliterator() {
         return Spliterators.spliterator(this, 256);
      }
   }

   protected abstract static class ViewSet<K, V, T> extends SWMRHashTable.ViewCollection<K, V, T> implements Set<T> {
      protected ViewSet(final SWMRHashTable<K, V> map) {
         super(map);
      }

      @Override
      public boolean equals(final Object obj) {
         if (this == obj) {
            return true;
         } else if (!(obj instanceof Set<?> other)) {
            return false;
         } else {
            return other.size() != this.size() ? false : this.containsAll(other);
         }
      }
   }
}
