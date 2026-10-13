package ca.spottedleaf.concurrentutil.map.concurrent.objects;

import ca.spottedleaf.common.util.HashUtil;
import ca.spottedleaf.common.util.IntegerUtil;
import ca.spottedleaf.concurrentutil.collection.iterator.BaseIntIterator;
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

public class ConcurrentChainedReference2IntHashTable<K> implements Iterable<ConcurrentChainedReference2IntHashTable.TableEntry<K>> {
   private static final ConcurrentChainedReference2IntHashTable.TableEntry<?> RESIZE_NODE = new ConcurrentChainedReference2IntHashTable.TableEntry(null, 0);
   protected static final int DEFAULT_CAPACITY = 16;
   protected static final float DEFAULT_LOAD_FACTOR = 0.75F;
   protected static final int MAXIMUM_CAPACITY = 1073741824;
   protected final AtomicLong size = new AtomicLong();
   protected final float loadFactor;
   protected volatile ConcurrentChainedReference2IntHashTable.TableEntry<K>[] table;
   protected volatile ConcurrentChainedReference2IntHashTable.TableEntry<K>[] nextTable;
   protected static final int THRESHOLD_NO_RESIZE = -1;
   protected static final int THRESHOLD_RESIZING = -2;
   protected volatile int threshold;
   protected static final VarHandle THRESHOLD_HANDLE = ConcurrentUtil.getVarHandle(ConcurrentChainedReference2IntHashTable.class, "threshold", int.class);
   protected ConcurrentChainedReference2IntHashTable.Keys<K> keys;
   protected ConcurrentChainedReference2IntHashTable.EntrySet<K> entrySet;

   public static int defaultCapacity() {
      return 16;
   }

   public static float defaultLoadFactor() {
      return 0.75F;
   }

   protected final int getThresholdAcquire() {
      return (int)THRESHOLD_HANDLE.getAcquire((ConcurrentChainedReference2IntHashTable)this);
   }

   protected final int getThresholdVolatile() {
      return (int)THRESHOLD_HANDLE.getVolatile((ConcurrentChainedReference2IntHashTable)this);
   }

   protected final void setThresholdPlain(final int threshold) {
      THRESHOLD_HANDLE.set((ConcurrentChainedReference2IntHashTable)this, (int)threshold);
   }

   protected final void setThresholdRelease(final int threshold) {
      THRESHOLD_HANDLE.setRelease((ConcurrentChainedReference2IntHashTable)this, (int)threshold);
   }

   protected final void setThresholdVolatile(final int threshold) {
      THRESHOLD_HANDLE.setVolatile((ConcurrentChainedReference2IntHashTable)this, (int)threshold);
   }

   protected final int compareExchangeThresholdVolatile(final int expect, final int update) {
      return (int)THRESHOLD_HANDLE.compareAndExchange((ConcurrentChainedReference2IntHashTable)this, (int)expect, (int)update);
   }

   public ConcurrentChainedReference2IntHashTable() {
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

   protected ConcurrentChainedReference2IntHashTable(final int capacity, final float loadFactor) {
      super();
      int tableSize = getCapacityFor(capacity);
      if (!(loadFactor <= 0.0) && Float.isFinite(loadFactor)) {
         if (tableSize == 1073741824) {
            this.setThresholdPlain(-1);
         } else {
            this.setThresholdPlain(getTargetThreshold(tableSize, loadFactor));
         }

         this.loadFactor = loadFactor;
         this.nextTable = this.table = new ConcurrentChainedReference2IntHashTable.TableEntry[tableSize];
      } else {
         throw new IllegalArgumentException("Invalid load factor: " + loadFactor);
      }
   }

   public static <K> ConcurrentChainedReference2IntHashTable<K> createWithCapacity(final int capacity) {
      return createWithCapacity(capacity, 0.75F);
   }

   public static <K> ConcurrentChainedReference2IntHashTable<K> createWithCapacity(final int capacity, final float loadFactor) {
      return new ConcurrentChainedReference2IntHashTable<>(capacity, loadFactor);
   }

   public static <K> ConcurrentChainedReference2IntHashTable<K> createWithExpected(final int expected) {
      return createWithExpected(expected, 0.75F);
   }

   public static <K> ConcurrentChainedReference2IntHashTable<K> createWithExpected(final int expected, final float loadFactor) {
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

   protected static <K> ConcurrentChainedReference2IntHashTable.TableEntry<K> getAtIndexAcquire(
      final ConcurrentChainedReference2IntHashTable.TableEntry<K>[] table, final int index
   ) {
      return (ConcurrentChainedReference2IntHashTable.TableEntry)ConcurrentChainedReference2IntHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .getAcquire((ConcurrentChainedReference2IntHashTable.TableEntry[])table, (int)index);
   }

   protected static <K> void setAtIndexRelease(
      final ConcurrentChainedReference2IntHashTable.TableEntry<K>[] table, final int index, final ConcurrentChainedReference2IntHashTable.TableEntry<K> value
   ) {
      ConcurrentChainedReference2IntHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .setRelease((ConcurrentChainedReference2IntHashTable.TableEntry[])table, (int)index, (ConcurrentChainedReference2IntHashTable.TableEntry)value);
   }

   protected static <K> void setAtIndexVolatile(
      final ConcurrentChainedReference2IntHashTable.TableEntry<K>[] table, final int index, final ConcurrentChainedReference2IntHashTable.TableEntry<K> value
   ) {
      ConcurrentChainedReference2IntHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .setVolatile((ConcurrentChainedReference2IntHashTable.TableEntry[])table, (int)index, (ConcurrentChainedReference2IntHashTable.TableEntry)value);
   }

   protected static <K> ConcurrentChainedReference2IntHashTable.TableEntry<K> compareAndExchangeAtIndexVolatile(
      final ConcurrentChainedReference2IntHashTable.TableEntry<K>[] table,
      final int index,
      final ConcurrentChainedReference2IntHashTable.TableEntry<K> expect,
      final ConcurrentChainedReference2IntHashTable.TableEntry<K> update
   ) {
      return (ConcurrentChainedReference2IntHashTable.TableEntry)ConcurrentChainedReference2IntHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .compareAndExchange(
            (ConcurrentChainedReference2IntHashTable.TableEntry[])table,
            (int)index,
            (ConcurrentChainedReference2IntHashTable.TableEntry)expect,
            (ConcurrentChainedReference2IntHashTable.TableEntry)update
         );
   }

   protected ConcurrentChainedReference2IntHashTable.TableEntry<K>[] fetchNewTable(final ConcurrentChainedReference2IntHashTable.TableEntry<K>[] expectedCurr) {
      ConcurrentChainedReference2IntHashTable.TableEntry<K>[] candidate = this.nextTable;
      ConcurrentChainedReference2IntHashTable.TableEntry<K>[] current = this.table;
      return expectedCurr == current ? candidate : current;
   }

   protected final ConcurrentChainedReference2IntHashTable.TableEntry<K> getNode(final K key) {
      int hash = HashUtil.mix(System.identityHashCode(key));
      ConcurrentChainedReference2IntHashTable.TableEntry<K>[] table = this.table;

      while (true) {
         ConcurrentChainedReference2IntHashTable.TableEntry<K> node = getAtIndexAcquire(table, hash & table.length - 1);
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

   public int get(final K key) {
      Objects.requireNonNull(key, "Key may not be null");
      ConcurrentChainedReference2IntHashTable.TableEntry<K> node = this.getNode(key);
      return node == null ? 0 : node.getValueVolatile();
   }

   public int getOrDefault(final K key, final int defaultValue) {
      Objects.requireNonNull(key, "Key may not be null");
      ConcurrentChainedReference2IntHashTable.TableEntry<K> node = this.getNode(key);
      return node == null ? defaultValue : node.getValueVolatile();
   }

   public boolean containsKey(final K key) {
      Objects.requireNonNull(key, "Key may not be null");
      return this.getNode(key) != null;
   }

   public boolean containsValue(final int value) {
      ConcurrentChainedReference2IntHashTable.NodeIterator<K> iterator = new ConcurrentChainedReference2IntHashTable.NodeIterator<>(this);

      ConcurrentChainedReference2IntHashTable.TableEntry<K> node;
      while ((node = iterator.findNext()) != null) {
         int nodeValue = node.getValueAcquire();
         if (value == nodeValue) {
            return true;
         }
      }

      return false;
   }

   public boolean contains(final K key, final int value) {
      Objects.requireNonNull(key, "Key may not be null");
      ConcurrentChainedReference2IntHashTable.TableEntry<K> node = this.getNode(key);
      if (node == null) {
         return false;
      }

      int nodeValue = node.getValueAcquire();
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

      ConcurrentChainedReference2IntHashTable.TableEntry<K>[] newTable = new ConcurrentChainedReference2IntHashTable.TableEntry[capacity];
      ConcurrentChainedReference2IntHashTable.TableEntry<K>[] oldTable = this.table;
      this.nextTable = newTable;
      int capOldShift = IntegerUtil.floorLog2(oldTable.length);
      int capDiffShift = IntegerUtil.floorLog2(capacity) - capOldShift;
      if (capDiffShift == 0) {
         throw new IllegalStateException("Resizing to same size");
      }

      ConcurrentChainedReference2IntHashTable.TableEntry<K>[] work = new ConcurrentChainedReference2IntHashTable.TableEntry[1 << capDiffShift];
      int i = 0;

      for (int len = oldTable.length; i < len; i++) {
         ConcurrentChainedReference2IntHashTable.TableEntry<K> binNode = getAtIndexAcquire(oldTable, i);

         while (
            binNode != null
               || null != (binNode = compareAndExchangeAtIndexVolatile(oldTable, i, null, (ConcurrentChainedReference2IntHashTable.TableEntry<K>)RESIZE_NODE))
         ) {
            synchronized (binNode) {
               if (binNode == (binNode = getAtIndexAcquire(oldTable, i))) {
                  ConcurrentChainedReference2IntHashTable.TableEntry<K> next = binNode.getNextPlain();
                  if (next == null) {
                     newTable[HashUtil.mix(System.identityHashCode(binNode.key)) & capacity - 1] = binNode;
                  } else {
                     Arrays.fill(work, null);

                     for (ConcurrentChainedReference2IntHashTable.TableEntry<K> curr = binNode; curr != null; curr = curr.getNextPlain()) {
                        int newTableIdx = HashUtil.mix(System.identityHashCode(curr.key)) & capacity - 1;
                        int workIdx = newTableIdx >>> capOldShift;
                        ConcurrentChainedReference2IntHashTable.TableEntry<K> replace = new ConcurrentChainedReference2IntHashTable.TableEntry<>(
                           curr.key, curr.getValuePlain()
                        );
                        ConcurrentChainedReference2IntHashTable.TableEntry<K> workNode = work[workIdx];
                        work[workIdx] = replace;
                        if (workNode == null) {
                           newTable[newTableIdx] = replace;
                        } else {
                           workNode.setNextPlain(replace);
                        }
                     }
                  }

                  setAtIndexRelease(oldTable, i, (ConcurrentChainedReference2IntHashTable.TableEntry<K>)RESIZE_NODE);
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

   public int put(final K key, final int value) {
      Objects.requireNonNull(key, "Key may not be null");
      int hash = HashUtil.mix(System.identityHashCode(key));
      ConcurrentChainedReference2IntHashTable.TableEntry<K>[] table = this.table;

      label50:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedReference2IntHashTable.TableEntry<K> node = getAtIndexAcquire(table, index);

         while (
            node != null
               || null != (node = compareAndExchangeAtIndexVolatile(table, index, null, new ConcurrentChainedReference2IntHashTable.TableEntry<>(key, value)))
         ) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label50;
            }

            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedReference2IntHashTable.TableEntry<K> prev = null;

                  while (node != null) {
                     if (node.key == key) {
                        int ret = node.getValuePlain();
                        node.setValueVolatile(value);
                        return ret;
                     }

                     prev = node;
                     node = node.getNextPlain();
                  }

                  prev.setNextRelease(new ConcurrentChainedReference2IntHashTable.TableEntry<>(key, value));
                  break label50;
               }
            }
         }

         this.addSize(1L);
         return 0;
      }

      this.addSize(1L);
      return 0;
   }

   public int putIfAbsent(final K key, final int value) {
      Objects.requireNonNull(key, "Key may not be null");
      int hash = HashUtil.mix(System.identityHashCode(key));
      ConcurrentChainedReference2IntHashTable.TableEntry<K>[] table = this.table;

      label58:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedReference2IntHashTable.TableEntry<K> node = getAtIndexAcquire(table, index);

         while (
            node != null
               || null != (node = compareAndExchangeAtIndexVolatile(table, index, null, new ConcurrentChainedReference2IntHashTable.TableEntry<>(key, value)))
         ) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label58;
            }

            if (node.key == key) {
               return node.getValueVolatile();
            }

            synchronized (node) {
               if (node != (node = getAtIndexAcquire(table, index))) {
                  continue;
               }

               ConcurrentChainedReference2IntHashTable.TableEntry<K> prev = null;

               while (node != null) {
                  if (node.key == key) {
                     return node.getValuePlain();
                  }

                  prev = node;
                  node = node.getNextPlain();
               }

               prev.setNextRelease(new ConcurrentChainedReference2IntHashTable.TableEntry<>(key, value));
            }

            this.addSize(1L);
            return 0;
         }

         this.addSize(1L);
         return 0;
      }
   }

   public int replace(final K key, final int value) {
      Objects.requireNonNull(key, "Key may not be null");
      int hash = HashUtil.mix(System.identityHashCode(key));
      ConcurrentChainedReference2IntHashTable.TableEntry<K>[] table = this.table;

      label45:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedReference2IntHashTable.TableEntry<K> node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label45;
            }

            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  while (node != null) {
                     if (node.key == key) {
                        int ret = node.getValuePlain();
                        node.setValueVolatile(value);
                        return ret;
                     }

                     node = node.getNextPlain();
                  }

                  return 0;
               }
            }
         }

         return 0;
      }
   }

   public int replace(final K key, final int expect, final int update) {
      int hash = HashUtil.mix(System.identityHashCode(key));
      ConcurrentChainedReference2IntHashTable.TableEntry<K>[] table = this.table;

      label51:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedReference2IntHashTable.TableEntry<K> node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label51;
            }

            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  while (node != null) {
                     if (node.key == key) {
                        int ret = node.getValuePlain();
                        if (expect != ret) {
                           return ret;
                        }

                        node.setValueVolatile(update);
                        return ret;
                     }

                     node = node.getNextPlain();
                  }

                  return 0;
               }
            }
         }

         return 0;
      }
   }

   public int remove(final K key) {
      Objects.requireNonNull(key, "Key may not be null");
      int hash = HashUtil.mix(System.identityHashCode(key));
      ConcurrentChainedReference2IntHashTable.TableEntry<K>[] table = this.table;

      boolean removed;
      int ret;
      label53:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedReference2IntHashTable.TableEntry<K> node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label53;
            }

            removed = false;
            ret = 0;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedReference2IntHashTable.TableEntry<K> prev = null;

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

         return 0;
      }

      if (removed) {
         this.subSize(1L);
      }

      return ret;
   }

   public int remove(final K key, final int expect) {
      Objects.requireNonNull(key, "Key may not be null");
      int hash = HashUtil.mix(System.identityHashCode(key));
      ConcurrentChainedReference2IntHashTable.TableEntry<K>[] table = this.table;

      boolean removed;
      int ret;
      label55:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedReference2IntHashTable.TableEntry<K> node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label55;
            }

            removed = false;
            ret = 0;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedReference2IntHashTable.TableEntry<K> prev = null;

                  while (true) {
                     if (node == null) {
                        break label55;
                     }

                     if (node.key == key) {
                        ret = node.getValuePlain();
                        if (expect == ret) {
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

         return 0;
      }

      if (removed) {
         this.subSize(1L);
      }

      return ret;
   }

   public int removeIf(final K key, final ConcurrentChainedReference2IntHashTable.RemoveIfPredicate<K> predicate) {
      Objects.requireNonNull(key, "Key may not be null");
      Objects.requireNonNull(predicate, "Predicate may not be null");
      int hash = HashUtil.mix(System.identityHashCode(key));
      ConcurrentChainedReference2IntHashTable.TableEntry<K>[] table = this.table;

      boolean removed;
      int ret;
      label55:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedReference2IntHashTable.TableEntry<K> node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label55;
            }

            removed = false;
            ret = 0;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedReference2IntHashTable.TableEntry<K> prev = null;

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

         return 0;
      }

      if (removed) {
         this.subSize(1L);
      }

      return ret;
   }

   public int addTo(final K key, final int increment, final int defaultValue) {
      Objects.requireNonNull(key, "Key may not be null");
      int hash = HashUtil.mix(System.identityHashCode(key));
      ConcurrentChainedReference2IntHashTable.TableEntry<K>[] table = this.table;

      label50:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedReference2IntHashTable.TableEntry<K> node = getAtIndexAcquire(table, index);

         while (
            node != null
               || null
                  != (node = compareAndExchangeAtIndexVolatile(table, index, null, new ConcurrentChainedReference2IntHashTable.TableEntry<>(key, defaultValue)))
         ) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label50;
            }

            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedReference2IntHashTable.TableEntry<K> prev = null;

                  while (node != null) {
                     if (node.key == key) {
                        int ret = node.getValuePlain() + increment;
                        node.setValueVolatile(ret);
                        return ret;
                     }

                     prev = node;
                     node = node.getNextPlain();
                  }

                  prev.setNextRelease(new ConcurrentChainedReference2IntHashTable.TableEntry<>(key, defaultValue));
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

   public int decFrom(final K key, final int decrement, final int threshold) {
      Objects.requireNonNull(key, "Key may not be null");
      int hash = HashUtil.mix(System.identityHashCode(key));
      ConcurrentChainedReference2IntHashTable.TableEntry<K>[] table = this.table;

      boolean removed;
      boolean found;
      int ret;
      label60:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedReference2IntHashTable.TableEntry<K> node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label60;
            }

            removed = false;
            found = false;
            ret = 0;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedReference2IntHashTable.TableEntry<K> prev = null;

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
      ConcurrentChainedReference2IntHashTable.NodeIterator<K> nodeIterator = new ConcurrentChainedReference2IntHashTable.NodeIterator<>(this);

      ConcurrentChainedReference2IntHashTable.TableEntry<K> node;
      while ((node = nodeIterator.findNext()) != null) {
         this.remove(node.key);
      }
   }

   public BaseObjectIterator<ConcurrentChainedReference2IntHashTable.TableEntry<K>> entryIterator() {
      return new ConcurrentChainedReference2IntHashTable.EntryNodeIterator<>(this);
   }

   public final BaseObjectIterator<ConcurrentChainedReference2IntHashTable.TableEntry<K>> iterator() {
      return this.entryIterator();
   }

   public BaseObjectIterator<K> keyIterator() {
      return new ConcurrentChainedReference2IntHashTable.KeyIterator<>(this);
   }

   public BaseIntIterator valueIterator() {
      return new ConcurrentChainedReference2IntHashTable.ValueIterator<>(this);
   }

   public Set<K> keys() {
      ConcurrentChainedReference2IntHashTable.Keys<K> keys = this.keys;
      return keys != null ? keys : (this.keys = new ConcurrentChainedReference2IntHashTable.Keys<>(this));
   }

   public Set<ConcurrentChainedReference2IntHashTable.TableEntry<K>> entrySet() {
      ConcurrentChainedReference2IntHashTable.EntrySet<K> entrySet = this.entrySet;
      return entrySet != null ? entrySet : (this.entrySet = new ConcurrentChainedReference2IntHashTable.EntrySet<>(this));
   }

   protected static final class EntryNodeIterator<K> extends BaseObjectIterator<ConcurrentChainedReference2IntHashTable.TableEntry<K>> {
      protected ConcurrentChainedReference2IntHashTable.TableEntry<K> lastReturned;
      protected ConcurrentChainedReference2IntHashTable.TableEntry<K> nextToReturn;
      protected final ConcurrentChainedReference2IntHashTable.NodeIterator<K> nodeIterator;

      protected EntryNodeIterator(final ConcurrentChainedReference2IntHashTable<K> map) {
         super();
         this.nodeIterator = new ConcurrentChainedReference2IntHashTable.NodeIterator<>(map);
      }

      @Override
      public boolean hasNext() {
         return this.nextToReturn != null ? true : (this.nextToReturn = this.nodeIterator.findNext()) != null;
      }

      public ConcurrentChainedReference2IntHashTable.TableEntry<K> next() throws NoSuchElementException {
         ConcurrentChainedReference2IntHashTable.TableEntry<K> ret = this.nextToReturn;
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
         ConcurrentChainedReference2IntHashTable.TableEntry<K> lastReturned = this.lastReturned;
         if (lastReturned == null) {
            throw new NoSuchElementException();
         }

         this.lastReturned = null;
         this.nodeIterator.map.remove(lastReturned.key);
      }
   }

   protected static class EntrySet<K> extends BaseMapSet<ConcurrentChainedReference2IntHashTable.TableEntry<K>> {
      protected final ConcurrentChainedReference2IntHashTable<K> map;

      protected EntrySet(final ConcurrentChainedReference2IntHashTable<K> map) {
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
         if (value instanceof ConcurrentChainedReference2IntHashTable.TableEntry<?> entry) {
            Object entryKey = entry.getKey();
            int entryValue = entry.getValue();
            return this.map.contains((K)entryKey, entryValue);
         } else {
            return false;
         }
      }

      @Override
      protected List<ConcurrentChainedReference2IntHashTable.TableEntry<K>> asList() {
         List<ConcurrentChainedReference2IntHashTable.TableEntry<K>> ret = new ArrayList<>(this.map.size());

         for (ConcurrentChainedReference2IntHashTable.TableEntry<K> element : this) {
            ret.add(element);
         }

         return ret;
      }

      @Override
      public BaseObjectIterator<ConcurrentChainedReference2IntHashTable.TableEntry<K>> iterator() {
         return this.map.entryIterator();
      }
   }

   protected static final class KeyIterator<K> extends BaseObjectIterator<K> {
      private final ConcurrentChainedReference2IntHashTable.EntryNodeIterator<K> iterator;

      public KeyIterator(final ConcurrentChainedReference2IntHashTable<K> map) {
         super();
         this.iterator = new ConcurrentChainedReference2IntHashTable.EntryNodeIterator<>(map);
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
      protected final ConcurrentChainedReference2IntHashTable<K> map;

      protected Keys(final ConcurrentChainedReference2IntHashTable<K> map) {
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
      protected final ConcurrentChainedReference2IntHashTable<K> map;
      private ConcurrentChainedReference2IntHashTable.TableEntry<K>[] currentTable;
      private ConcurrentChainedReference2IntHashTable.NodeIterator.ResizeChain<K> resizeChain;
      private ConcurrentChainedReference2IntHashTable.TableEntry<K> last;
      private int nextBin;
      private int increment;

      protected NodeIterator(final ConcurrentChainedReference2IntHashTable<K> map) {
         super();
         this.map = map;
         this.currentTable = map.table;
         this.increment = 1;
      }

      private ConcurrentChainedReference2IntHashTable.TableEntry<K>[] pullResizeChain(final int index) {
         ConcurrentChainedReference2IntHashTable.NodeIterator.ResizeChain<K> resizeChain = this.resizeChain;
         if (resizeChain == null) {
            this.currentTable = null;
            return null;
         }

         ConcurrentChainedReference2IntHashTable.NodeIterator.ResizeChain<K> prevChain = resizeChain.prev;
         this.resizeChain = prevChain;
         if (prevChain == null) {
            this.currentTable = null;
            return null;
         }

         ConcurrentChainedReference2IntHashTable.TableEntry<K>[] newTable = prevChain.table;
         int newIdx = index & newTable.length - 1;
         ConcurrentChainedReference2IntHashTable.NodeIterator.ResizeChain<K> nextPrevChain = prevChain.prev;
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

      private ConcurrentChainedReference2IntHashTable.TableEntry<K>[] pushResizeChain(final ConcurrentChainedReference2IntHashTable.TableEntry<K>[] table) {
         ConcurrentChainedReference2IntHashTable.NodeIterator.ResizeChain<K> chain = this.resizeChain;
         if (chain == null) {
            ConcurrentChainedReference2IntHashTable.TableEntry<K>[] nextTable = this.map.fetchNewTable(table);
            ConcurrentChainedReference2IntHashTable.NodeIterator.ResizeChain<K> oldChain = new ConcurrentChainedReference2IntHashTable.NodeIterator.ResizeChain<>(
               table, null, null
            );
            ConcurrentChainedReference2IntHashTable.NodeIterator.ResizeChain<K> currChain = new ConcurrentChainedReference2IntHashTable.NodeIterator.ResizeChain<>(
               nextTable, oldChain, null
            );
            oldChain.next = currChain;
            this.increment = table.length;
            this.resizeChain = currChain;
            this.currentTable = nextTable;
            return nextTable;
         } else {
            ConcurrentChainedReference2IntHashTable.NodeIterator.ResizeChain<K> currChain = chain.next;
            if (currChain == null) {
               ConcurrentChainedReference2IntHashTable.TableEntry<K>[] ret = this.map.fetchNewTable(table);
               currChain = new ConcurrentChainedReference2IntHashTable.NodeIterator.ResizeChain<>(ret, chain, null);
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

      protected final ConcurrentChainedReference2IntHashTable.TableEntry<K> findNext() {
         ConcurrentChainedReference2IntHashTable.TableEntry<K> last = this.last;
         if (last != null) {
            ConcurrentChainedReference2IntHashTable.TableEntry<K> next = last.getNextVolatile();
            if (next != null) {
               this.last = next;
               return next;
            }
         }

         ConcurrentChainedReference2IntHashTable.TableEntry<K>[] table = this.currentTable;
         if (table == null) {
            return null;
         }

         int idx = this.nextBin;
         int increment = this.increment;

         do {
            while (idx < table.length) {
               ConcurrentChainedReference2IntHashTable.TableEntry<K> entry = ConcurrentChainedReference2IntHashTable.getAtIndexAcquire(table, idx);
               if (entry == null) {
                  idx += increment;
               } else {
                  if (entry != ConcurrentChainedReference2IntHashTable.RESIZE_NODE) {
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
         public final ConcurrentChainedReference2IntHashTable.TableEntry<K>[] table;
         public final ConcurrentChainedReference2IntHashTable.NodeIterator.ResizeChain<K> prev;
         public ConcurrentChainedReference2IntHashTable.NodeIterator.ResizeChain<K> next;

         public ResizeChain(
            final ConcurrentChainedReference2IntHashTable.TableEntry<K>[] table,
            final ConcurrentChainedReference2IntHashTable.NodeIterator.ResizeChain<K> prev,
            final ConcurrentChainedReference2IntHashTable.NodeIterator.ResizeChain<K> next
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
      boolean test(final int value);
   }

   public static final class TableEntry<K> {
      private static final VarHandle TABLE_ENTRY_ARRAY_HANDLE = ConcurrentUtil.getArrayHandle(ConcurrentChainedReference2IntHashTable.TableEntry[].class);
      private final K key;
      private volatile int value;
      private static final VarHandle VALUE_HANDLE = ConcurrentUtil.getVarHandle(ConcurrentChainedReference2IntHashTable.TableEntry.class, "value", int.class);
      private volatile ConcurrentChainedReference2IntHashTable.TableEntry<K> next;
      private static final VarHandle NEXT_HANDLE = ConcurrentUtil.getVarHandle(
         ConcurrentChainedReference2IntHashTable.TableEntry.class, "next", ConcurrentChainedReference2IntHashTable.TableEntry.class
      );

      private int getValuePlain() {
         return (int)VALUE_HANDLE.get((ConcurrentChainedReference2IntHashTable.TableEntry)this);
      }

      private int getValueAcquire() {
         return (int)VALUE_HANDLE.getAcquire((ConcurrentChainedReference2IntHashTable.TableEntry)this);
      }

      private int getValueVolatile() {
         return (int)VALUE_HANDLE.getVolatile((ConcurrentChainedReference2IntHashTable.TableEntry)this);
      }

      private void setValuePlain(final int value) {
         VALUE_HANDLE.set((ConcurrentChainedReference2IntHashTable.TableEntry)this, (int)value);
      }

      private void setValueRelease(final int value) {
         VALUE_HANDLE.setRelease((ConcurrentChainedReference2IntHashTable.TableEntry)this, (int)value);
      }

      private void setValueVolatile(final int value) {
         VALUE_HANDLE.setVolatile((ConcurrentChainedReference2IntHashTable.TableEntry)this, (int)value);
      }

      private ConcurrentChainedReference2IntHashTable.TableEntry<K> getNextPlain() {
         return (ConcurrentChainedReference2IntHashTable.TableEntry)NEXT_HANDLE.get((ConcurrentChainedReference2IntHashTable.TableEntry)this);
      }

      private ConcurrentChainedReference2IntHashTable.TableEntry<K> getNextAcquire() {
         return (ConcurrentChainedReference2IntHashTable.TableEntry)NEXT_HANDLE.getAcquire((ConcurrentChainedReference2IntHashTable.TableEntry)this);
      }

      private ConcurrentChainedReference2IntHashTable.TableEntry<K> getNextVolatile() {
         return (ConcurrentChainedReference2IntHashTable.TableEntry)NEXT_HANDLE.getVolatile((ConcurrentChainedReference2IntHashTable.TableEntry)this);
      }

      private void setNextPlain(final ConcurrentChainedReference2IntHashTable.TableEntry<K> next) {
         NEXT_HANDLE.set((ConcurrentChainedReference2IntHashTable.TableEntry)this, (ConcurrentChainedReference2IntHashTable.TableEntry)next);
      }

      private void setNextRelease(final ConcurrentChainedReference2IntHashTable.TableEntry<K> next) {
         NEXT_HANDLE.setRelease((ConcurrentChainedReference2IntHashTable.TableEntry)this, (ConcurrentChainedReference2IntHashTable.TableEntry)next);
      }

      private void setNextVolatile(final ConcurrentChainedReference2IntHashTable.TableEntry<K> next) {
         NEXT_HANDLE.setVolatile((ConcurrentChainedReference2IntHashTable.TableEntry)this, (ConcurrentChainedReference2IntHashTable.TableEntry)next);
      }

      protected TableEntry(final K key, final int value) {
         super();
         this.key = key;
         this.setValuePlain(value);
      }

      public K getKey() {
         return this.key;
      }

      public int getValue() {
         return this.getValueVolatile();
      }
   }

   protected static final class ValueIterator<K> extends BaseIntIterator {
      private final ConcurrentChainedReference2IntHashTable.EntryNodeIterator<K> iterator;

      public ValueIterator(final ConcurrentChainedReference2IntHashTable<K> map) {
         super();
         this.iterator = new ConcurrentChainedReference2IntHashTable.EntryNodeIterator<>(map);
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
      public int nextInt() {
         return this.iterator.next().getValueVolatile();
      }
   }
}
