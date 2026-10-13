package ca.spottedleaf.concurrentutil.map;

import ca.spottedleaf.common.function.BiLongObjectConsumer;
import ca.spottedleaf.common.util.HashUtil;
import ca.spottedleaf.common.util.IntegerUtil;
import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

public class SWMRLong2ObjectHashTable<V> {
   protected int size;
   protected SWMRLong2ObjectHashTable.TableEntry<V>[] table;
   protected final float loadFactor;
   protected static final VarHandle SIZE_HANDLE = ConcurrentUtil.getVarHandle(SWMRLong2ObjectHashTable.class, "size", int.class);
   protected static final VarHandle TABLE_HANDLE = ConcurrentUtil.getVarHandle(
      SWMRLong2ObjectHashTable.class, "table", SWMRLong2ObjectHashTable.TableEntry[].class
   );
   protected static final int DEFAULT_CAPACITY = 16;
   protected static final float DEFAULT_LOAD_FACTOR = 0.75F;
   protected static final int MAXIMUM_CAPACITY = 1073741824;
   protected int threshold;

   protected final int getSizePlain() {
      return (int)SIZE_HANDLE.get((SWMRLong2ObjectHashTable)this);
   }

   protected final int getSizeOpaque() {
      return (int)SIZE_HANDLE.getOpaque((SWMRLong2ObjectHashTable)this);
   }

   protected final int getSizeAcquire() {
      return (int)SIZE_HANDLE.getAcquire((SWMRLong2ObjectHashTable)this);
   }

   protected final void setSizePlain(final int value) {
      SIZE_HANDLE.set((SWMRLong2ObjectHashTable)this, (int)value);
   }

   protected final void setSizeOpaque(final int value) {
      SIZE_HANDLE.setOpaque((SWMRLong2ObjectHashTable)this, (int)value);
   }

   protected final void setSizeRelease(final int value) {
      SIZE_HANDLE.setRelease((SWMRLong2ObjectHashTable)this, (int)value);
   }

   protected final SWMRLong2ObjectHashTable.TableEntry<V>[] getTablePlain() {
      return (SWMRLong2ObjectHashTable.TableEntry[])TABLE_HANDLE.get((SWMRLong2ObjectHashTable)this);
   }

   protected final SWMRLong2ObjectHashTable.TableEntry<V>[] getTableAcquire() {
      return (SWMRLong2ObjectHashTable.TableEntry[])TABLE_HANDLE.getAcquire((SWMRLong2ObjectHashTable)this);
   }

   protected final void setTablePlain(final SWMRLong2ObjectHashTable.TableEntry<V>[] table) {
      TABLE_HANDLE.set((SWMRLong2ObjectHashTable)this, (SWMRLong2ObjectHashTable.TableEntry[])table);
   }

   protected final void setTableRelease(final SWMRLong2ObjectHashTable.TableEntry<V>[] table) {
      TABLE_HANDLE.setRelease((SWMRLong2ObjectHashTable)this, (SWMRLong2ObjectHashTable.TableEntry[])table);
   }

   public SWMRLong2ObjectHashTable() {
      this(16, 0.75F);
   }

   public SWMRLong2ObjectHashTable(final int capacity) {
      this(capacity, 0.75F);
   }

   public SWMRLong2ObjectHashTable(final int capacity, final float loadFactor) {
      super();
      int tableSize = getCapacityFor(capacity);
      if (!(loadFactor <= 0.0) && Float.isFinite(loadFactor)) {
         SWMRLong2ObjectHashTable.TableEntry<V>[] table = new SWMRLong2ObjectHashTable.TableEntry[tableSize];
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

   public SWMRLong2ObjectHashTable(final SWMRLong2ObjectHashTable<V> other) {
      this(16, 0.75F, other);
   }

   public SWMRLong2ObjectHashTable(final int capacity, final SWMRLong2ObjectHashTable<V> other) {
      this(capacity, 0.75F, other);
   }

   public SWMRLong2ObjectHashTable(final int capacity, final float loadFactor, final SWMRLong2ObjectHashTable<V> other) {
      this(Math.max(Objects.requireNonNull(other, "Null map").size(), capacity), loadFactor);
      this.putAll(other);
   }

   protected static <V> SWMRLong2ObjectHashTable.TableEntry<V> getAtIndexOpaque(final SWMRLong2ObjectHashTable.TableEntry<V>[] table, final int index) {
      return (SWMRLong2ObjectHashTable.TableEntry)SWMRLong2ObjectHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .getOpaque((SWMRLong2ObjectHashTable.TableEntry[])table, (int)index);
   }

   protected static <V> void setAtIndexRelease(
      final SWMRLong2ObjectHashTable.TableEntry<V>[] table, final int index, final SWMRLong2ObjectHashTable.TableEntry<V> value
   ) {
      SWMRLong2ObjectHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .setRelease((SWMRLong2ObjectHashTable.TableEntry[])table, (int)index, (SWMRLong2ObjectHashTable.TableEntry)value);
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

   protected final SWMRLong2ObjectHashTable.TableEntry<V> getEntryForOpaque(final long key) {
      int hash = getHash(key);
      SWMRLong2ObjectHashTable.TableEntry<V>[] table = this.getTableAcquire();

      for (SWMRLong2ObjectHashTable.TableEntry<V> curr = getAtIndexOpaque(table, hash & table.length - 1); curr != null; curr = curr.getNextOpaque()) {
         if (key == curr.key) {
            return curr;
         }
      }

      return null;
   }

   protected final SWMRLong2ObjectHashTable.TableEntry<V> getEntryForPlain(final long key) {
      int hash = getHash(key);
      SWMRLong2ObjectHashTable.TableEntry<V>[] table = this.getTablePlain();

      for (SWMRLong2ObjectHashTable.TableEntry<V> curr = table[hash & table.length - 1]; curr != null; curr = curr.getNextPlain()) {
         if (key == curr.key) {
            return curr;
         }
      }

      return null;
   }

   protected static int getHash(final long key) {
      return (int)HashUtil.mix(key);
   }

   protected static int getTargetCapacity(final int capacity, final float loadFactor) {
      double ret = (double)capacity * loadFactor;
      return !Double.isInfinite(ret) && !(ret >= 2.147483647E9) ? (int)ret : -1;
   }

   @Override
   public boolean equals(final Object obj) {
      if (this == obj) {
         return true;
      } else if (!(obj instanceof SWMRLong2ObjectHashTable<?> other)) {
         return false;
      } else {
         if (this.size() != other.size()) {
            return false;
         }

         SWMRLong2ObjectHashTable.TableEntry<V>[] table = this.getTableAcquire();
         int i = 0;

         for (int len = table.length; i < len; i++) {
            for (SWMRLong2ObjectHashTable.TableEntry<V> curr = getAtIndexOpaque(table, i); curr != null; curr = curr.getNextOpaque()) {
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
      SWMRLong2ObjectHashTable.TableEntry<V>[] table = this.getTableAcquire();
      int i = 0;

      for (int len = table.length; i < len; i++) {
         for (SWMRLong2ObjectHashTable.TableEntry<V> curr = getAtIndexOpaque(table, i); curr != null; curr = curr.getNextOpaque()) {
            hash += curr.hashCode();
         }
      }

      return hash;
   }

   @Override
   public String toString() {
      StringBuilder builder = new StringBuilder(64);
      builder.append("SingleWriterMultiReaderHashMap:{");
      this.forEach((key, value) -> builder.append("{key: \"").append(key).append("\", value: \"").append(value).append("\"}"));
      return builder.append('}').toString();
   }

   public SWMRLong2ObjectHashTable<V> clone() {
      return new SWMRLong2ObjectHashTable<>(this.getTableAcquire().length, this.loadFactor, this);
   }

   public void forEach(final Consumer<? super SWMRLong2ObjectHashTable.TableEntry<V>> action) {
      Objects.requireNonNull(action, "Null action");
      SWMRLong2ObjectHashTable.TableEntry<V>[] table = this.getTableAcquire();
      int i = 0;

      for (int len = table.length; i < len; i++) {
         for (SWMRLong2ObjectHashTable.TableEntry<V> curr = getAtIndexOpaque(table, i); curr != null; curr = curr.getNextOpaque()) {
            action.accept(curr);
         }
      }
   }

   public void forEach(final BiLongObjectConsumer<? super V> action) {
      Objects.requireNonNull(action, "Null action");
      SWMRLong2ObjectHashTable.TableEntry<V>[] table = this.getTableAcquire();
      int i = 0;

      for (int len = table.length; i < len; i++) {
         for (SWMRLong2ObjectHashTable.TableEntry<V> curr = getAtIndexOpaque(table, i); curr != null; curr = curr.getNextOpaque()) {
            V value = curr.getValueAcquire();
            action.accept(curr.key, value);
         }
      }
   }

   public void forEachKey(final LongConsumer action) {
      Objects.requireNonNull(action, "Null action");
      SWMRLong2ObjectHashTable.TableEntry<V>[] table = this.getTableAcquire();
      int i = 0;

      for (int len = table.length; i < len; i++) {
         for (SWMRLong2ObjectHashTable.TableEntry<V> curr = getAtIndexOpaque(table, i); curr != null; curr = curr.getNextOpaque()) {
            action.accept(curr.key);
         }
      }
   }

   public void forEachValue(final Consumer<? super V> action) {
      Objects.requireNonNull(action, "Null action");
      SWMRLong2ObjectHashTable.TableEntry<V>[] table = this.getTableAcquire();
      int i = 0;

      for (int len = table.length; i < len; i++) {
         for (SWMRLong2ObjectHashTable.TableEntry<V> curr = getAtIndexOpaque(table, i); curr != null; curr = curr.getNextOpaque()) {
            V value = curr.getValueAcquire();
            action.accept(value);
         }
      }
   }

   public V get(final long key) {
      SWMRLong2ObjectHashTable.TableEntry<V> entry = this.getEntryForOpaque(key);
      return entry == null ? null : entry.getValueAcquire();
   }

   public boolean containsKey(final long key) {
      return this.get(key) != null;
   }

   public V getOrDefault(final long key, final V defaultValue) {
      SWMRLong2ObjectHashTable.TableEntry<V> entry = this.getEntryForOpaque(key);
      return entry == null ? defaultValue : entry.getValueAcquire();
   }

   public int size() {
      return this.getSizeAcquire();
   }

   public boolean isEmpty() {
      return this.getSizeAcquire() == 0;
   }

   protected final void checkResize(final int minCapacity) {
      if (minCapacity > this.threshold && this.threshold >= 0) {
         SWMRLong2ObjectHashTable.TableEntry<V>[] table = this.getTablePlain();
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

         SWMRLong2ObjectHashTable.TableEntry<V>[] newTable = new SWMRLong2ObjectHashTable.TableEntry[newCapacity];
         int indexMask = newCapacity - 1;
         int i = 0;

         for (int len = table.length; i < len; i++) {
            for (SWMRLong2ObjectHashTable.TableEntry<V> entry = table[i]; entry != null; entry = entry.getNextPlain()) {
               long key = entry.key;
               int hash = getHash(key);
               int index = hash & indexMask;
               SWMRLong2ObjectHashTable.TableEntry<V> insert = new SWMRLong2ObjectHashTable.TableEntry<>(key, entry.getValuePlain());
               SWMRLong2ObjectHashTable.TableEntry<V> prev = newTable[index];
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

   protected final V put(final long key, final V value, final boolean onlyIfAbsent) {
      SWMRLong2ObjectHashTable.TableEntry<V>[] table = this.getTablePlain();
      int hash = getHash(key);
      int index = hash & table.length - 1;
      SWMRLong2ObjectHashTable.TableEntry<V> head = table[index];
      if (head == null) {
         SWMRLong2ObjectHashTable.TableEntry<V> insert = new SWMRLong2ObjectHashTable.TableEntry<>(key, value);
         setAtIndexRelease(table, index, insert);
         this.addToSize(1);
         return null;
      }

      SWMRLong2ObjectHashTable.TableEntry<V> curr = head;

      while (key != curr.key) {
         SWMRLong2ObjectHashTable.TableEntry<V> next = curr.getNextPlain();
         if (next == null) {
            SWMRLong2ObjectHashTable.TableEntry<V> insert = new SWMRLong2ObjectHashTable.TableEntry<>(key, value);
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

   public V put(final long key, final V value) {
      Objects.requireNonNull(value, "Null value");
      return this.put(key, value, false);
   }

   public V putIfAbsent(final long key, final V value) {
      Objects.requireNonNull(value, "Null value");
      return this.put(key, value, true);
   }

   protected final V remove(final long key, final int hash) {
      SWMRLong2ObjectHashTable.TableEntry<V>[] table = this.getTablePlain();
      int index = table.length - 1 & hash;
      SWMRLong2ObjectHashTable.TableEntry<V> head = table[index];
      if (head == null) {
         return null;
      }

      if (head.key == key) {
         setAtIndexRelease(table, index, head.getNextPlain());
         this.removeFromSize(1);
         return head.getValuePlain();
      }

      SWMRLong2ObjectHashTable.TableEntry<V> curr = head.getNextPlain();
      SWMRLong2ObjectHashTable.TableEntry<V> prev = head;

      while (curr != null) {
         if (key == curr.key) {
            prev.setNextRelease(curr.getNextPlain());
            this.removeFromSize(1);
            return curr.getValuePlain();
         }

         prev = curr;
         curr = curr.getNextPlain();
      }

      return null;
   }

   protected final V remove(final long key, final int hash, final V expect) {
      SWMRLong2ObjectHashTable.TableEntry<V>[] table = this.getTablePlain();
      int index = table.length - 1 & hash;
      SWMRLong2ObjectHashTable.TableEntry<V> head = table[index];
      if (head == null) {
         return null;
      }

      if (head.key == key) {
         V val = head.value;
         if (val != expect && !val.equals(expect)) {
            return null;
         }

         setAtIndexRelease(table, index, head.getNextPlain());
         this.removeFromSize(1);
         return head.getValuePlain();
      } else {
         SWMRLong2ObjectHashTable.TableEntry<V> curr = head.getNextPlain();
         SWMRLong2ObjectHashTable.TableEntry<V> prev = head;

         while (curr != null) {
            if (key == curr.key) {
               V val = curr.value;
               if (val != expect && !val.equals(expect)) {
                  return null;
               }

               prev.setNextRelease(curr.getNextPlain());
               this.removeFromSize(1);
               return curr.getValuePlain();
            }

            prev = curr;
            curr = curr.getNextPlain();
         }

         return null;
      }
   }

   public V remove(final long key) {
      return this.remove(key, getHash(key));
   }

   public boolean remove(final long key, final V expect) {
      return this.remove(key, getHash(key), expect) != null;
   }

   public void putAll(final SWMRLong2ObjectHashTable<? extends V> map) {
      Objects.requireNonNull(map, "Null map");
      int size = map.size();
      this.checkResize(Math.max(this.getSizePlain() + size / 2, size));
      map.forEach(this::put);
   }

   public void clear() {
      Arrays.fill(this.getTablePlain(), null);
      this.setSizeRelease(0);
   }

   public static final class TableEntry<V> {
      private static final VarHandle TABLE_ENTRY_ARRAY_HANDLE = ConcurrentUtil.getArrayHandle(SWMRLong2ObjectHashTable.TableEntry[].class);
      private final long key;
      private V value;
      private SWMRLong2ObjectHashTable.TableEntry<V> next;
      private static final VarHandle VALUE_HANDLE = ConcurrentUtil.getVarHandle(SWMRLong2ObjectHashTable.TableEntry.class, "value", Object.class);
      private static final VarHandle NEXT_HANDLE = ConcurrentUtil.getVarHandle(
         SWMRLong2ObjectHashTable.TableEntry.class, "next", SWMRLong2ObjectHashTable.TableEntry.class
      );

      private V getValuePlain() {
         return (V)(Object)VALUE_HANDLE.get((SWMRLong2ObjectHashTable.TableEntry)this);
      }

      private V getValueAcquire() {
         return (V)(Object)VALUE_HANDLE.getAcquire((SWMRLong2ObjectHashTable.TableEntry)this);
      }

      private void setValueRelease(final V to) {
         VALUE_HANDLE.setRelease((SWMRLong2ObjectHashTable.TableEntry)this, (Object)to);
      }

      private SWMRLong2ObjectHashTable.TableEntry<V> getNextPlain() {
         return (SWMRLong2ObjectHashTable.TableEntry)NEXT_HANDLE.get((SWMRLong2ObjectHashTable.TableEntry)this);
      }

      private SWMRLong2ObjectHashTable.TableEntry<V> getNextOpaque() {
         return (SWMRLong2ObjectHashTable.TableEntry)NEXT_HANDLE.getOpaque((SWMRLong2ObjectHashTable.TableEntry)this);
      }

      private void setNextPlain(final SWMRLong2ObjectHashTable.TableEntry<V> next) {
         NEXT_HANDLE.set((SWMRLong2ObjectHashTable.TableEntry)this, (SWMRLong2ObjectHashTable.TableEntry)next);
      }

      private void setNextRelease(final SWMRLong2ObjectHashTable.TableEntry<V> next) {
         NEXT_HANDLE.setRelease((SWMRLong2ObjectHashTable.TableEntry)this, (SWMRLong2ObjectHashTable.TableEntry)next);
      }

      private TableEntry(final long key, final V value) {
         super();
         this.key = key;
         this.value = value;
      }

      public long getKey() {
         return this.key;
      }

      public V getValue() {
         return this.getValueAcquire();
      }
   }
}
