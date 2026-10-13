package ca.spottedleaf.concurrentutil.map.concurrent.ints;

import ca.spottedleaf.common.util.HashUtil;
import ca.spottedleaf.common.util.IntegerUtil;
import ca.spottedleaf.concurrentutil.collection.iterator.BaseIntIterator;
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

public class ConcurrentChainedInt2LongHashTable implements Iterable<ConcurrentChainedInt2LongHashTable.TableEntry> {
   private static final ConcurrentChainedInt2LongHashTable.TableEntry RESIZE_NODE = new ConcurrentChainedInt2LongHashTable.TableEntry(0, 0L);
   protected static final int DEFAULT_CAPACITY = 16;
   protected static final float DEFAULT_LOAD_FACTOR = 0.75F;
   protected static final int MAXIMUM_CAPACITY = 1073741824;
   protected final AtomicLong size = new AtomicLong();
   protected final float loadFactor;
   protected volatile ConcurrentChainedInt2LongHashTable.TableEntry[] table;
   protected volatile ConcurrentChainedInt2LongHashTable.TableEntry[] nextTable;
   protected static final int THRESHOLD_NO_RESIZE = -1;
   protected static final int THRESHOLD_RESIZING = -2;
   protected volatile int threshold;
   protected static final VarHandle THRESHOLD_HANDLE = ConcurrentUtil.getVarHandle(ConcurrentChainedInt2LongHashTable.class, "threshold", int.class);
   protected ConcurrentChainedInt2LongHashTable.EntrySet entrySet;

   public static int defaultCapacity() {
      return 16;
   }

   public static float defaultLoadFactor() {
      return 0.75F;
   }

   protected final int getThresholdAcquire() {
      return (int)THRESHOLD_HANDLE.getAcquire((ConcurrentChainedInt2LongHashTable)this);
   }

   protected final int getThresholdVolatile() {
      return (int)THRESHOLD_HANDLE.getVolatile((ConcurrentChainedInt2LongHashTable)this);
   }

   protected final void setThresholdPlain(final int threshold) {
      THRESHOLD_HANDLE.set((ConcurrentChainedInt2LongHashTable)this, (int)threshold);
   }

   protected final void setThresholdRelease(final int threshold) {
      THRESHOLD_HANDLE.setRelease((ConcurrentChainedInt2LongHashTable)this, (int)threshold);
   }

   protected final void setThresholdVolatile(final int threshold) {
      THRESHOLD_HANDLE.setVolatile((ConcurrentChainedInt2LongHashTable)this, (int)threshold);
   }

   protected final int compareExchangeThresholdVolatile(final int expect, final int update) {
      return (int)THRESHOLD_HANDLE.compareAndExchange((ConcurrentChainedInt2LongHashTable)this, (int)expect, (int)update);
   }

   public ConcurrentChainedInt2LongHashTable() {
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

   protected ConcurrentChainedInt2LongHashTable(final int capacity, final float loadFactor) {
      super();
      int tableSize = getCapacityFor(capacity);
      if (!(loadFactor <= 0.0) && Float.isFinite(loadFactor)) {
         if (tableSize == 1073741824) {
            this.setThresholdPlain(-1);
         } else {
            this.setThresholdPlain(getTargetThreshold(tableSize, loadFactor));
         }

         this.loadFactor = loadFactor;
         this.nextTable = this.table = new ConcurrentChainedInt2LongHashTable.TableEntry[tableSize];
      } else {
         throw new IllegalArgumentException("Invalid load factor: " + loadFactor);
      }
   }

   public static ConcurrentChainedInt2LongHashTable createWithCapacity(final int capacity) {
      return createWithCapacity(capacity, 0.75F);
   }

   public static ConcurrentChainedInt2LongHashTable createWithCapacity(final int capacity, final float loadFactor) {
      return new ConcurrentChainedInt2LongHashTable(capacity, loadFactor);
   }

   public static ConcurrentChainedInt2LongHashTable createWithExpected(final int expected) {
      return createWithExpected(expected, 0.75F);
   }

   public static ConcurrentChainedInt2LongHashTable createWithExpected(final int expected, final float loadFactor) {
      double capacity = Math.ceil((double)expected / loadFactor);
      if (!Double.isFinite(capacity)) {
         throw new IllegalArgumentException("Invalid load factor");
      }

      if (capacity > 2.147483647E9) {
         capacity = 2.147483647E9;
      }

      return createWithCapacity((int)capacity, loadFactor);
   }

   protected static int getHash(final int key) {
      return HashUtil.mix(key);
   }

   public final float getLoadFactor() {
      return this.loadFactor;
   }

   protected static ConcurrentChainedInt2LongHashTable.TableEntry getAtIndexAcquire(
      final ConcurrentChainedInt2LongHashTable.TableEntry[] table, final int index
   ) {
      return (ConcurrentChainedInt2LongHashTable.TableEntry)ConcurrentChainedInt2LongHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .getAcquire((ConcurrentChainedInt2LongHashTable.TableEntry[])table, (int)index);
   }

   protected static void setAtIndexRelease(
      final ConcurrentChainedInt2LongHashTable.TableEntry[] table, final int index, final ConcurrentChainedInt2LongHashTable.TableEntry value
   ) {
      ConcurrentChainedInt2LongHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .setRelease((ConcurrentChainedInt2LongHashTable.TableEntry[])table, (int)index, (ConcurrentChainedInt2LongHashTable.TableEntry)value);
   }

   protected static void setAtIndexVolatile(
      final ConcurrentChainedInt2LongHashTable.TableEntry[] table, final int index, final ConcurrentChainedInt2LongHashTable.TableEntry value
   ) {
      ConcurrentChainedInt2LongHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .setVolatile((ConcurrentChainedInt2LongHashTable.TableEntry[])table, (int)index, (ConcurrentChainedInt2LongHashTable.TableEntry)value);
   }

   protected static ConcurrentChainedInt2LongHashTable.TableEntry compareAndExchangeAtIndexVolatile(
      final ConcurrentChainedInt2LongHashTable.TableEntry[] table,
      final int index,
      final ConcurrentChainedInt2LongHashTable.TableEntry expect,
      final ConcurrentChainedInt2LongHashTable.TableEntry update
   ) {
      return (ConcurrentChainedInt2LongHashTable.TableEntry)ConcurrentChainedInt2LongHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .compareAndExchange(
            (ConcurrentChainedInt2LongHashTable.TableEntry[])table,
            (int)index,
            (ConcurrentChainedInt2LongHashTable.TableEntry)expect,
            (ConcurrentChainedInt2LongHashTable.TableEntry)update
         );
   }

   protected ConcurrentChainedInt2LongHashTable.TableEntry[] fetchNewTable(final ConcurrentChainedInt2LongHashTable.TableEntry[] expectedCurr) {
      ConcurrentChainedInt2LongHashTable.TableEntry[] candidate = this.nextTable;
      ConcurrentChainedInt2LongHashTable.TableEntry[] current = this.table;
      return expectedCurr == current ? candidate : current;
   }

   protected final ConcurrentChainedInt2LongHashTable.TableEntry getNode(final int key) {
      int hash = HashUtil.mix(key);
      ConcurrentChainedInt2LongHashTable.TableEntry[] table = this.table;

      while (true) {
         ConcurrentChainedInt2LongHashTable.TableEntry node = getAtIndexAcquire(table, hash & table.length - 1);
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

   public long get(final int key) {
      ConcurrentChainedInt2LongHashTable.TableEntry node = this.getNode(key);
      return node == null ? 0L : node.getValueVolatile();
   }

   public long getOrDefault(final int key, final long defaultValue) {
      ConcurrentChainedInt2LongHashTable.TableEntry node = this.getNode(key);
      return node == null ? defaultValue : node.getValueVolatile();
   }

   public boolean containsKey(final int key) {
      return this.getNode(key) != null;
   }

   public boolean containsValue(final long value) {
      ConcurrentChainedInt2LongHashTable.NodeIterator iterator = new ConcurrentChainedInt2LongHashTable.NodeIterator(this);

      ConcurrentChainedInt2LongHashTable.TableEntry node;
      while ((node = iterator.findNext()) != null) {
         long nodeValue = node.getValueAcquire();
         if (value == nodeValue) {
            return true;
         }
      }

      return false;
   }

   public boolean contains(final int key, final long value) {
      ConcurrentChainedInt2LongHashTable.TableEntry node = this.getNode(key);
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

      ConcurrentChainedInt2LongHashTable.TableEntry[] newTable = new ConcurrentChainedInt2LongHashTable.TableEntry[capacity];
      ConcurrentChainedInt2LongHashTable.TableEntry[] oldTable = this.table;
      this.nextTable = newTable;
      int capOldShift = IntegerUtil.floorLog2(oldTable.length);
      int capDiffShift = IntegerUtil.floorLog2(capacity) - capOldShift;
      if (capDiffShift == 0) {
         throw new IllegalStateException("Resizing to same size");
      }

      ConcurrentChainedInt2LongHashTable.TableEntry[] work = new ConcurrentChainedInt2LongHashTable.TableEntry[1 << capDiffShift];
      int i = 0;

      for (int len = oldTable.length; i < len; i++) {
         ConcurrentChainedInt2LongHashTable.TableEntry binNode = getAtIndexAcquire(oldTable, i);

         while (binNode != null || null != (binNode = compareAndExchangeAtIndexVolatile(oldTable, i, null, RESIZE_NODE))) {
            synchronized (binNode) {
               if (binNode == (binNode = getAtIndexAcquire(oldTable, i))) {
                  ConcurrentChainedInt2LongHashTable.TableEntry next = binNode.getNextPlain();
                  if (next == null) {
                     newTable[HashUtil.mix(binNode.key) & capacity - 1] = binNode;
                  } else {
                     Arrays.fill(work, null);

                     for (ConcurrentChainedInt2LongHashTable.TableEntry curr = binNode; curr != null; curr = curr.getNextPlain()) {
                        int newTableIdx = HashUtil.mix(curr.key) & capacity - 1;
                        int workIdx = newTableIdx >>> capOldShift;
                        ConcurrentChainedInt2LongHashTable.TableEntry replace = new ConcurrentChainedInt2LongHashTable.TableEntry(
                           curr.key, curr.getValuePlain()
                        );
                        ConcurrentChainedInt2LongHashTable.TableEntry workNode = work[workIdx];
                        work[workIdx] = replace;
                        if (workNode == null) {
                           newTable[newTableIdx] = replace;
                        } else {
                           workNode.setNextPlain(replace);
                        }
                     }
                  }

                  setAtIndexRelease(oldTable, i, RESIZE_NODE);
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

   public long put(final int key, final long value) {
      int hash = HashUtil.mix(key);
      ConcurrentChainedInt2LongHashTable.TableEntry[] table = this.table;

      label50:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedInt2LongHashTable.TableEntry node = getAtIndexAcquire(table, index);

         while (
            node != null
               || null != (node = compareAndExchangeAtIndexVolatile(table, index, null, new ConcurrentChainedInt2LongHashTable.TableEntry(key, value)))
         ) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label50;
            }

            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedInt2LongHashTable.TableEntry prev = null;

                  while (node != null) {
                     if (node.key == key) {
                        long ret = node.getValuePlain();
                        node.setValueVolatile(value);
                        return ret;
                     }

                     prev = node;
                     node = node.getNextPlain();
                  }

                  prev.setNextRelease(new ConcurrentChainedInt2LongHashTable.TableEntry(key, value));
                  break label50;
               }
            }
         }

         this.addSize(1L);
         return 0L;
      }

      this.addSize(1L);
      return 0L;
   }

   public long putIfAbsent(final int key, final long value) {
      int hash = HashUtil.mix(key);
      ConcurrentChainedInt2LongHashTable.TableEntry[] table = this.table;

      label58:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedInt2LongHashTable.TableEntry node = getAtIndexAcquire(table, index);

         while (
            node != null
               || null != (node = compareAndExchangeAtIndexVolatile(table, index, null, new ConcurrentChainedInt2LongHashTable.TableEntry(key, value)))
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

               ConcurrentChainedInt2LongHashTable.TableEntry prev = null;

               while (node != null) {
                  if (node.key == key) {
                     return node.getValuePlain();
                  }

                  prev = node;
                  node = node.getNextPlain();
               }

               prev.setNextRelease(new ConcurrentChainedInt2LongHashTable.TableEntry(key, value));
            }

            this.addSize(1L);
            return 0L;
         }

         this.addSize(1L);
         return 0L;
      }
   }

   public long replace(final int key, final long value) {
      int hash = HashUtil.mix(key);
      ConcurrentChainedInt2LongHashTable.TableEntry[] table = this.table;

      label45:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedInt2LongHashTable.TableEntry node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label45;
            }

            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  while (node != null) {
                     if (node.key == key) {
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

   public long replace(final int key, final long expect, final long update) {
      int hash = HashUtil.mix(key);
      ConcurrentChainedInt2LongHashTable.TableEntry[] table = this.table;

      label51:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedInt2LongHashTable.TableEntry node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label51;
            }

            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  while (node != null) {
                     if (node.key == key) {
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

   public long remove(final int key) {
      int hash = HashUtil.mix(key);
      ConcurrentChainedInt2LongHashTable.TableEntry[] table = this.table;

      boolean removed;
      long ret;
      label53:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedInt2LongHashTable.TableEntry node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label53;
            }

            removed = false;
            ret = 0L;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedInt2LongHashTable.TableEntry prev = null;

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

         return 0L;
      }

      if (removed) {
         this.subSize(1L);
      }

      return ret;
   }

   public long remove(final int key, final long expect) {
      int hash = HashUtil.mix(key);
      ConcurrentChainedInt2LongHashTable.TableEntry[] table = this.table;

      boolean removed;
      long ret;
      label55:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedInt2LongHashTable.TableEntry node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label55;
            }

            removed = false;
            ret = 0L;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedInt2LongHashTable.TableEntry prev = null;

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

         return 0L;
      }

      if (removed) {
         this.subSize(1L);
      }

      return ret;
   }

   public long removeIf(final int key, final ConcurrentChainedInt2LongHashTable.RemoveIfPredicate predicate) {
      Objects.requireNonNull(predicate, "Predicate may not be null");
      int hash = HashUtil.mix(key);
      ConcurrentChainedInt2LongHashTable.TableEntry[] table = this.table;

      boolean removed;
      long ret;
      label55:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedInt2LongHashTable.TableEntry node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label55;
            }

            removed = false;
            ret = 0L;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedInt2LongHashTable.TableEntry prev = null;

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

         return 0L;
      }

      if (removed) {
         this.subSize(1L);
      }

      return ret;
   }

   public long addTo(final int key, final long increment, final long defaultValue) {
      int hash = HashUtil.mix(key);
      ConcurrentChainedInt2LongHashTable.TableEntry[] table = this.table;

      label50:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedInt2LongHashTable.TableEntry node = getAtIndexAcquire(table, index);

         while (
            node != null
               || null != (node = compareAndExchangeAtIndexVolatile(table, index, null, new ConcurrentChainedInt2LongHashTable.TableEntry(key, defaultValue)))
         ) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label50;
            }

            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedInt2LongHashTable.TableEntry prev = null;

                  while (node != null) {
                     if (node.key == key) {
                        long ret = node.getValuePlain() + increment;
                        node.setValueVolatile(ret);
                        return ret;
                     }

                     prev = node;
                     node = node.getNextPlain();
                  }

                  prev.setNextRelease(new ConcurrentChainedInt2LongHashTable.TableEntry(key, defaultValue));
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

   public long decFrom(final int key, final long decrement, final long threshold) {
      int hash = HashUtil.mix(key);
      ConcurrentChainedInt2LongHashTable.TableEntry[] table = this.table;

      boolean removed;
      boolean found;
      long ret;
      label60:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedInt2LongHashTable.TableEntry node = getAtIndexAcquire(table, index);

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
                  ConcurrentChainedInt2LongHashTable.TableEntry prev = null;

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
      ConcurrentChainedInt2LongHashTable.NodeIterator nodeIterator = new ConcurrentChainedInt2LongHashTable.NodeIterator(this);

      ConcurrentChainedInt2LongHashTable.TableEntry node;
      while ((node = nodeIterator.findNext()) != null) {
         this.remove(node.key);
      }
   }

   public BaseObjectIterator<ConcurrentChainedInt2LongHashTable.TableEntry> entryIterator() {
      return new ConcurrentChainedInt2LongHashTable.EntryNodeIterator(this);
   }

   public final BaseObjectIterator<ConcurrentChainedInt2LongHashTable.TableEntry> iterator() {
      return this.entryIterator();
   }

   public BaseIntIterator keyIterator() {
      return new ConcurrentChainedInt2LongHashTable.KeyIterator(this);
   }

   public BaseLongIterator valueIterator() {
      return new ConcurrentChainedInt2LongHashTable.ValueIterator(this);
   }

   public Set<ConcurrentChainedInt2LongHashTable.TableEntry> entrySet() {
      ConcurrentChainedInt2LongHashTable.EntrySet entrySet = this.entrySet;
      return entrySet != null ? entrySet : (this.entrySet = new ConcurrentChainedInt2LongHashTable.EntrySet(this));
   }

   protected static final class EntryNodeIterator extends BaseObjectIterator<ConcurrentChainedInt2LongHashTable.TableEntry> {
      protected ConcurrentChainedInt2LongHashTable.TableEntry lastReturned;
      protected ConcurrentChainedInt2LongHashTable.TableEntry nextToReturn;
      protected final ConcurrentChainedInt2LongHashTable.NodeIterator nodeIterator;

      protected EntryNodeIterator(final ConcurrentChainedInt2LongHashTable map) {
         super();
         this.nodeIterator = new ConcurrentChainedInt2LongHashTable.NodeIterator(map);
      }

      @Override
      public boolean hasNext() {
         return this.nextToReturn != null ? true : (this.nextToReturn = this.nodeIterator.findNext()) != null;
      }

      public ConcurrentChainedInt2LongHashTable.TableEntry next() throws NoSuchElementException {
         ConcurrentChainedInt2LongHashTable.TableEntry ret = this.nextToReturn;
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
         ConcurrentChainedInt2LongHashTable.TableEntry lastReturned = this.lastReturned;
         if (lastReturned == null) {
            throw new NoSuchElementException();
         }

         this.lastReturned = null;
         this.nodeIterator.map.remove(lastReturned.key);
      }
   }

   protected static class EntrySet extends BaseMapSet<ConcurrentChainedInt2LongHashTable.TableEntry> {
      protected final ConcurrentChainedInt2LongHashTable map;

      protected EntrySet(final ConcurrentChainedInt2LongHashTable map) {
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
         if (value instanceof ConcurrentChainedInt2LongHashTable.TableEntry entry) {
            int entryKey = entry.getKey();
            long entryValue = entry.getValue();
            return this.map.contains(entryKey, entryValue);
         } else {
            return false;
         }
      }

      @Override
      protected List<ConcurrentChainedInt2LongHashTable.TableEntry> asList() {
         List<ConcurrentChainedInt2LongHashTable.TableEntry> ret = new ArrayList<>(this.map.size());

         for (ConcurrentChainedInt2LongHashTable.TableEntry element : this) {
            ret.add(element);
         }

         return ret;
      }

      @Override
      public BaseObjectIterator<ConcurrentChainedInt2LongHashTable.TableEntry> iterator() {
         return this.map.entryIterator();
      }
   }

   protected static final class KeyIterator extends BaseIntIterator {
      private final ConcurrentChainedInt2LongHashTable.EntryNodeIterator iterator;

      public KeyIterator(final ConcurrentChainedInt2LongHashTable map) {
         super();
         this.iterator = new ConcurrentChainedInt2LongHashTable.EntryNodeIterator(map);
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
         return this.iterator.next().key;
      }
   }

   protected static class NodeIterator {
      protected final ConcurrentChainedInt2LongHashTable map;
      private ConcurrentChainedInt2LongHashTable.TableEntry[] currentTable;
      private ConcurrentChainedInt2LongHashTable.NodeIterator.ResizeChain resizeChain;
      private ConcurrentChainedInt2LongHashTable.TableEntry last;
      private int nextBin;
      private int increment;

      protected NodeIterator(final ConcurrentChainedInt2LongHashTable map) {
         super();
         this.map = map;
         this.currentTable = map.table;
         this.increment = 1;
      }

      private ConcurrentChainedInt2LongHashTable.TableEntry[] pullResizeChain(final int index) {
         ConcurrentChainedInt2LongHashTable.NodeIterator.ResizeChain resizeChain = this.resizeChain;
         if (resizeChain == null) {
            this.currentTable = null;
            return null;
         }

         ConcurrentChainedInt2LongHashTable.NodeIterator.ResizeChain prevChain = resizeChain.prev;
         this.resizeChain = prevChain;
         if (prevChain == null) {
            this.currentTable = null;
            return null;
         }

         ConcurrentChainedInt2LongHashTable.TableEntry[] newTable = prevChain.table;
         int newIdx = index & newTable.length - 1;
         ConcurrentChainedInt2LongHashTable.NodeIterator.ResizeChain nextPrevChain = prevChain.prev;
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

      private ConcurrentChainedInt2LongHashTable.TableEntry[] pushResizeChain(final ConcurrentChainedInt2LongHashTable.TableEntry[] table) {
         ConcurrentChainedInt2LongHashTable.NodeIterator.ResizeChain chain = this.resizeChain;
         if (chain == null) {
            ConcurrentChainedInt2LongHashTable.TableEntry[] nextTable = this.map.fetchNewTable(table);
            ConcurrentChainedInt2LongHashTable.NodeIterator.ResizeChain oldChain = new ConcurrentChainedInt2LongHashTable.NodeIterator.ResizeChain(
               table, null, null
            );
            ConcurrentChainedInt2LongHashTable.NodeIterator.ResizeChain currChain = new ConcurrentChainedInt2LongHashTable.NodeIterator.ResizeChain(
               nextTable, oldChain, null
            );
            oldChain.next = currChain;
            this.increment = table.length;
            this.resizeChain = currChain;
            this.currentTable = nextTable;
            return nextTable;
         } else {
            ConcurrentChainedInt2LongHashTable.NodeIterator.ResizeChain currChain = chain.next;
            if (currChain == null) {
               ConcurrentChainedInt2LongHashTable.TableEntry[] ret = this.map.fetchNewTable(table);
               currChain = new ConcurrentChainedInt2LongHashTable.NodeIterator.ResizeChain(ret, chain, null);
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

      protected final ConcurrentChainedInt2LongHashTable.TableEntry findNext() {
         ConcurrentChainedInt2LongHashTable.TableEntry last = this.last;
         if (last != null) {
            ConcurrentChainedInt2LongHashTable.TableEntry next = last.getNextVolatile();
            if (next != null) {
               this.last = next;
               return next;
            }
         }

         ConcurrentChainedInt2LongHashTable.TableEntry[] table = this.currentTable;
         if (table == null) {
            return null;
         }

         int idx = this.nextBin;
         int increment = this.increment;

         do {
            while (idx < table.length) {
               ConcurrentChainedInt2LongHashTable.TableEntry entry = ConcurrentChainedInt2LongHashTable.getAtIndexAcquire(table, idx);
               if (entry == null) {
                  idx += increment;
               } else {
                  if (entry != ConcurrentChainedInt2LongHashTable.RESIZE_NODE) {
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

      private static final class ResizeChain {
         public final ConcurrentChainedInt2LongHashTable.TableEntry[] table;
         public final ConcurrentChainedInt2LongHashTable.NodeIterator.ResizeChain prev;
         public ConcurrentChainedInt2LongHashTable.NodeIterator.ResizeChain next;

         public ResizeChain(
            final ConcurrentChainedInt2LongHashTable.TableEntry[] table,
            final ConcurrentChainedInt2LongHashTable.NodeIterator.ResizeChain prev,
            final ConcurrentChainedInt2LongHashTable.NodeIterator.ResizeChain next
         ) {
            super();
            this.table = table;
            this.prev = prev;
            this.next = next;
         }
      }
   }

   @FunctionalInterface
   public interface RemoveIfPredicate {
      boolean test(final long value);
   }

   public static final class TableEntry {
      private static final VarHandle TABLE_ENTRY_ARRAY_HANDLE = ConcurrentUtil.getArrayHandle(ConcurrentChainedInt2LongHashTable.TableEntry[].class);
      private final int key;
      private volatile long value;
      private static final VarHandle VALUE_HANDLE = ConcurrentUtil.getVarHandle(ConcurrentChainedInt2LongHashTable.TableEntry.class, "value", long.class);
      private volatile ConcurrentChainedInt2LongHashTable.TableEntry next;
      private static final VarHandle NEXT_HANDLE = ConcurrentUtil.getVarHandle(
         ConcurrentChainedInt2LongHashTable.TableEntry.class, "next", ConcurrentChainedInt2LongHashTable.TableEntry.class
      );

      private long getValuePlain() {
         return (long)VALUE_HANDLE.get((ConcurrentChainedInt2LongHashTable.TableEntry)this);
      }

      private long getValueAcquire() {
         return (long)VALUE_HANDLE.getAcquire((ConcurrentChainedInt2LongHashTable.TableEntry)this);
      }

      private long getValueVolatile() {
         return (long)VALUE_HANDLE.getVolatile((ConcurrentChainedInt2LongHashTable.TableEntry)this);
      }

      private void setValuePlain(final long value) {
         VALUE_HANDLE.set((ConcurrentChainedInt2LongHashTable.TableEntry)this, (long)value);
      }

      private void setValueRelease(final long value) {
         VALUE_HANDLE.setRelease((ConcurrentChainedInt2LongHashTable.TableEntry)this, (long)value);
      }

      private void setValueVolatile(final long value) {
         VALUE_HANDLE.setVolatile((ConcurrentChainedInt2LongHashTable.TableEntry)this, (long)value);
      }

      private ConcurrentChainedInt2LongHashTable.TableEntry getNextPlain() {
         return (ConcurrentChainedInt2LongHashTable.TableEntry)NEXT_HANDLE.get((ConcurrentChainedInt2LongHashTable.TableEntry)this);
      }

      private ConcurrentChainedInt2LongHashTable.TableEntry getNextAcquire() {
         return (ConcurrentChainedInt2LongHashTable.TableEntry)NEXT_HANDLE.getAcquire((ConcurrentChainedInt2LongHashTable.TableEntry)this);
      }

      private ConcurrentChainedInt2LongHashTable.TableEntry getNextVolatile() {
         return (ConcurrentChainedInt2LongHashTable.TableEntry)NEXT_HANDLE.getVolatile((ConcurrentChainedInt2LongHashTable.TableEntry)this);
      }

      private void setNextPlain(final ConcurrentChainedInt2LongHashTable.TableEntry next) {
         NEXT_HANDLE.set((ConcurrentChainedInt2LongHashTable.TableEntry)this, (ConcurrentChainedInt2LongHashTable.TableEntry)next);
      }

      private void setNextRelease(final ConcurrentChainedInt2LongHashTable.TableEntry next) {
         NEXT_HANDLE.setRelease((ConcurrentChainedInt2LongHashTable.TableEntry)this, (ConcurrentChainedInt2LongHashTable.TableEntry)next);
      }

      private void setNextVolatile(final ConcurrentChainedInt2LongHashTable.TableEntry next) {
         NEXT_HANDLE.setVolatile((ConcurrentChainedInt2LongHashTable.TableEntry)this, (ConcurrentChainedInt2LongHashTable.TableEntry)next);
      }

      protected TableEntry(final int key, final long value) {
         super();
         this.key = key;
         this.setValuePlain(value);
      }

      public int getKey() {
         return this.key;
      }

      public long getValue() {
         return this.getValueVolatile();
      }
   }

   protected static final class ValueIterator extends BaseLongIterator {
      private final ConcurrentChainedInt2LongHashTable.EntryNodeIterator iterator;

      public ValueIterator(final ConcurrentChainedInt2LongHashTable map) {
         super();
         this.iterator = new ConcurrentChainedInt2LongHashTable.EntryNodeIterator(map);
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
