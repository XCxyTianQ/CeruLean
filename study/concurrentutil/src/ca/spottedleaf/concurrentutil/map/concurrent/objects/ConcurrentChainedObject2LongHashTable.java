package ca.spottedleaf.concurrentutil.map.concurrent.objects;

import ca.spottedleaf.common.util.HashUtil;
import ca.spottedleaf.common.util.IntegerUtil;
import ca.spottedleaf.concurrentutil.collection.iterator.BaseLongIterator;
import ca.spottedleaf.concurrentutil.collection.iterator.BaseObjectIterator;
import ca.spottedleaf.concurrentutil.map.BaseMapSet;
import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class ConcurrentChainedObject2LongHashTable<K> implements Iterable<ConcurrentChainedObject2LongHashTable.TableEntry<K>> {
   private static final ConcurrentChainedObject2LongHashTable.TableEntry<?> RESIZE_NODE = new ConcurrentChainedObject2LongHashTable.TableEntry(null, 0L);
   protected static final int DEFAULT_CAPACITY = 16;
   protected static final float DEFAULT_LOAD_FACTOR = 0.75F;
   protected static final int MAXIMUM_CAPACITY = 1073741824;
   protected final AtomicLong size = new AtomicLong();
   protected final float loadFactor;
   protected volatile ConcurrentChainedObject2LongHashTable.TableEntry<K>[] table;
   protected volatile ConcurrentChainedObject2LongHashTable.TableEntry<K>[] nextTable;
   protected static final int THRESHOLD_NO_RESIZE = -1;
   protected static final int THRESHOLD_RESIZING = -2;
   protected volatile int threshold;
   protected static final VarHandle THRESHOLD_HANDLE = ConcurrentUtil.getVarHandle(ConcurrentChainedObject2LongHashTable.class, "threshold", int.class);
   protected ConcurrentChainedObject2LongHashTable.Keys<K> keys;
   protected ConcurrentChainedObject2LongHashTable.EntrySet<K> entrySet;

   public static int defaultCapacity() {
      return 16;
   }

   public static float defaultLoadFactor() {
      return 0.75F;
   }

   protected final int getThresholdAcquire() {
      return (int)THRESHOLD_HANDLE.getAcquire((ConcurrentChainedObject2LongHashTable)this);
   }

   protected final int getThresholdVolatile() {
      return (int)THRESHOLD_HANDLE.getVolatile((ConcurrentChainedObject2LongHashTable)this);
   }

   protected final void setThresholdPlain(final int threshold) {
      THRESHOLD_HANDLE.set((ConcurrentChainedObject2LongHashTable)this, (int)threshold);
   }

   protected final void setThresholdRelease(final int threshold) {
      THRESHOLD_HANDLE.setRelease((ConcurrentChainedObject2LongHashTable)this, (int)threshold);
   }

   protected final void setThresholdVolatile(final int threshold) {
      THRESHOLD_HANDLE.setVolatile((ConcurrentChainedObject2LongHashTable)this, (int)threshold);
   }

   protected final int compareExchangeThresholdVolatile(final int expect, final int update) {
      return (int)THRESHOLD_HANDLE.compareAndExchange((ConcurrentChainedObject2LongHashTable)this, (int)expect, (int)update);
   }

   public ConcurrentChainedObject2LongHashTable() {
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

   protected ConcurrentChainedObject2LongHashTable(final int capacity, final float loadFactor) {
      super();
      int tableSize = getCapacityFor(capacity);
      if (!(loadFactor <= 0.0) && Float.isFinite(loadFactor)) {
         if (tableSize == 1073741824) {
            this.setThresholdPlain(-1);
         } else {
            this.setThresholdPlain(getTargetThreshold(tableSize, loadFactor));
         }

         this.loadFactor = loadFactor;
         this.nextTable = this.table = new ConcurrentChainedObject2LongHashTable.TableEntry[tableSize];
      } else {
         throw new IllegalArgumentException("Invalid load factor: " + loadFactor);
      }
   }

   public static <K> ConcurrentChainedObject2LongHashTable<K> createWithCapacity(final int capacity) {
      return createWithCapacity(capacity, 0.75F);
   }

   public static <K> ConcurrentChainedObject2LongHashTable<K> createWithCapacity(final int capacity, final float loadFactor) {
      return new ConcurrentChainedObject2LongHashTable<>(capacity, loadFactor);
   }

   public static <K> ConcurrentChainedObject2LongHashTable<K> createWithExpected(final int expected) {
      return createWithExpected(expected, 0.75F);
   }

   public static <K> ConcurrentChainedObject2LongHashTable<K> createWithExpected(final int expected, final float loadFactor) {
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
      return HashUtil.mix(key.hashCode());
   }

   public final float getLoadFactor() {
      return this.loadFactor;
   }

   protected static <K> ConcurrentChainedObject2LongHashTable.TableEntry<K> getAtIndexAcquire(
      final ConcurrentChainedObject2LongHashTable.TableEntry<K>[] table, final int index
   ) {
      return (ConcurrentChainedObject2LongHashTable.TableEntry)ConcurrentChainedObject2LongHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .getAcquire((ConcurrentChainedObject2LongHashTable.TableEntry[])table, (int)index);
   }

   protected static <K> void setAtIndexRelease(
      final ConcurrentChainedObject2LongHashTable.TableEntry<K>[] table, final int index, final ConcurrentChainedObject2LongHashTable.TableEntry<K> value
   ) {
      ConcurrentChainedObject2LongHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .setRelease((ConcurrentChainedObject2LongHashTable.TableEntry[])table, (int)index, (ConcurrentChainedObject2LongHashTable.TableEntry)value);
   }

   protected static <K> void setAtIndexVolatile(
      final ConcurrentChainedObject2LongHashTable.TableEntry<K>[] table, final int index, final ConcurrentChainedObject2LongHashTable.TableEntry<K> value
   ) {
      ConcurrentChainedObject2LongHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .setVolatile((ConcurrentChainedObject2LongHashTable.TableEntry[])table, (int)index, (ConcurrentChainedObject2LongHashTable.TableEntry)value);
   }

   protected static <K> ConcurrentChainedObject2LongHashTable.TableEntry<K> compareAndExchangeAtIndexVolatile(
      final ConcurrentChainedObject2LongHashTable.TableEntry<K>[] table,
      final int index,
      final ConcurrentChainedObject2LongHashTable.TableEntry<K> expect,
      final ConcurrentChainedObject2LongHashTable.TableEntry<K> update
   ) {
      return (ConcurrentChainedObject2LongHashTable.TableEntry)ConcurrentChainedObject2LongHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .compareAndExchange(
            (ConcurrentChainedObject2LongHashTable.TableEntry[])table,
            (int)index,
            (ConcurrentChainedObject2LongHashTable.TableEntry)expect,
            (ConcurrentChainedObject2LongHashTable.TableEntry)update
         );
   }

   protected ConcurrentChainedObject2LongHashTable.TableEntry<K>[] fetchNewTable(final ConcurrentChainedObject2LongHashTable.TableEntry<K>[] expectedCurr) {
      ConcurrentChainedObject2LongHashTable.TableEntry<K>[] candidate = this.nextTable;
      ConcurrentChainedObject2LongHashTable.TableEntry<K>[] current = this.table;
      return expectedCurr == current ? candidate : current;
   }

   protected final ConcurrentChainedObject2LongHashTable.TableEntry<K> getNode(final K key) {
      int hash = HashUtil.mix(key.hashCode());
      ConcurrentChainedObject2LongHashTable.TableEntry<K>[] table = this.table;

      while (true) {
         ConcurrentChainedObject2LongHashTable.TableEntry<K> node = getAtIndexAcquire(table, hash & table.length - 1);
         if (node == null) {
            return node;
         }

         if (node != RESIZE_NODE) {
            while (node.key != key && !node.key.equals(key)) {
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

   public long get(final K key) {
      Objects.requireNonNull(key, "Key may not be null");
      ConcurrentChainedObject2LongHashTable.TableEntry<K> node = this.getNode(key);
      return node == null ? 0L : node.getValueVolatile();
   }

   public long getOrDefault(final K key, final long defaultValue) {
      Objects.requireNonNull(key, "Key may not be null");
      ConcurrentChainedObject2LongHashTable.TableEntry<K> node = this.getNode(key);
      return node == null ? defaultValue : node.getValueVolatile();
   }

   public boolean containsKey(final K key) {
      Objects.requireNonNull(key, "Key may not be null");
      return this.getNode(key) != null;
   }

   public boolean containsValue(final long value) {
      ConcurrentChainedObject2LongHashTable.NodeIterator<K> iterator = new ConcurrentChainedObject2LongHashTable.NodeIterator<>(this);

      ConcurrentChainedObject2LongHashTable.TableEntry<K> node;
      while ((node = iterator.findNext()) != null) {
         long nodeValue = node.getValueAcquire();
         if (value == nodeValue) {
            return true;
         }
      }

      return false;
   }

   public boolean contains(final K key, final long value) {
      Objects.requireNonNull(key, "Key may not be null");
      ConcurrentChainedObject2LongHashTable.TableEntry<K> node = this.getNode(key);
      if (node == null) {
         return false;
      }

      long nodeValue = node.getValueAcquire();
      return value == nodeValue;
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

      ConcurrentChainedObject2LongHashTable.TableEntry<K>[] newTable = new ConcurrentChainedObject2LongHashTable.TableEntry[capacity];
      ConcurrentChainedObject2LongHashTable.TableEntry<K>[] oldTable = this.table;
      this.nextTable = newTable;
      int capOldShift = IntegerUtil.floorLog2(oldTable.length);
      int capDiffShift = IntegerUtil.floorLog2(capacity) - capOldShift;
      if (capDiffShift == 0) {
         throw new IllegalStateException("Resizing to same size");
      }

      ConcurrentChainedObject2LongHashTable.TableEntry<K>[] work = new ConcurrentChainedObject2LongHashTable.TableEntry[1 << capDiffShift];
      int i = 0;

      for (int len = oldTable.length; i < len; i++) {
         ConcurrentChainedObject2LongHashTable.TableEntry<K> binNode = getAtIndexAcquire(oldTable, i);

         while (
            binNode != null
               || null != (binNode = compareAndExchangeAtIndexVolatile(oldTable, i, null, (ConcurrentChainedObject2LongHashTable.TableEntry<K>)RESIZE_NODE))
         ) {
            synchronized (binNode) {
               if (binNode == (binNode = getAtIndexAcquire(oldTable, i))) {
                  ConcurrentChainedObject2LongHashTable.TableEntry<K> next = binNode.getNextPlain();
                  if (next == null) {
                     newTable[HashUtil.mix(binNode.key.hashCode()) & capacity - 1] = binNode;
                  } else {
                     Arrays.fill(work, null);

                     for (ConcurrentChainedObject2LongHashTable.TableEntry<K> curr = binNode; curr != null; curr = curr.getNextPlain()) {
                        int newTableIdx = HashUtil.mix(curr.key.hashCode()) & capacity - 1;
                        int workIdx = newTableIdx >>> capOldShift;
                        ConcurrentChainedObject2LongHashTable.TableEntry<K> replace = new ConcurrentChainedObject2LongHashTable.TableEntry<>(
                           curr.key, curr.getValuePlain()
                        );
                        ConcurrentChainedObject2LongHashTable.TableEntry<K> workNode = work[workIdx];
                        work[workIdx] = replace;
                        if (workNode == null) {
                           newTable[newTableIdx] = replace;
                        } else {
                           workNode.setNextPlain(replace);
                        }
                     }
                  }

                  setAtIndexRelease(oldTable, i, (ConcurrentChainedObject2LongHashTable.TableEntry<K>)RESIZE_NODE);
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

   public long put(final K key, final long value) {
      Objects.requireNonNull(key, "Key may not be null");
      int hash = HashUtil.mix(key.hashCode());
      ConcurrentChainedObject2LongHashTable.TableEntry<K>[] table = this.table;

      label55:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedObject2LongHashTable.TableEntry<K> node = getAtIndexAcquire(table, index);

         while (
            node != null
               || null != (node = compareAndExchangeAtIndexVolatile(table, index, null, new ConcurrentChainedObject2LongHashTable.TableEntry<>(key, value)))
         ) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label55;
            }

            synchronized (node) {
               if (node != (node = getAtIndexAcquire(table, index))) {
                  continue;
               }

               ConcurrentChainedObject2LongHashTable.TableEntry<K> prev = null;

               while (node != null) {
                  if (node.key == key || node.key.equals(key)) {
                     long ret = node.getValuePlain();
                     node.setValueVolatile(value);
                     return ret;
                  }

                  prev = node;
                  node = node.getNextPlain();
               }

               prev.setNextRelease(new ConcurrentChainedObject2LongHashTable.TableEntry<>(key, value));
            }

            this.addSize(1L);
            return 0L;
         }

         this.addSize(1L);
         return 0L;
      }
   }

   public long putIfAbsent(final K key, final long value) {
      Objects.requireNonNull(key, "Key may not be null");
      int hash = HashUtil.mix(key.hashCode());
      ConcurrentChainedObject2LongHashTable.TableEntry<K>[] table = this.table;

      label61:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedObject2LongHashTable.TableEntry<K> node = getAtIndexAcquire(table, index);

         while (
            node != null
               || null != (node = compareAndExchangeAtIndexVolatile(table, index, null, new ConcurrentChainedObject2LongHashTable.TableEntry<>(key, value)))
         ) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label61;
            }

            if (node.key != key && !node.key.equals(key)) {
               synchronized (node) {
                  if (node != (node = getAtIndexAcquire(table, index))) {
                     continue;
                  }

                  ConcurrentChainedObject2LongHashTable.TableEntry<K> prev = null;

                  while (node != null) {
                     if (node.key == key || node.key.equals(key)) {
                        return node.getValuePlain();
                     }

                     prev = node;
                     node = node.getNextPlain();
                  }

                  prev.setNextRelease(new ConcurrentChainedObject2LongHashTable.TableEntry<>(key, value));
               }

               this.addSize(1L);
               return 0L;
            }

            return node.getValueVolatile();
         }

         this.addSize(1L);
         return 0L;
      }
   }

   public long replace(final K key, final long value) {
      Objects.requireNonNull(key, "Key may not be null");
      int hash = HashUtil.mix(key.hashCode());
      ConcurrentChainedObject2LongHashTable.TableEntry<K>[] table = this.table;

      label50:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedObject2LongHashTable.TableEntry<K> node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label50;
            }

            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  while (node != null) {
                     if (node.key == key || node.key.equals(key)) {
                        long ret = node.getValuePlain();
                        node.setValueVolatile(value);
                        return ret;
                     }

                     node = node.getNextPlain();
                  }

                  return 0L;
               }
            }
         }

         return 0L;
      }
   }

   public long replace(final K key, final long expect, final long update) {
      int hash = HashUtil.mix(key.hashCode());
      ConcurrentChainedObject2LongHashTable.TableEntry<K>[] table = this.table;

      label56:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedObject2LongHashTable.TableEntry<K> node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label56;
            }

            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  while (node != null) {
                     if (node.key == key || node.key.equals(key)) {
                        long ret = node.getValuePlain();
                        if (expect != ret) {
                           return ret;
                        }

                        node.setValueVolatile(update);
                        return ret;
                     }

                     node = node.getNextPlain();
                  }

                  return 0L;
               }
            }
         }

         return 0L;
      }
   }

   public long remove(final K key) {
      Objects.requireNonNull(key, "Key may not be null");
      int hash = HashUtil.mix(key.hashCode());
      ConcurrentChainedObject2LongHashTable.TableEntry<K>[] table = this.table;

      boolean removed;
      long ret;
      label55:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedObject2LongHashTable.TableEntry<K> node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label55;
            }

            removed = false;
            ret = 0L;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedObject2LongHashTable.TableEntry<K> prev = null;

                  while (true) {
                     if (node == null) {
                        break label55;
                     }

                     if (node.key == key || node.key.equals(key)) {
                        ret = node.getValuePlain();
                        removed = true;
                        if (prev == null) {
                           setAtIndexRelease(table, index, node.getNextPlain());
                        } else {
                           prev.setNextRelease(node.getNextPlain());
                        }
                        break label55;
                     }

                     prev = node;
                     node = node.getNextPlain();
                  }
               }
            }
         }

         return 0L;
      }

      if (removed) {
         this.subSize(1L);
      }

      return ret;
   }

   public long remove(final K key, final long expect) {
      Objects.requireNonNull(key, "Key may not be null");
      int hash = HashUtil.mix(key.hashCode());
      ConcurrentChainedObject2LongHashTable.TableEntry<K>[] table = this.table;

      boolean removed;
      long ret;
      label57:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedObject2LongHashTable.TableEntry<K> node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label57;
            }

            removed = false;
            ret = 0L;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedObject2LongHashTable.TableEntry<K> prev = null;

                  while (true) {
                     if (node == null) {
                        break label57;
                     }

                     if (node.key == key || node.key.equals(key)) {
                        ret = node.getValuePlain();
                        if (expect == ret) {
                           removed = true;
                           if (prev == null) {
                              setAtIndexRelease(table, index, node.getNextPlain());
                           } else {
                              prev.setNextRelease(node.getNextPlain());
                           }
                        }
                        break label57;
                     }

                     prev = node;
                     node = node.getNextPlain();
                  }
               }
            }
         }

         return 0L;
      }

      if (removed) {
         this.subSize(1L);
      }

      return ret;
   }

   public long removeIf(final K key, final ConcurrentChainedObject2LongHashTable.RemoveIfPredicate<K> predicate) {
      Objects.requireNonNull(key, "Key may not be null");
      Objects.requireNonNull(predicate, "Predicate may not be null");
      int hash = HashUtil.mix(key.hashCode());
      ConcurrentChainedObject2LongHashTable.TableEntry<K>[] table = this.table;

      boolean removed;
      long ret;
      label57:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedObject2LongHashTable.TableEntry<K> node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label57;
            }

            removed = false;
            ret = 0L;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedObject2LongHashTable.TableEntry<K> prev = null;

                  while (true) {
                     if (node == null) {
                        break label57;
                     }

                     if (node.key == key || node.key.equals(key)) {
                        ret = node.getValuePlain();
                        if (predicate.test(ret)) {
                           removed = true;
                           if (prev == null) {
                              setAtIndexRelease(table, index, node.getNextPlain());
                           } else {
                              prev.setNextRelease(node.getNextPlain());
                           }
                        }
                        break label57;
                     }

                     prev = node;
                     node = node.getNextPlain();
                  }
               }
            }
         }

         return 0L;
      }

      if (removed) {
         this.subSize(1L);
      }

      return ret;
   }

   public long addTo(final K key, final long increment, final long defaultValue) {
      Objects.requireNonNull(key, "Key may not be null");
      int hash = HashUtil.mix(key.hashCode());
      ConcurrentChainedObject2LongHashTable.TableEntry<K>[] table = this.table;

      label50:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedObject2LongHashTable.TableEntry<K> node = getAtIndexAcquire(table, index);

         while (
            node != null
               || null
                  != (node = compareAndExchangeAtIndexVolatile(table, index, null, new ConcurrentChainedObject2LongHashTable.TableEntry<>(key, defaultValue)))
         ) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label50;
            }

            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedObject2LongHashTable.TableEntry<K> prev = null;

                  while (node != null) {
                     if (node.key == key) {
                        long ret = node.getValuePlain() + increment;
                        node.setValueVolatile(ret);
                        return ret;
                     }

                     prev = node;
                     node = node.getNextPlain();
                  }

                  prev.setNextRelease(new ConcurrentChainedObject2LongHashTable.TableEntry<>(key, defaultValue));
                  break label50;
               }
            }
         }

         this.addSize(1L);
         return defaultValue;
      }

      this.addSize(1L);
      return defaultValue;
   }

   public long decFrom(final K key, final long decrement, final long threshold) {
      Objects.requireNonNull(key, "Key may not be null");
      int hash = HashUtil.mix(key.hashCode());
      ConcurrentChainedObject2LongHashTable.TableEntry<K>[] table = this.table;

      boolean removed;
      boolean found;
      long ret;
      label60:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedObject2LongHashTable.TableEntry<K> node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label60;
            }

            removed = false;
            found = false;
            ret = 0L;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedObject2LongHashTable.TableEntry<K> prev = null;

                  while (true) {
                     if (node == null) {
                        break label60;
                     }

                     if (node.key == key) {
                        found = true;
                        ret = node.getValuePlain() - decrement;
                        if (ret <= threshold) {
                           removed = true;
                           if (prev == null) {
                              setAtIndexRelease(table, index, node.getNextPlain());
                           } else {
                              prev.setNextRelease(node.getNextPlain());
                           }
                        } else {
                           node.setValueRelease(ret);
                        }
                        break label60;
                     }

                     prev = node;
                     node = node.getNextPlain();
                  }
               }
            }
         }

         throw new IllegalStateException();
      }

      if (!found) {
         throw new IllegalStateException();
      }

      if (removed) {
         this.subSize(1L);
      }

      return ret;
   }

   public void clear() {
      ConcurrentChainedObject2LongHashTable.NodeIterator<K> nodeIterator = new ConcurrentChainedObject2LongHashTable.NodeIterator<>(this);

      ConcurrentChainedObject2LongHashTable.TableEntry<K> node;
      while ((node = nodeIterator.findNext()) != null) {
         this.remove(node.key);
      }
   }

   public BaseObjectIterator<ConcurrentChainedObject2LongHashTable.TableEntry<K>> entryIterator() {
      return new ConcurrentChainedObject2LongHashTable.EntryNodeIterator<>(this);
   }

   public final BaseObjectIterator<ConcurrentChainedObject2LongHashTable.TableEntry<K>> iterator() {
      return this.entryIterator();
   }

   public BaseObjectIterator<K> keyIterator() {
      return new ConcurrentChainedObject2LongHashTable.KeyIterator<>(this);
   }

   public BaseLongIterator valueIterator() {
      return new ConcurrentChainedObject2LongHashTable.ValueIterator<>(this);
   }

   public Set<K> keys() {
      ConcurrentChainedObject2LongHashTable.Keys<K> keys = this.keys;
      return keys != null ? keys : (this.keys = new ConcurrentChainedObject2LongHashTable.Keys<>(this));
   }

   public Set<ConcurrentChainedObject2LongHashTable.TableEntry<K>> entrySet() {
      ConcurrentChainedObject2LongHashTable.EntrySet<K> entrySet = this.entrySet;
      return entrySet != null ? entrySet : (this.entrySet = new ConcurrentChainedObject2LongHashTable.EntrySet<>(this));
   }

   protected static final class EntryNodeIterator<K> extends BaseObjectIterator<ConcurrentChainedObject2LongHashTable.TableEntry<K>> {
      protected ConcurrentChainedObject2LongHashTable.TableEntry<K> lastReturned;
      protected ConcurrentChainedObject2LongHashTable.TableEntry<K> nextToReturn;
      protected final ConcurrentChainedObject2LongHashTable.NodeIterator<K> nodeIterator;

      protected EntryNodeIterator(final ConcurrentChainedObject2LongHashTable<K> map) {
         super();
         this.nodeIterator = new ConcurrentChainedObject2LongHashTable.NodeIterator<>(map);
      }

      @Override
      public boolean hasNext() {
         return this.nextToReturn != null ? true : (this.nextToReturn = this.nodeIterator.findNext()) != null;
      }

      public ConcurrentChainedObject2LongHashTable.TableEntry<K> next() throws NoSuchElementException {
         ConcurrentChainedObject2LongHashTable.TableEntry<K> ret = this.nextToReturn;
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
         ConcurrentChainedObject2LongHashTable.TableEntry<K> lastReturned = this.lastReturned;
         if (lastReturned == null) {
            throw new NoSuchElementException();
         }

         this.lastReturned = null;
         this.nodeIterator.map.remove(lastReturned.key);
      }
   }

   protected static class EntrySet<K> extends BaseMapSet<ConcurrentChainedObject2LongHashTable.TableEntry<K>> {
      protected final ConcurrentChainedObject2LongHashTable<K> map;

      protected EntrySet(final ConcurrentChainedObject2LongHashTable<K> map) {
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
         if (value instanceof ConcurrentChainedObject2LongHashTable.TableEntry<?> entry) {
            Object entryKey = entry.getKey();
            long entryValue = entry.getValue();
            return this.map.contains((K)entryKey, entryValue);
         } else {
            return false;
         }
      }

      @Override
      protected List<ConcurrentChainedObject2LongHashTable.TableEntry<K>> asList() {
         List<ConcurrentChainedObject2LongHashTable.TableEntry<K>> ret = new ArrayList<>(this.map.size());

         for (ConcurrentChainedObject2LongHashTable.TableEntry<K> element : this) {
            ret.add(element);
         }

         return ret;
      }

      @Override
      public BaseObjectIterator<ConcurrentChainedObject2LongHashTable.TableEntry<K>> iterator() {
         return this.map.entryIterator();
      }
   }

   protected static final class KeyIterator<K> extends BaseObjectIterator<K> {
      private final ConcurrentChainedObject2LongHashTable.EntryNodeIterator<K> iterator;

      public KeyIterator(final ConcurrentChainedObject2LongHashTable<K> map) {
         super();
         this.iterator = new ConcurrentChainedObject2LongHashTable.EntryNodeIterator<>(map);
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
      protected final ConcurrentChainedObject2LongHashTable<K> map;

      protected Keys(final ConcurrentChainedObject2LongHashTable<K> map) {
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

   protected static class NodeIterator<K> {
      protected final ConcurrentChainedObject2LongHashTable<K> map;
      private ConcurrentChainedObject2LongHashTable.TableEntry<K>[] currentTable;
      private ConcurrentChainedObject2LongHashTable.NodeIterator.ResizeChain<K> resizeChain;
      private ConcurrentChainedObject2LongHashTable.TableEntry<K> last;
      private int nextBin;
      private int increment;

      protected NodeIterator(final ConcurrentChainedObject2LongHashTable<K> map) {
         super();
         this.map = map;
         this.currentTable = map.table;
         this.increment = 1;
      }

      private ConcurrentChainedObject2LongHashTable.TableEntry<K>[] pullResizeChain(final int index) {
         ConcurrentChainedObject2LongHashTable.NodeIterator.ResizeChain<K> resizeChain = this.resizeChain;
         if (resizeChain == null) {
            this.currentTable = null;
            return null;
         }

         ConcurrentChainedObject2LongHashTable.NodeIterator.ResizeChain<K> prevChain = resizeChain.prev;
         this.resizeChain = prevChain;
         if (prevChain == null) {
            this.currentTable = null;
            return null;
         }

         ConcurrentChainedObject2LongHashTable.TableEntry<K>[] newTable = prevChain.table;
         int newIdx = index & newTable.length - 1;
         ConcurrentChainedObject2LongHashTable.NodeIterator.ResizeChain<K> nextPrevChain = prevChain.prev;
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

      private ConcurrentChainedObject2LongHashTable.TableEntry<K>[] pushResizeChain(final ConcurrentChainedObject2LongHashTable.TableEntry<K>[] table) {
         ConcurrentChainedObject2LongHashTable.NodeIterator.ResizeChain<K> chain = this.resizeChain;
         if (chain == null) {
            ConcurrentChainedObject2LongHashTable.TableEntry<K>[] nextTable = this.map.fetchNewTable(table);
            ConcurrentChainedObject2LongHashTable.NodeIterator.ResizeChain<K> oldChain = new ConcurrentChainedObject2LongHashTable.NodeIterator.ResizeChain<>(
               table, null, null
            );
            ConcurrentChainedObject2LongHashTable.NodeIterator.ResizeChain<K> currChain = new ConcurrentChainedObject2LongHashTable.NodeIterator.ResizeChain<>(
               nextTable, oldChain, null
            );
            oldChain.next = currChain;
            this.increment = table.length;
            this.resizeChain = currChain;
            this.currentTable = nextTable;
            return nextTable;
         } else {
            ConcurrentChainedObject2LongHashTable.NodeIterator.ResizeChain<K> currChain = chain.next;
            if (currChain == null) {
               ConcurrentChainedObject2LongHashTable.TableEntry<K>[] ret = this.map.fetchNewTable(table);
               currChain = new ConcurrentChainedObject2LongHashTable.NodeIterator.ResizeChain<>(ret, chain, null);
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

      protected final ConcurrentChainedObject2LongHashTable.TableEntry<K> findNext() {
         ConcurrentChainedObject2LongHashTable.TableEntry<K> last = this.last;
         if (last != null) {
            ConcurrentChainedObject2LongHashTable.TableEntry<K> next = last.getNextVolatile();
            if (next != null) {
               this.last = next;
               return next;
            }
         }

         ConcurrentChainedObject2LongHashTable.TableEntry<K>[] table = this.currentTable;
         if (table == null) {
            return null;
         }

         int idx = this.nextBin;
         int increment = this.increment;

         do {
            while (idx < table.length) {
               ConcurrentChainedObject2LongHashTable.TableEntry<K> entry = ConcurrentChainedObject2LongHashTable.getAtIndexAcquire(table, idx);
               if (entry == null) {
                  idx += increment;
               } else {
                  if (entry != ConcurrentChainedObject2LongHashTable.RESIZE_NODE) {
                     this.last = entry;
                     this.nextBin = idx + increment;
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

      private static final class ResizeChain<K> {
         public final ConcurrentChainedObject2LongHashTable.TableEntry<K>[] table;
         public final ConcurrentChainedObject2LongHashTable.NodeIterator.ResizeChain<K> prev;
         public ConcurrentChainedObject2LongHashTable.NodeIterator.ResizeChain<K> next;

         public ResizeChain(
            final ConcurrentChainedObject2LongHashTable.TableEntry<K>[] table,
            final ConcurrentChainedObject2LongHashTable.NodeIterator.ResizeChain<K> prev,
            final ConcurrentChainedObject2LongHashTable.NodeIterator.ResizeChain<K> next
         ) {
            super();
            this.table = table;
            this.prev = prev;
            this.next = next;
         }
      }
   }

   @FunctionalInterface
   public interface RemoveIfPredicate<K> {
      boolean test(final long value);
   }

   public static final class TableEntry<K> {
      private static final VarHandle TABLE_ENTRY_ARRAY_HANDLE = ConcurrentUtil.getArrayHandle(ConcurrentChainedObject2LongHashTable.TableEntry[].class);
      private final K key;
      private volatile long value;
      private static final VarHandle VALUE_HANDLE = ConcurrentUtil.getVarHandle(ConcurrentChainedObject2LongHashTable.TableEntry.class, "value", long.class);
      private volatile ConcurrentChainedObject2LongHashTable.TableEntry<K> next;
      private static final VarHandle NEXT_HANDLE = ConcurrentUtil.getVarHandle(
         ConcurrentChainedObject2LongHashTable.TableEntry.class, "next", ConcurrentChainedObject2LongHashTable.TableEntry.class
      );

      private long getValuePlain() {
         return (long)VALUE_HANDLE.get((ConcurrentChainedObject2LongHashTable.TableEntry)this);
      }

      private long getValueAcquire() {
         return (long)VALUE_HANDLE.getAcquire((ConcurrentChainedObject2LongHashTable.TableEntry)this);
      }

      private long getValueVolatile() {
         return (long)VALUE_HANDLE.getVolatile((ConcurrentChainedObject2LongHashTable.TableEntry)this);
      }

      private void setValuePlain(final long value) {
         VALUE_HANDLE.set((ConcurrentChainedObject2LongHashTable.TableEntry)this, (long)value);
      }

      private void setValueRelease(final long value) {
         VALUE_HANDLE.setRelease((ConcurrentChainedObject2LongHashTable.TableEntry)this, (long)value);
      }

      private void setValueVolatile(final long value) {
         VALUE_HANDLE.setVolatile((ConcurrentChainedObject2LongHashTable.TableEntry)this, (long)value);
      }

      private ConcurrentChainedObject2LongHashTable.TableEntry<K> getNextPlain() {
         return (ConcurrentChainedObject2LongHashTable.TableEntry)NEXT_HANDLE.get((ConcurrentChainedObject2LongHashTable.TableEntry)this);
      }

      private ConcurrentChainedObject2LongHashTable.TableEntry<K> getNextAcquire() {
         return (ConcurrentChainedObject2LongHashTable.TableEntry)NEXT_HANDLE.getAcquire((ConcurrentChainedObject2LongHashTable.TableEntry)this);
      }

      private ConcurrentChainedObject2LongHashTable.TableEntry<K> getNextVolatile() {
         return (ConcurrentChainedObject2LongHashTable.TableEntry)NEXT_HANDLE.getVolatile((ConcurrentChainedObject2LongHashTable.TableEntry)this);
      }

      private void setNextPlain(final ConcurrentChainedObject2LongHashTable.TableEntry<K> next) {
         NEXT_HANDLE.set((ConcurrentChainedObject2LongHashTable.TableEntry)this, (ConcurrentChainedObject2LongHashTable.TableEntry)next);
      }

      private void setNextRelease(final ConcurrentChainedObject2LongHashTable.TableEntry<K> next) {
         NEXT_HANDLE.setRelease((ConcurrentChainedObject2LongHashTable.TableEntry)this, (ConcurrentChainedObject2LongHashTable.TableEntry)next);
      }

      private void setNextVolatile(final ConcurrentChainedObject2LongHashTable.TableEntry<K> next) {
         NEXT_HANDLE.setVolatile((ConcurrentChainedObject2LongHashTable.TableEntry)this, (ConcurrentChainedObject2LongHashTable.TableEntry)next);
      }

      protected TableEntry(final K key, final long value) {
         super();
         this.key = key;
         this.setValuePlain(value);
      }

      public K getKey() {
         return this.key;
      }

      public long getValue() {
         return this.getValueVolatile();
      }
   }

   protected static final class ValueIterator<K> extends BaseLongIterator {
      private final ConcurrentChainedObject2LongHashTable.EntryNodeIterator<K> iterator;

      public ValueIterator(final ConcurrentChainedObject2LongHashTable<K> map) {
         super();
         this.iterator = new ConcurrentChainedObject2LongHashTable.EntryNodeIterator<>(map);
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
      public long nextLong() {
         return this.iterator.next().getValueVolatile();
      }
   }
}
