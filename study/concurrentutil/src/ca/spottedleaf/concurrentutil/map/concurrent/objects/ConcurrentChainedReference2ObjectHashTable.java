package ca.spottedleaf.concurrentutil.map.concurrent.objects;

import ca.spottedleaf.common.util.HashUtil;
import ca.spottedleaf.common.util.IntegerUtil;
import ca.spottedleaf.common.util.ThrowUtil;
import ca.spottedleaf.concurrentutil.collection.iterator.BaseObjectIterator;
import ca.spottedleaf.concurrentutil.map.BaseMapCollection;
import ca.spottedleaf.concurrentutil.map.BaseMapSet;
import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

public class ConcurrentChainedReference2ObjectHashTable<K, V> implements Iterable<ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>> {
   private static final ConcurrentChainedReference2ObjectHashTable.TableEntry<?, ?> RESIZE_NODE = new ConcurrentChainedReference2ObjectHashTable.TableEntry(
      null, null
   );
   protected static final int DEFAULT_CAPACITY = 16;
   protected static final float DEFAULT_LOAD_FACTOR = 0.75F;
   protected static final int MAXIMUM_CAPACITY = 1073741824;
   protected final AtomicLong size = new AtomicLong();
   protected final float loadFactor;
   protected volatile ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] table;
   protected volatile ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] nextTable;
   protected static final int THRESHOLD_NO_RESIZE = -1;
   protected static final int THRESHOLD_RESIZING = -2;
   protected volatile int threshold;
   protected static final VarHandle THRESHOLD_HANDLE = ConcurrentUtil.getVarHandle(ConcurrentChainedReference2ObjectHashTable.class, "threshold", int.class);
   protected ConcurrentChainedReference2ObjectHashTable.Keys<K> keys;
   protected ConcurrentChainedReference2ObjectHashTable.Values<V> values;
   protected ConcurrentChainedReference2ObjectHashTable.EntrySet<K, V> entrySet;

   public static int defaultCapacity() {
      return 16;
   }

   public static float defaultLoadFactor() {
      return 0.75F;
   }

   protected final int getThresholdAcquire() {
      return (int)THRESHOLD_HANDLE.getAcquire((ConcurrentChainedReference2ObjectHashTable)this);
   }

   protected final int getThresholdVolatile() {
      return (int)THRESHOLD_HANDLE.getVolatile((ConcurrentChainedReference2ObjectHashTable)this);
   }

   protected final void setThresholdPlain(final int threshold) {
      THRESHOLD_HANDLE.set((ConcurrentChainedReference2ObjectHashTable)this, (int)threshold);
   }

   protected final void setThresholdRelease(final int threshold) {
      THRESHOLD_HANDLE.setRelease((ConcurrentChainedReference2ObjectHashTable)this, (int)threshold);
   }

   protected final void setThresholdVolatile(final int threshold) {
      THRESHOLD_HANDLE.setVolatile((ConcurrentChainedReference2ObjectHashTable)this, (int)threshold);
   }

   protected final int compareExchangeThresholdVolatile(final int expect, final int update) {
      return (int)THRESHOLD_HANDLE.compareAndExchange((ConcurrentChainedReference2ObjectHashTable)this, (int)expect, (int)update);
   }

   public ConcurrentChainedReference2ObjectHashTable() {
      this(16, 0.75F);
   }

   protected static int getTargetThreshold(final int capacity, final float loadFactor) {
      double ret = (double)capacity * loadFactor;
      return !Double.isInfinite(ret) && !(ret >= 2.147483646E9) ? (int)Math.ceil(ret) : -1;
   }

   protected static int getCapacityFor(final int capacity) {
      if (capacity <= 0) {
         throw new IllegalArgumentException("Invalid capacity: " + capacity);
      } else {
         return capacity >= 1073741824 ? 1073741824 : IntegerUtil.roundCeilLog2(capacity);
      }
   }

   protected ConcurrentChainedReference2ObjectHashTable(final int capacity, final float loadFactor) {
      super();
      int tableSize = getCapacityFor(capacity);
      if (!(loadFactor <= 0.0) && Float.isFinite(loadFactor)) {
         if (tableSize == 1073741824) {
            this.setThresholdPlain(-1);
         } else {
            this.setThresholdPlain(getTargetThreshold(tableSize, loadFactor));
         }

         this.loadFactor = loadFactor;
         this.nextTable = this.table = new ConcurrentChainedReference2ObjectHashTable.TableEntry[tableSize];
      } else {
         throw new IllegalArgumentException("Invalid load factor: " + loadFactor);
      }
   }

   public static <K, V> ConcurrentChainedReference2ObjectHashTable<K, V> createWithCapacity(final int capacity) {
      return createWithCapacity(capacity, 0.75F);
   }

   public static <K, V> ConcurrentChainedReference2ObjectHashTable<K, V> createWithCapacity(final int capacity, final float loadFactor) {
      return new ConcurrentChainedReference2ObjectHashTable<>(capacity, loadFactor);
   }

   public static <K, V> ConcurrentChainedReference2ObjectHashTable<K, V> createWithExpected(final int expected) {
      return createWithExpected(expected, 0.75F);
   }

   public static <K, V> ConcurrentChainedReference2ObjectHashTable<K, V> createWithExpected(final int expected, final float loadFactor) {
      double capacity = Math.ceil((double)expected / loadFactor);
      if (!Double.isFinite(capacity)) {
         throw new IllegalArgumentException("Invalid load factor");
      }

      if (capacity > 2.147483647E9) {
         capacity = 2.147483647E9;
      }

      return createWithCapacity((int)capacity, loadFactor);
   }

   protected static int getHash(final Object key) {
      return HashUtil.mix(System.identityHashCode(key));
   }

   public final float getLoadFactor() {
      return this.loadFactor;
   }

   protected static <K, V> ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> getAtIndexAcquire(
      final ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] table, final int index
   ) {
      return (ConcurrentChainedReference2ObjectHashTable.TableEntry)ConcurrentChainedReference2ObjectHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .getAcquire((ConcurrentChainedReference2ObjectHashTable.TableEntry[])table, (int)index);
   }

   protected static <K, V> void setAtIndexRelease(
      final ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] table,
      final int index,
      final ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> value
   ) {
      ConcurrentChainedReference2ObjectHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .setRelease((ConcurrentChainedReference2ObjectHashTable.TableEntry[])table, (int)index, (ConcurrentChainedReference2ObjectHashTable.TableEntry)value);
   }

   protected static <K, V> void setAtIndexVolatile(
      final ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] table,
      final int index,
      final ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> value
   ) {
      ConcurrentChainedReference2ObjectHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .setVolatile((ConcurrentChainedReference2ObjectHashTable.TableEntry[])table, (int)index, (ConcurrentChainedReference2ObjectHashTable.TableEntry)value);
   }

   protected static <K, V> ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> compareAndExchangeAtIndexVolatile(
      final ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] table,
      final int index,
      final ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> expect,
      final ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> update
   ) {
      return (ConcurrentChainedReference2ObjectHashTable.TableEntry)ConcurrentChainedReference2ObjectHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .compareAndExchange(
            (ConcurrentChainedReference2ObjectHashTable.TableEntry[])table,
            (int)index,
            (ConcurrentChainedReference2ObjectHashTable.TableEntry)expect,
            (ConcurrentChainedReference2ObjectHashTable.TableEntry)update
         );
   }

   protected ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] fetchNewTable(
      final ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] expectedCurr
   ) {
      ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] candidate = this.nextTable;
      ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] current = this.table;
      return expectedCurr == current ? candidate : current;
   }

   protected final ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> getNode(final K key) {
      int hash = HashUtil.mix(System.identityHashCode(key));
      ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] table = this.table;

      while (true) {
         ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> node = getAtIndexAcquire(table, hash & table.length - 1);
         if (node == null) {
            return node;
         }

         if (node != RESIZE_NODE) {
            while (node.key != key) {
               node = node.getNextAcquire();
               if (node == null) {
                  return node;
               }
            }

            return node;
         }

         table = this.fetchNewTable(table);
      }
   }

   public V get(final K key) {
      Objects.requireNonNull(key, "Key may not be null");
      ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> node = this.getNode(key);
      return node == null ? null : node.getValueVolatile();
   }

   public V getOrDefault(final K key, final V defaultValue) {
      Objects.requireNonNull(key, "Key may not be null");
      ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> node = this.getNode(key);
      if (node == null) {
         return defaultValue;
      }

      V ret = node.getValueVolatile();
      return ret == null ? defaultValue : ret;
   }

   public boolean containsKey(final K key) {
      Objects.requireNonNull(key, "Key may not be null");
      ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> node = this.getNode(key);
      return node != null && node.getValueVolatile() != null;
   }

   public boolean containsValue(final V value) {
      Objects.requireNonNull(value, "Value may not be null");
      ConcurrentChainedReference2ObjectHashTable.NodeIterator<K, V> iterator = new ConcurrentChainedReference2ObjectHashTable.NodeIterator<>(this);

      ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> node;
      while ((node = iterator.findNext()) != null) {
         V nodeValue = node.getValueAcquire();
         if (value == nodeValue || value.equals(nodeValue)) {
            return true;
         }
      }

      return false;
   }

   public boolean contains(final K key, final V value) {
      Objects.requireNonNull(key, "Key may not be null");
      Objects.requireNonNull(value, "Value may not be null");
      ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> node = this.getNode(key);
      if (node == null) {
         return false;
      }

      V nodeValue = node.getValueAcquire();
      return value == nodeValue || value.equals(nodeValue);
   }

   public int size() {
      return (int)Math.clamp(this.size.get(), 0L, 2147483647L);
   }

   public boolean isEmpty() {
      return this.size.get() <= 0L;
   }

   protected final void addSize(final long count) {
      long sum = this.size.addAndGet(count);
      int threshold = this.getThresholdAcquire();
      if (threshold >= 0L) {
         if (sum >= threshold) {
            if (threshold == this.compareExchangeThresholdVolatile(threshold, -2)) {
               this.resize(sum);
            }
         }
      }
   }

   private void resize(final long sum) {
      double targetD = (double)sum / this.loadFactor + 1.0;
      int capacity;
      if (targetD >= 1.0737418E9F) {
         capacity = 1073741824;
      } else {
         capacity = (int)Math.ceil(targetD);
         capacity = IntegerUtil.roundCeilLog2(capacity);
         capacity = Math.min(capacity, 1073741824);
      }

      ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] newTable = new ConcurrentChainedReference2ObjectHashTable.TableEntry[capacity];
      ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] oldTable = this.table;
      this.nextTable = newTable;
      int capOldShift = IntegerUtil.floorLog2(oldTable.length);
      int capDiffShift = IntegerUtil.floorLog2(capacity) - capOldShift;
      if (capDiffShift == 0) {
         throw new IllegalStateException("Resizing to same size");
      }

      ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] work = new ConcurrentChainedReference2ObjectHashTable.TableEntry[1 << capDiffShift];
      int i = 0;

      for (int len = oldTable.length; i < len; i++) {
         ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> binNode = getAtIndexAcquire(oldTable, i);

         while (
            binNode != null
               || null
                  != (binNode = compareAndExchangeAtIndexVolatile(oldTable, i, null, (ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>)RESIZE_NODE))
         ) {
            synchronized (binNode) {
               if (binNode == (binNode = getAtIndexAcquire(oldTable, i))) {
                  ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> next = binNode.getNextPlain();
                  if (next == null) {
                     newTable[HashUtil.mix(System.identityHashCode(binNode.key)) & capacity - 1] = binNode;
                  } else {
                     Arrays.fill(work, null);

                     for (ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> curr = binNode; curr != null; curr = curr.getNextPlain()) {
                        int newTableIdx = HashUtil.mix(System.identityHashCode(curr.key)) & capacity - 1;
                        int workIdx = newTableIdx >>> capOldShift;
                        ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> replace = new ConcurrentChainedReference2ObjectHashTable.TableEntry<>(
                           curr.key, curr.getValuePlain()
                        );
                        ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> workNode = work[workIdx];
                        work[workIdx] = replace;
                        if (workNode == null) {
                           newTable[newTableIdx] = replace;
                        } else {
                           workNode.setNextPlain(replace);
                        }
                     }
                  }

                  setAtIndexRelease(oldTable, i, (ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>)RESIZE_NODE);
                  break;
               }
            }
         }
      }

      if (capacity == 1073741824) {
         i = -1;
      } else {
         i = getTargetThreshold(capacity, this.loadFactor);
      }

      this.table = newTable;
      this.setThresholdVolatile(i);
   }

   protected final void subSize(final long count) {
      this.size.getAndAdd(-count);
   }

   public V put(final K key, final V value) {
      Objects.requireNonNull(key, "Key may not be null");
      Objects.requireNonNull(value, "Value may not be null");
      int hash = HashUtil.mix(System.identityHashCode(key));
      ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] table = this.table;

      label50:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> node = getAtIndexAcquire(table, index);

         while (
            node != null
               || null
                  != (node = compareAndExchangeAtIndexVolatile(table, index, null, new ConcurrentChainedReference2ObjectHashTable.TableEntry<>(key, value)))
         ) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label50;
            }

            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> prev = null;

                  while (node != null) {
                     if (node.key == key) {
                        V ret = node.getValuePlain();
                        node.setValueVolatile(value);
                        return ret;
                     }

                     prev = node;
                     node = node.getNextPlain();
                  }

                  prev.setNextRelease(new ConcurrentChainedReference2ObjectHashTable.TableEntry<>(key, value));
                  break label50;
               }
            }
         }

         this.addSize(1L);
         return null;
      }

      this.addSize(1L);
      return null;
   }

   public V putIfAbsent(final K key, final V value) {
      Objects.requireNonNull(key, "Key may not be null");
      Objects.requireNonNull(value, "Value may not be null");
      int hash = HashUtil.mix(System.identityHashCode(key));
      ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] table = this.table;

      label56:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> node = getAtIndexAcquire(table, index);

         while (
            node != null
               || null
                  != (node = compareAndExchangeAtIndexVolatile(table, index, null, new ConcurrentChainedReference2ObjectHashTable.TableEntry<>(key, value)))
         ) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label56;
            }

            if (node.key == key) {
               V ret = node.getValueVolatile();
               if (ret != null) {
                  return ret;
               }
            }

            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> prev = null;

                  while (node != null) {
                     if (node.key == key) {
                        return node.getValuePlain();
                     }

                     prev = node;
                     node = node.getNextPlain();
                  }

                  prev.setNextRelease(new ConcurrentChainedReference2ObjectHashTable.TableEntry<>(key, value));
                  break label56;
               }
            }
         }

         this.addSize(1L);
         return null;
      }

      this.addSize(1L);
      return null;
   }

   public V replace(final K key, final V value) {
      Objects.requireNonNull(key, "Key may not be null");
      Objects.requireNonNull(value, "Value may not be null");
      int hash = HashUtil.mix(System.identityHashCode(key));
      ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] table = this.table;

      label45:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label45;
            }

            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  while (node != null) {
                     if (node.key == key) {
                        V ret = node.getValuePlain();
                        node.setValueVolatile(value);
                        return ret;
                     }

                     node = node.getNextPlain();
                  }

                  return null;
               }
            }
         }

         return null;
      }
   }

   public V replace(final K key, final V expect, final V update) {
      Objects.requireNonNull(expect, "Expect may not be null");
      Objects.requireNonNull(update, "Update may not be null");
      int hash = HashUtil.mix(System.identityHashCode(key));
      ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] table = this.table;

      label53:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label53;
            }

            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  while (node != null) {
                     if (node.key == key) {
                        V ret = node.getValuePlain();
                        if (expect != ret && !expect.equals(ret)) {
                           return ret;
                        }

                        node.setValueVolatile(update);
                        return ret;
                     }

                     node = node.getNextPlain();
                  }

                  return null;
               }
            }
         }

         return null;
      }
   }

   public V remove(final K key) {
      Objects.requireNonNull(key, "Key may not be null");
      int hash = HashUtil.mix(System.identityHashCode(key));
      ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] table = this.table;

      boolean removed;
      V ret;
      label53:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label53;
            }

            removed = false;
            ret = null;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> prev = null;

                  while (true) {
                     if (node == null) {
                        break label53;
                     }

                     if (node.key == key) {
                        ret = node.getValuePlain();
                        removed = true;
                        if (prev == null) {
                           setAtIndexRelease(table, index, node.getNextPlain());
                        } else {
                           prev.setNextRelease(node.getNextPlain());
                        }
                        break label53;
                     }

                     prev = node;
                     node = node.getNextPlain();
                  }
               }
            }
         }

         return null;
      }

      if (removed) {
         this.subSize(1L);
      }

      return ret;
   }

   public V remove(final K key, final V expect) {
      Objects.requireNonNull(key, "Key may not be null");
      int hash = HashUtil.mix(System.identityHashCode(key));
      ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] table = this.table;

      boolean removed;
      V ret;
      label58:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label58;
            }

            removed = false;
            ret = null;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> prev = null;

                  while (true) {
                     if (node == null) {
                        break label58;
                     }

                     if (node.key == key) {
                        ret = node.getValuePlain();
                        if (expect == ret || expect.equals(ret)) {
                           removed = true;
                           if (prev == null) {
                              setAtIndexRelease(table, index, node.getNextPlain());
                           } else {
                              prev.setNextRelease(node.getNextPlain());
                           }
                        }
                        break label58;
                     }

                     prev = node;
                     node = node.getNextPlain();
                  }
               }
            }
         }

         return null;
      }

      if (removed) {
         this.subSize(1L);
      }

      return ret;
   }

   public V removeIf(final K key, final ConcurrentChainedReference2ObjectHashTable.RemoveIfPredicate<K, ? super V> predicate) {
      Objects.requireNonNull(key, "Key may not be null");
      Objects.requireNonNull(predicate, "Predicate may not be null");
      int hash = HashUtil.mix(System.identityHashCode(key));
      ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] table = this.table;

      boolean removed;
      V ret;
      label55:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label55;
            }

            removed = false;
            ret = null;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> prev = null;

                  while (true) {
                     if (node == null) {
                        break label55;
                     }

                     if (node.key == key) {
                        ret = node.getValuePlain();
                        if (predicate.test(ret)) {
                           removed = true;
                           if (prev == null) {
                              setAtIndexRelease(table, index, node.getNextPlain());
                           } else {
                              prev.setNextRelease(node.getNextPlain());
                           }
                        }
                        break label55;
                     }

                     prev = node;
                     node = node.getNextPlain();
                  }
               }
            }
         }

         return null;
      }

      if (removed) {
         this.subSize(1L);
      }

      return ret;
   }

   public V compute(final K key, final ConcurrentChainedReference2ObjectHashTable.BiObjectObjectObjectFunction<K, ? super V, ? extends V> function) {
      Objects.requireNonNull(key, "Key may not be null");
      Objects.requireNonNull(function, "Function may not be null");
      int hash = HashUtil.mix(System.identityHashCode(key));
      ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] table = this.table;

      V ret;
      boolean removed;
      boolean added;
      label106:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> node = getAtIndexAcquire(table, index);

         while (true) {
            ret = null;
            if (node == null) {
               ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> insert = new ConcurrentChainedReference2ObjectHashTable.TableEntry<>(key, null);
               added = false;
               synchronized (insert) {
                  if (null == (node = compareAndExchangeAtIndexVolatile(table, index, null, insert))) {
                     try {
                        ret = (V)function.apply(key, null);
                     } catch (Throwable throwable) {
                        setAtIndexVolatile(table, index, null);
                        ThrowUtil.throwUnchecked(throwable);
                        return null;
                     }

                     if (ret == null) {
                        setAtIndexVolatile(table, index, null);
                        return ret;
                     }

                     insert.setValueRelease(ret);
                     added = true;
                  }
               }

               if (added) {
                  this.addSize(1L);
                  return ret;
               }
            }

            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               break;
            }

            removed = false;
            added = false;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> prev = null;

                  while (node != null) {
                     if (node.key == key) {
                        V old = node.getValuePlain();
                        V computed = (V)function.apply(key, old);
                        if (computed != null) {
                           node.setValueVolatile(computed);
                           return computed;
                        }

                        if (prev == null) {
                           setAtIndexRelease(table, index, node.getNextPlain());
                        } else {
                           prev.setNextRelease(node.getNextPlain());
                        }

                        removed = true;
                        break;
                     }

                     prev = node;
                     node = node.getNextPlain();
                  }

                  if (!removed) {
                     V computed = (V)function.apply(key, null);
                     if (computed != null) {
                        prev.setNextRelease(new ConcurrentChainedReference2ObjectHashTable.TableEntry<>(key, computed));
                        ret = computed;
                        added = true;
                     }
                  }
                  break label106;
               }
            }
         }
      }

      if (removed) {
         this.subSize(1L);
      }

      if (added) {
         this.addSize(1L);
      }

      return ret;
   }

   public V computeIfAbsent(final K key, final ConcurrentChainedReference2ObjectHashTable.BiObjectObjectFunction<? super K, ? extends V> function) {
      Objects.requireNonNull(key, "Key may not be null");
      Objects.requireNonNull(function, "Function may not be null");
      int hash = HashUtil.mix(System.identityHashCode(key));
      ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] table = this.table;

      V ret;
      boolean added;
      label97:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> node = getAtIndexAcquire(table, index);

         while (true) {
            ret = null;
            if (node == null) {
               ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> insert = new ConcurrentChainedReference2ObjectHashTable.TableEntry<>(key, null);
               boolean addedx = false;
               synchronized (insert) {
                  if (null == (node = compareAndExchangeAtIndexVolatile(table, index, null, insert))) {
                     try {
                        ret = (V)function.apply(key);
                     } catch (Throwable throwable) {
                        setAtIndexVolatile(table, index, null);
                        ThrowUtil.throwUnchecked(throwable);
                        return null;
                     }

                     if (ret == null) {
                        setAtIndexVolatile(table, index, null);
                        return null;
                     }

                     insert.setValueRelease(ret);
                     addedx = true;
                  }
               }

               if (addedx) {
                  this.addSize(1L);
                  return ret;
               }
            }

            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               break;
            }

            if (node.key == key) {
               ret = node.getValueVolatile();
               if (ret != null) {
                  return ret;
               }
            }

            added = false;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> prev = null;

                  while (node != null) {
                     if (node.key == key) {
                        return node.getValuePlain();
                     }

                     prev = node;
                     node = node.getNextPlain();
                  }

                  V computed = (V)function.apply(key);
                  if (computed != null) {
                     prev.setNextRelease(new ConcurrentChainedReference2ObjectHashTable.TableEntry<>(key, computed));
                     ret = computed;
                     added = true;
                  }
                  break label97;
               }
            }
         }
      }

      if (added) {
         this.addSize(1L);
      }

      return ret;
   }

   public V computeIfPresent(final K key, final ConcurrentChainedReference2ObjectHashTable.BiObjectObjectObjectFunction<K, ? super V, ? extends V> function) {
      Objects.requireNonNull(key, "Key may not be null");
      Objects.requireNonNull(function, "Function may not be null");
      int hash = HashUtil.mix(System.identityHashCode(key));
      ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] table = this.table;

      boolean removed;
      label61:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label61;
            }

            removed = false;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> prev = null;

                  while (true) {
                     if (node == null) {
                        break label61;
                     }

                     if (node.key == key) {
                        V old = node.getValuePlain();
                        V computed = (V)function.apply(key, old);
                        if (computed != null) {
                           node.setValueVolatile(computed);
                           return computed;
                        }

                        if (prev == null) {
                           setAtIndexRelease(table, index, node.getNextPlain());
                        } else {
                           prev.setNextRelease(node.getNextPlain());
                        }

                        removed = true;
                        break label61;
                     }

                     prev = node;
                     node = node.getNextPlain();
                  }
               }
            }
         }

         return null;
      }

      if (removed) {
         this.subSize(1L);
      }

      return null;
   }

   public V merge(final K key, final V def, final BiFunction<? super V, ? super V, ? extends V> function) {
      Objects.requireNonNull(key, "Key may not be null");
      Objects.requireNonNull(def, "Default value may not be null");
      Objects.requireNonNull(function, "Function may not be null");
      int hash = HashUtil.mix(System.identityHashCode(key));
      ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] table = this.table;

      boolean removed;
      boolean added;
      V ret;
      label73:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> node = getAtIndexAcquire(table, index);

         while (
            node != null
               || null != (node = compareAndExchangeAtIndexVolatile(table, index, null, new ConcurrentChainedReference2ObjectHashTable.TableEntry<>(key, def)))
         ) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label73;
            }

            removed = false;
            added = false;
            ret = null;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> prev = null;

                  while (node != null) {
                     if (node.key == key) {
                        V old = node.getValuePlain();
                        V computed = (V)function.apply(old, def);
                        if (computed != null) {
                           node.setValueVolatile(computed);
                           return computed;
                        }

                        if (prev == null) {
                           setAtIndexRelease(table, index, node.getNextPlain());
                        } else {
                           prev.setNextRelease(node.getNextPlain());
                        }

                        removed = true;
                        break;
                     }

                     prev = node;
                     node = node.getNextPlain();
                  }

                  if (!removed) {
                     prev.setNextRelease(new ConcurrentChainedReference2ObjectHashTable.TableEntry<>(key, def));
                     ret = def;
                     added = true;
                  }
                  break label73;
               }
            }
         }

         this.addSize(1L);
         return def;
      }

      if (removed) {
         this.subSize(1L);
      }

      if (added) {
         this.addSize(1L);
      }

      return ret;
   }

   public void clear() {
      ConcurrentChainedReference2ObjectHashTable.NodeIterator<K, V> nodeIterator = new ConcurrentChainedReference2ObjectHashTable.NodeIterator<>(this);

      ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> node;
      while ((node = nodeIterator.findNext()) != null) {
         this.remove(node.key);
      }
   }

   public BaseObjectIterator<ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>> entryIterator() {
      return new ConcurrentChainedReference2ObjectHashTable.EntryNodeIterator<>(this);
   }

   public final BaseObjectIterator<ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>> iterator() {
      return this.entryIterator();
   }

   public BaseObjectIterator<K> keyIterator() {
      return new ConcurrentChainedReference2ObjectHashTable.KeyIterator<>(this);
   }

   public BaseObjectIterator<V> valueIterator() {
      return new ConcurrentChainedReference2ObjectHashTable.ValueIterator<>(this);
   }

   public Set<K> keys() {
      ConcurrentChainedReference2ObjectHashTable.Keys<K> keys = this.keys;
      return keys != null ? keys : (this.keys = new ConcurrentChainedReference2ObjectHashTable.Keys<>(this));
   }

   public Collection<V> values() {
      ConcurrentChainedReference2ObjectHashTable.Values<V> values = this.values;
      return values != null ? values : (this.values = new ConcurrentChainedReference2ObjectHashTable.Values<>(this));
   }

   public Set<ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>> entrySet() {
      ConcurrentChainedReference2ObjectHashTable.EntrySet<K, V> entrySet = this.entrySet;
      return entrySet != null ? entrySet : (this.entrySet = new ConcurrentChainedReference2ObjectHashTable.EntrySet<>(this));
   }

   @FunctionalInterface
   public interface BiObjectObjectFunction<K, V> {
      V apply(final K key);
   }

   @FunctionalInterface
   public interface BiObjectObjectObjectFunction<P1, P2, R> {
      R apply(final P1 key, final P2 value);
   }

   protected static final class EntryNodeIterator<K, V> extends BaseObjectIterator<ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>> {
      protected ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> lastReturned;
      protected ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> nextToReturn;
      protected final ConcurrentChainedReference2ObjectHashTable.NodeIterator<K, V> nodeIterator;

      protected EntryNodeIterator(final ConcurrentChainedReference2ObjectHashTable<K, V> map) {
         super();
         this.nodeIterator = new ConcurrentChainedReference2ObjectHashTable.NodeIterator<>(map);
      }

      @Override
      public boolean hasNext() {
         return this.nextToReturn != null ? true : (this.nextToReturn = this.nodeIterator.findNext()) != null;
      }

      public ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> next() throws NoSuchElementException {
         ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> ret = this.nextToReturn;
         if (ret != null) {
            this.lastReturned = ret;
            this.nextToReturn = null;
            return ret;
         } else {
            ret = this.nodeIterator.findNext();
            if (ret != null) {
               this.lastReturned = ret;
               return ret;
            } else {
               throw new NoSuchElementException();
            }
         }
      }

      @Override
      public void remove() {
         ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> lastReturned = this.lastReturned;
         if (lastReturned == null) {
            throw new NoSuchElementException();
         }

         this.lastReturned = null;
         this.nodeIterator.map.remove(lastReturned.key);
      }
   }

   protected static class EntrySet<K, V> extends BaseMapSet<ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>> {
      protected final ConcurrentChainedReference2ObjectHashTable<K, V> map;

      protected EntrySet(final ConcurrentChainedReference2ObjectHashTable<K, V> map) {
         super();
         this.map = map;
      }

      @Override
      public int size() {
         return this.map.size();
      }

      @Override
      public boolean isEmpty() {
         return this.map.isEmpty();
      }

      @Override
      public boolean contains(final Object value) {
         if (value instanceof ConcurrentChainedReference2ObjectHashTable.TableEntry<?, ?> entry) {
            Object entryKey = entry.getKey();
            Object entryValue = entry.getValue();
            return entryValue == null ? false : this.map.contains((K)entryKey, (V)entryValue);
         } else {
            return false;
         }
      }

      @Override
      protected List<ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>> asList() {
         List<ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>> ret = new ArrayList<>(this.map.size());

         for (ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> element : this) {
            ret.add(element);
         }

         return ret;
      }

      @Override
      public BaseObjectIterator<ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>> iterator() {
         return this.map.entryIterator();
      }
   }

   protected static final class KeyIterator<K, V> extends BaseObjectIterator<K> {
      private final ConcurrentChainedReference2ObjectHashTable.EntryNodeIterator<K, V> iterator;

      public KeyIterator(final ConcurrentChainedReference2ObjectHashTable<K, V> map) {
         super();
         this.iterator = new ConcurrentChainedReference2ObjectHashTable.EntryNodeIterator<>(map);
      }

      @Override
      public boolean hasNext() {
         return this.iterator.hasNext();
      }

      @Override
      public void remove() {
         this.iterator.remove();
      }

      @Override
      public K next() {
         return this.iterator.next().key;
      }
   }

   protected static class Keys<K> extends BaseMapSet<K> {
      protected final ConcurrentChainedReference2ObjectHashTable<K, ?> map;

      protected Keys(final ConcurrentChainedReference2ObjectHashTable<K, ?> map) {
         super();
         this.map = map;
      }

      @Override
      public int size() {
         return this.map.size();
      }

      @Override
      public boolean isEmpty() {
         return this.map.isEmpty();
      }

      @Override
      public boolean contains(final Object value) {
         return this.map.containsKey((K)value);
      }

      @Override
      protected List<K> asList() {
         List<K> ret = new ArrayList<>(this.map.size());

         for (K element : this) {
            ret.add(element);
         }

         return ret;
      }

      @Override
      public BaseObjectIterator<K> iterator() {
         return this.map.keyIterator();
      }
   }

   protected static class NodeIterator<K, V> {
      protected final ConcurrentChainedReference2ObjectHashTable<K, V> map;
      private ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] currentTable;
      private ConcurrentChainedReference2ObjectHashTable.NodeIterator.ResizeChain<K, V> resizeChain;
      private ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> last;
      private int nextBin;
      private int increment;

      protected NodeIterator(final ConcurrentChainedReference2ObjectHashTable<K, V> map) {
         super();
         this.map = map;
         this.currentTable = map.table;
         this.increment = 1;
      }

      private ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] pullResizeChain(final int index) {
         ConcurrentChainedReference2ObjectHashTable.NodeIterator.ResizeChain<K, V> resizeChain = this.resizeChain;
         if (resizeChain == null) {
            this.currentTable = null;
            return null;
         }

         ConcurrentChainedReference2ObjectHashTable.NodeIterator.ResizeChain<K, V> prevChain = resizeChain.prev;
         this.resizeChain = prevChain;
         if (prevChain == null) {
            this.currentTable = null;
            return null;
         }

         ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] newTable = prevChain.table;
         int newIdx = index & newTable.length - 1;
         ConcurrentChainedReference2ObjectHashTable.NodeIterator.ResizeChain<K, V> nextPrevChain = prevChain.prev;
         int increment;
         if (nextPrevChain == null) {
            increment = 1;
         } else {
            increment = nextPrevChain.table.length;
         }

         newIdx += increment;
         this.increment = increment;
         this.nextBin = newIdx;
         this.currentTable = newTable;
         return newTable;
      }

      private ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] pushResizeChain(
         final ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] table
      ) {
         ConcurrentChainedReference2ObjectHashTable.NodeIterator.ResizeChain<K, V> chain = this.resizeChain;
         if (chain == null) {
            ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] nextTable = this.map.fetchNewTable(table);
            ConcurrentChainedReference2ObjectHashTable.NodeIterator.ResizeChain<K, V> oldChain = new ConcurrentChainedReference2ObjectHashTable.NodeIterator.ResizeChain<>(
               table, null, null
            );
            ConcurrentChainedReference2ObjectHashTable.NodeIterator.ResizeChain<K, V> currChain = new ConcurrentChainedReference2ObjectHashTable.NodeIterator.ResizeChain<>(
               nextTable, oldChain, null
            );
            oldChain.next = currChain;
            this.increment = table.length;
            this.resizeChain = currChain;
            this.currentTable = nextTable;
            return nextTable;
         } else {
            ConcurrentChainedReference2ObjectHashTable.NodeIterator.ResizeChain<K, V> currChain = chain.next;
            if (currChain == null) {
               ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] ret = this.map.fetchNewTable(table);
               currChain = new ConcurrentChainedReference2ObjectHashTable.NodeIterator.ResizeChain<>(ret, chain, null);
               chain.next = currChain;
               this.increment = table.length;
               this.resizeChain = currChain;
               this.currentTable = ret;
               return ret;
            } else {
               this.increment = table.length;
               this.resizeChain = currChain;
               return this.currentTable = currChain.table;
            }
         }
      }

      protected final ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> findNext() {
         label43:
         while (true) {
            ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> last = this.last;
            if (last != null) {
               ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> next = last.getNextVolatile();
               if (next != null) {
                  this.last = next;
                  if (next.getValuePlain() == null) {
                     continue;
                  }

                  return next;
               }
            }

            ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] table = this.currentTable;
            if (table == null) {
               return null;
            }

            int idx = this.nextBin;
            int increment = this.increment;

            do {
               while (idx < table.length) {
                  ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> entry = ConcurrentChainedReference2ObjectHashTable.getAtIndexAcquire(table, idx);
                  if (entry == null) {
                     idx += increment;
                  } else {
                     if (entry != ConcurrentChainedReference2ObjectHashTable.RESIZE_NODE) {
                        this.last = entry;
                        this.nextBin = idx + increment;
                        if (entry.getValuePlain() == null) {
                           continue label43;
                        }

                        return entry;
                     }

                     table = this.pushResizeChain(table);
                     increment = this.increment;
                  }
               }

               table = this.pullResizeChain(idx);
               idx = this.nextBin;
               increment = this.increment;
            } while (table != null);

            this.last = null;
            return null;
         }
      }

      private static final class ResizeChain<K, V> {
         public final ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] table;
         public final ConcurrentChainedReference2ObjectHashTable.NodeIterator.ResizeChain<K, V> prev;
         public ConcurrentChainedReference2ObjectHashTable.NodeIterator.ResizeChain<K, V> next;

         public ResizeChain(
            final ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V>[] table,
            final ConcurrentChainedReference2ObjectHashTable.NodeIterator.ResizeChain<K, V> prev,
            final ConcurrentChainedReference2ObjectHashTable.NodeIterator.ResizeChain<K, V> next
         ) {
            super();
            this.table = table;
            this.prev = prev;
            this.next = next;
         }
      }
   }

   @FunctionalInterface
   public interface RemoveIfPredicate<K, V> {
      boolean test(final V value);
   }

   public static final class TableEntry<K, V> {
      private static final VarHandle TABLE_ENTRY_ARRAY_HANDLE = ConcurrentUtil.getArrayHandle(ConcurrentChainedReference2ObjectHashTable.TableEntry[].class);
      private final K key;
      private volatile V value;
      private static final VarHandle VALUE_HANDLE = ConcurrentUtil.getVarHandle(
         ConcurrentChainedReference2ObjectHashTable.TableEntry.class, "value", Object.class
      );
      private volatile ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> next;
      private static final VarHandle NEXT_HANDLE = ConcurrentUtil.getVarHandle(
         ConcurrentChainedReference2ObjectHashTable.TableEntry.class, "next", ConcurrentChainedReference2ObjectHashTable.TableEntry.class
      );

      private V getValuePlain() {
         return (V)(Object)VALUE_HANDLE.get((ConcurrentChainedReference2ObjectHashTable.TableEntry)this);
      }

      private V getValueAcquire() {
         return (V)(Object)VALUE_HANDLE.getAcquire((ConcurrentChainedReference2ObjectHashTable.TableEntry)this);
      }

      private V getValueVolatile() {
         return (V)(Object)VALUE_HANDLE.getVolatile((ConcurrentChainedReference2ObjectHashTable.TableEntry)this);
      }

      private void setValuePlain(final V value) {
         VALUE_HANDLE.set((ConcurrentChainedReference2ObjectHashTable.TableEntry)this, (Object)value);
      }

      private void setValueRelease(final V value) {
         VALUE_HANDLE.setRelease((ConcurrentChainedReference2ObjectHashTable.TableEntry)this, (Object)value);
      }

      private void setValueVolatile(final V value) {
         VALUE_HANDLE.setVolatile((ConcurrentChainedReference2ObjectHashTable.TableEntry)this, (Object)value);
      }

      private ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> getNextPlain() {
         return (ConcurrentChainedReference2ObjectHashTable.TableEntry)NEXT_HANDLE.get((ConcurrentChainedReference2ObjectHashTable.TableEntry)this);
      }

      private ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> getNextAcquire() {
         return (ConcurrentChainedReference2ObjectHashTable.TableEntry)NEXT_HANDLE.getAcquire((ConcurrentChainedReference2ObjectHashTable.TableEntry)this);
      }

      private ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> getNextVolatile() {
         return (ConcurrentChainedReference2ObjectHashTable.TableEntry)NEXT_HANDLE.getVolatile((ConcurrentChainedReference2ObjectHashTable.TableEntry)this);
      }

      private void setNextPlain(final ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> next) {
         NEXT_HANDLE.set((ConcurrentChainedReference2ObjectHashTable.TableEntry)this, (ConcurrentChainedReference2ObjectHashTable.TableEntry)next);
      }

      private void setNextRelease(final ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> next) {
         NEXT_HANDLE.setRelease((ConcurrentChainedReference2ObjectHashTable.TableEntry)this, (ConcurrentChainedReference2ObjectHashTable.TableEntry)next);
      }

      private void setNextVolatile(final ConcurrentChainedReference2ObjectHashTable.TableEntry<K, V> next) {
         NEXT_HANDLE.setVolatile((ConcurrentChainedReference2ObjectHashTable.TableEntry)this, (ConcurrentChainedReference2ObjectHashTable.TableEntry)next);
      }

      protected TableEntry(final K key, final V value) {
         super();
         this.key = key;
         this.setValuePlain(value);
      }

      public K getKey() {
         return this.key;
      }

      public V getValue() {
         return this.getValueVolatile();
      }
   }

   protected static final class ValueIterator<K, V> extends BaseObjectIterator<V> {
      private final ConcurrentChainedReference2ObjectHashTable.EntryNodeIterator<K, V> iterator;

      public ValueIterator(final ConcurrentChainedReference2ObjectHashTable<K, V> map) {
         super();
         this.iterator = new ConcurrentChainedReference2ObjectHashTable.EntryNodeIterator<>(map);
      }

      @Override
      public boolean hasNext() {
         return this.iterator.hasNext();
      }

      @Override
      public void remove() {
         this.iterator.remove();
      }

      @Override
      public V next() {
         return this.iterator.next().getValueVolatile();
      }
   }

   protected static class Values<V> extends BaseMapCollection<V> {
      protected final ConcurrentChainedReference2ObjectHashTable<?, V> map;

      protected Values(final ConcurrentChainedReference2ObjectHashTable<?, V> map) {
         super();
         this.map = map;
      }

      @Override
      public int size() {
         return this.map.size();
      }

      @Override
      public boolean isEmpty() {
         return this.map.isEmpty();
      }

      @Override
      public boolean contains(final Object value) {
         return this.map.containsValue((V)value);
      }

      @Override
      protected List<V> asList() {
         List<V> ret = new ArrayList<>(this.map.size());

         for (V element : this) {
            ret.add(element);
         }

         return ret;
      }

      @Override
      public BaseObjectIterator<V> iterator() {
         return this.map.valueIterator();
      }
   }
}
