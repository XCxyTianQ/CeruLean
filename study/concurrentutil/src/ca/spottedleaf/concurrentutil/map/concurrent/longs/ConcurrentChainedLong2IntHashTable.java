package ca.spottedleaf.concurrentutil.map.concurrent.longs;

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

public class ConcurrentChainedLong2IntHashTable implements Iterable<ConcurrentChainedLong2IntHashTable.TableEntry> {
   private static final ConcurrentChainedLong2IntHashTable.TableEntry RESIZE_NODE = new ConcurrentChainedLong2IntHashTable.TableEntry(0L, 0);
   protected static final int DEFAULT_CAPACITY = 16;
   protected static final float DEFAULT_LOAD_FACTOR = 0.75F;
   protected static final int MAXIMUM_CAPACITY = 1073741824;
   protected final AtomicLong size = new AtomicLong();
   protected final float loadFactor;
   protected volatile ConcurrentChainedLong2IntHashTable.TableEntry[] table;
   protected volatile ConcurrentChainedLong2IntHashTable.TableEntry[] nextTable;
   protected static final int THRESHOLD_NO_RESIZE = -1;
   protected static final int THRESHOLD_RESIZING = -2;
   protected volatile int threshold;
   protected static final VarHandle THRESHOLD_HANDLE = ConcurrentUtil.getVarHandle(ConcurrentChainedLong2IntHashTable.class, "threshold", int.class);
   protected ConcurrentChainedLong2IntHashTable.EntrySet entrySet;

   public static int defaultCapacity() {
      return 16;
   }

   public static float defaultLoadFactor() {
      return 0.75F;
   }

   protected final int getThresholdAcquire() {
      return (int)THRESHOLD_HANDLE.getAcquire((ConcurrentChainedLong2IntHashTable)this);
   }

   protected final int getThresholdVolatile() {
      return (int)THRESHOLD_HANDLE.getVolatile((ConcurrentChainedLong2IntHashTable)this);
   }

   protected final void setThresholdPlain(final int threshold) {
      THRESHOLD_HANDLE.set((ConcurrentChainedLong2IntHashTable)this, (int)threshold);
   }

   protected final void setThresholdRelease(final int threshold) {
      THRESHOLD_HANDLE.setRelease((ConcurrentChainedLong2IntHashTable)this, (int)threshold);
   }

   protected final void setThresholdVolatile(final int threshold) {
      THRESHOLD_HANDLE.setVolatile((ConcurrentChainedLong2IntHashTable)this, (int)threshold);
   }

   protected final int compareExchangeThresholdVolatile(final int expect, final int update) {
      return (int)THRESHOLD_HANDLE.compareAndExchange((ConcurrentChainedLong2IntHashTable)this, (int)expect, (int)update);
   }

   public ConcurrentChainedLong2IntHashTable() {
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

   protected ConcurrentChainedLong2IntHashTable(final int capacity, final float loadFactor) {
      super();
      int tableSize = getCapacityFor(capacity);
      if (!(loadFactor <= 0.0) && Float.isFinite(loadFactor)) {
         if (tableSize == 1073741824) {
            this.setThresholdPlain(-1);
         } else {
            this.setThresholdPlain(getTargetThreshold(tableSize, loadFactor));
         }

         this.loadFactor = loadFactor;
         this.nextTable = this.table = new ConcurrentChainedLong2IntHashTable.TableEntry[tableSize];
      } else {
         throw new IllegalArgumentException("Invalid load factor: " + loadFactor);
      }
   }

   public static ConcurrentChainedLong2IntHashTable createWithCapacity(final int capacity) {
      return createWithCapacity(capacity, 0.75F);
   }

   public static ConcurrentChainedLong2IntHashTable createWithCapacity(final int capacity, final float loadFactor) {
      return new ConcurrentChainedLong2IntHashTable(capacity, loadFactor);
   }

   public static ConcurrentChainedLong2IntHashTable createWithExpected(final int expected) {
      return createWithExpected(expected, 0.75F);
   }

   public static ConcurrentChainedLong2IntHashTable createWithExpected(final int expected, final float loadFactor) {
      double capacity = Math.ceil((double)expected / loadFactor);
      if (!Double.isFinite(capacity)) {
         throw new IllegalArgumentException("Invalid load factor");
      }

      if (capacity > 2.147483647E9) {
         capacity = 2.147483647E9;
      }

      return createWithCapacity((int)capacity, loadFactor);
   }

   protected static int getHash(final long key) {
      return (int)HashUtil.mix(key);
   }

   public final float getLoadFactor() {
      return this.loadFactor;
   }

   protected static ConcurrentChainedLong2IntHashTable.TableEntry getAtIndexAcquire(
      final ConcurrentChainedLong2IntHashTable.TableEntry[] table, final int index
   ) {
      return (ConcurrentChainedLong2IntHashTable.TableEntry)ConcurrentChainedLong2IntHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .getAcquire((ConcurrentChainedLong2IntHashTable.TableEntry[])table, (int)index);
   }

   protected static void setAtIndexRelease(
      final ConcurrentChainedLong2IntHashTable.TableEntry[] table, final int index, final ConcurrentChainedLong2IntHashTable.TableEntry value
   ) {
      ConcurrentChainedLong2IntHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .setRelease((ConcurrentChainedLong2IntHashTable.TableEntry[])table, (int)index, (ConcurrentChainedLong2IntHashTable.TableEntry)value);
   }

   protected static void setAtIndexVolatile(
      final ConcurrentChainedLong2IntHashTable.TableEntry[] table, final int index, final ConcurrentChainedLong2IntHashTable.TableEntry value
   ) {
      ConcurrentChainedLong2IntHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .setVolatile((ConcurrentChainedLong2IntHashTable.TableEntry[])table, (int)index, (ConcurrentChainedLong2IntHashTable.TableEntry)value);
   }

   protected static ConcurrentChainedLong2IntHashTable.TableEntry compareAndExchangeAtIndexVolatile(
      final ConcurrentChainedLong2IntHashTable.TableEntry[] table,
      final int index,
      final ConcurrentChainedLong2IntHashTable.TableEntry expect,
      final ConcurrentChainedLong2IntHashTable.TableEntry update
   ) {
      return (ConcurrentChainedLong2IntHashTable.TableEntry)ConcurrentChainedLong2IntHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .compareAndExchange(
            (ConcurrentChainedLong2IntHashTable.TableEntry[])table,
            (int)index,
            (ConcurrentChainedLong2IntHashTable.TableEntry)expect,
            (ConcurrentChainedLong2IntHashTable.TableEntry)update
         );
   }

   protected ConcurrentChainedLong2IntHashTable.TableEntry[] fetchNewTable(final ConcurrentChainedLong2IntHashTable.TableEntry[] expectedCurr) {
      ConcurrentChainedLong2IntHashTable.TableEntry[] candidate = this.nextTable;
      ConcurrentChainedLong2IntHashTable.TableEntry[] current = this.table;
      return expectedCurr == current ? candidate : current;
   }

   protected final ConcurrentChainedLong2IntHashTable.TableEntry getNode(final long key) {
      int hash = (int)HashUtil.mix(key);
      ConcurrentChainedLong2IntHashTable.TableEntry[] table = this.table;

      while (true) {
         ConcurrentChainedLong2IntHashTable.TableEntry node = getAtIndexAcquire(table, hash & table.length - 1);
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

   public int get(final long key) {
      ConcurrentChainedLong2IntHashTable.TableEntry node = this.getNode(key);
      return node == null ? 0 : node.getValueVolatile();
   }

   public int getOrDefault(final long key, final int defaultValue) {
      ConcurrentChainedLong2IntHashTable.TableEntry node = this.getNode(key);
      return node == null ? defaultValue : node.getValueVolatile();
   }

   public boolean containsKey(final long key) {
      return this.getNode(key) != null;
   }

   public boolean containsValue(final int value) {
      ConcurrentChainedLong2IntHashTable.NodeIterator iterator = new ConcurrentChainedLong2IntHashTable.NodeIterator(this);

      ConcurrentChainedLong2IntHashTable.TableEntry node;
      while ((node = iterator.findNext()) != null) {
         int nodeValue = node.getValueAcquire();
         if (value == nodeValue) {
            return true;
         }
      }

      return false;
   }

   public boolean contains(final long key, final int value) {
      ConcurrentChainedLong2IntHashTable.TableEntry node = this.getNode(key);
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

      ConcurrentChainedLong2IntHashTable.TableEntry[] newTable = new ConcurrentChainedLong2IntHashTable.TableEntry[capacity];
      ConcurrentChainedLong2IntHashTable.TableEntry[] oldTable = this.table;
      this.nextTable = newTable;
      int capOldShift = IntegerUtil.floorLog2(oldTable.length);
      int capDiffShift = IntegerUtil.floorLog2(capacity) - capOldShift;
      if (capDiffShift == 0) {
         throw new IllegalStateException("Resizing to same size");
      }

      ConcurrentChainedLong2IntHashTable.TableEntry[] work = new ConcurrentChainedLong2IntHashTable.TableEntry[1 << capDiffShift];
      int i = 0;

      for (int len = oldTable.length; i < len; i++) {
         ConcurrentChainedLong2IntHashTable.TableEntry binNode = getAtIndexAcquire(oldTable, i);

         while (binNode != null || null != (binNode = compareAndExchangeAtIndexVolatile(oldTable, i, null, RESIZE_NODE))) {
            synchronized (binNode) {
               if (binNode == (binNode = getAtIndexAcquire(oldTable, i))) {
                  ConcurrentChainedLong2IntHashTable.TableEntry next = binNode.getNextPlain();
                  if (next == null) {
                     newTable[(int)HashUtil.mix(binNode.key) & capacity - 1] = binNode;
                  } else {
                     Arrays.fill(work, null);

                     for (ConcurrentChainedLong2IntHashTable.TableEntry curr = binNode; curr != null; curr = curr.getNextPlain()) {
                        int newTableIdx = (int)HashUtil.mix(curr.key) & capacity - 1;
                        int workIdx = newTableIdx >>> capOldShift;
                        ConcurrentChainedLong2IntHashTable.TableEntry replace = new ConcurrentChainedLong2IntHashTable.TableEntry(
                           curr.key, curr.getValuePlain()
                        );
                        ConcurrentChainedLong2IntHashTable.TableEntry workNode = work[workIdx];
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

   public int put(final long key, final int value) {
      int hash = (int)HashUtil.mix(key);
      ConcurrentChainedLong2IntHashTable.TableEntry[] table = this.table;

      label50:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedLong2IntHashTable.TableEntry node = getAtIndexAcquire(table, index);

         while (
            node != null
               || null != (node = compareAndExchangeAtIndexVolatile(table, index, null, new ConcurrentChainedLong2IntHashTable.TableEntry(key, value)))
         ) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label50;
            }

            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedLong2IntHashTable.TableEntry prev = null;

                  while (node != null) {
                     if (node.key == key) {
                        int ret = node.getValuePlain();
                        node.setValueVolatile(value);
                        return ret;
                     }

                     prev = node;
                     node = node.getNextPlain();
                  }

                  prev.setNextRelease(new ConcurrentChainedLong2IntHashTable.TableEntry(key, value));
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

   public int putIfAbsent(final long key, final int value) {
      int hash = (int)HashUtil.mix(key);
      ConcurrentChainedLong2IntHashTable.TableEntry[] table = this.table;

      label58:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedLong2IntHashTable.TableEntry node = getAtIndexAcquire(table, index);

         while (
            node != null
               || null != (node = compareAndExchangeAtIndexVolatile(table, index, null, new ConcurrentChainedLong2IntHashTable.TableEntry(key, value)))
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

               ConcurrentChainedLong2IntHashTable.TableEntry prev = null;

               while (node != null) {
                  if (node.key == key) {
                     return node.getValuePlain();
                  }

                  prev = node;
                  node = node.getNextPlain();
               }

               prev.setNextRelease(new ConcurrentChainedLong2IntHashTable.TableEntry(key, value));
            }

            this.addSize(1L);
            return 0;
         }

         this.addSize(1L);
         return 0;
      }
   }

   public int replace(final long key, final int value) {
      int hash = (int)HashUtil.mix(key);
      ConcurrentChainedLong2IntHashTable.TableEntry[] table = this.table;

      label45:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedLong2IntHashTable.TableEntry node = getAtIndexAcquire(table, index);

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

   public int replace(final long key, final int expect, final int update) {
      int hash = (int)HashUtil.mix(key);
      ConcurrentChainedLong2IntHashTable.TableEntry[] table = this.table;

      label51:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedLong2IntHashTable.TableEntry node = getAtIndexAcquire(table, index);

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

   public int remove(final long key) {
      int hash = (int)HashUtil.mix(key);
      ConcurrentChainedLong2IntHashTable.TableEntry[] table = this.table;

      boolean removed;
      int ret;
      label53:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedLong2IntHashTable.TableEntry node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label53;
            }

            removed = false;
            ret = 0;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedLong2IntHashTable.TableEntry prev = null;

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

   public int remove(final long key, final int expect) {
      int hash = (int)HashUtil.mix(key);
      ConcurrentChainedLong2IntHashTable.TableEntry[] table = this.table;

      boolean removed;
      int ret;
      label55:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedLong2IntHashTable.TableEntry node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label55;
            }

            removed = false;
            ret = 0;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedLong2IntHashTable.TableEntry prev = null;

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

   public int removeIf(final long key, final ConcurrentChainedLong2IntHashTable.RemoveIfPredicate predicate) {
      Objects.requireNonNull(predicate, "Predicate may not be null");
      int hash = (int)HashUtil.mix(key);
      ConcurrentChainedLong2IntHashTable.TableEntry[] table = this.table;

      boolean removed;
      int ret;
      label55:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedLong2IntHashTable.TableEntry node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label55;
            }

            removed = false;
            ret = 0;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedLong2IntHashTable.TableEntry prev = null;

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

   public int addTo(final long key, final int increment, final int defaultValue) {
      int hash = (int)HashUtil.mix(key);
      ConcurrentChainedLong2IntHashTable.TableEntry[] table = this.table;

      label50:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedLong2IntHashTable.TableEntry node = getAtIndexAcquire(table, index);

         while (
            node != null
               || null != (node = compareAndExchangeAtIndexVolatile(table, index, null, new ConcurrentChainedLong2IntHashTable.TableEntry(key, defaultValue)))
         ) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label50;
            }

            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedLong2IntHashTable.TableEntry prev = null;

                  while (node != null) {
                     if (node.key == key) {
                        int ret = node.getValuePlain() + increment;
                        node.setValueVolatile(ret);
                        return ret;
                     }

                     prev = node;
                     node = node.getNextPlain();
                  }

                  prev.setNextRelease(new ConcurrentChainedLong2IntHashTable.TableEntry(key, defaultValue));
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

   public int decFrom(final long key, final int decrement, final int threshold) {
      int hash = (int)HashUtil.mix(key);
      ConcurrentChainedLong2IntHashTable.TableEntry[] table = this.table;

      boolean removed;
      boolean found;
      int ret;
      label60:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedLong2IntHashTable.TableEntry node = getAtIndexAcquire(table, index);

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
                  ConcurrentChainedLong2IntHashTable.TableEntry prev = null;

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
      ConcurrentChainedLong2IntHashTable.NodeIterator nodeIterator = new ConcurrentChainedLong2IntHashTable.NodeIterator(this);

      ConcurrentChainedLong2IntHashTable.TableEntry node;
      while ((node = nodeIterator.findNext()) != null) {
         this.remove(node.key);
      }
   }

   public BaseObjectIterator<ConcurrentChainedLong2IntHashTable.TableEntry> entryIterator() {
      return new ConcurrentChainedLong2IntHashTable.EntryNodeIterator(this);
   }

   public final BaseObjectIterator<ConcurrentChainedLong2IntHashTable.TableEntry> iterator() {
      return this.entryIterator();
   }

   public BaseLongIterator keyIterator() {
      return new ConcurrentChainedLong2IntHashTable.KeyIterator(this);
   }

   public BaseIntIterator valueIterator() {
      return new ConcurrentChainedLong2IntHashTable.ValueIterator(this);
   }

   public Set<ConcurrentChainedLong2IntHashTable.TableEntry> entrySet() {
      ConcurrentChainedLong2IntHashTable.EntrySet entrySet = this.entrySet;
      return entrySet != null ? entrySet : (this.entrySet = new ConcurrentChainedLong2IntHashTable.EntrySet(this));
   }

   protected static final class EntryNodeIterator extends BaseObjectIterator<ConcurrentChainedLong2IntHashTable.TableEntry> {
      protected ConcurrentChainedLong2IntHashTable.TableEntry lastReturned;
      protected ConcurrentChainedLong2IntHashTable.TableEntry nextToReturn;
      protected final ConcurrentChainedLong2IntHashTable.NodeIterator nodeIterator;

      protected EntryNodeIterator(final ConcurrentChainedLong2IntHashTable map) {
         super();
         this.nodeIterator = new ConcurrentChainedLong2IntHashTable.NodeIterator(map);
      }

      @Override
      public boolean hasNext() {
         return this.nextToReturn != null ? true : (this.nextToReturn = this.nodeIterator.findNext()) != null;
      }

      public ConcurrentChainedLong2IntHashTable.TableEntry next() throws NoSuchElementException {
         ConcurrentChainedLong2IntHashTable.TableEntry ret = this.nextToReturn;
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
         ConcurrentChainedLong2IntHashTable.TableEntry lastReturned = this.lastReturned;
         if (lastReturned == null) {
            throw new NoSuchElementException();
         }

         this.lastReturned = null;
         this.nodeIterator.map.remove(lastReturned.key);
      }
   }

   protected static class EntrySet extends BaseMapSet<ConcurrentChainedLong2IntHashTable.TableEntry> {
      protected final ConcurrentChainedLong2IntHashTable map;

      protected EntrySet(final ConcurrentChainedLong2IntHashTable map) {
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
         if (value instanceof ConcurrentChainedLong2IntHashTable.TableEntry entry) {
            long entryKey = entry.getKey();
            int entryValue = entry.getValue();
            return this.map.contains(entryKey, entryValue);
         } else {
            return false;
         }
      }

      @Override
      protected List<ConcurrentChainedLong2IntHashTable.TableEntry> asList() {
         List<ConcurrentChainedLong2IntHashTable.TableEntry> ret = new ArrayList<>(this.map.size());

         for (ConcurrentChainedLong2IntHashTable.TableEntry element : this) {
            ret.add(element);
         }

         return ret;
      }

      @Override
      public BaseObjectIterator<ConcurrentChainedLong2IntHashTable.TableEntry> iterator() {
         return this.map.entryIterator();
      }
   }

   protected static final class KeyIterator extends BaseLongIterator {
      private final ConcurrentChainedLong2IntHashTable.EntryNodeIterator iterator;

      public KeyIterator(final ConcurrentChainedLong2IntHashTable map) {
         super();
         this.iterator = new ConcurrentChainedLong2IntHashTable.EntryNodeIterator(map);
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
         return this.iterator.next().key;
      }
   }

   protected static class NodeIterator {
      protected final ConcurrentChainedLong2IntHashTable map;
      private ConcurrentChainedLong2IntHashTable.TableEntry[] currentTable;
      private ConcurrentChainedLong2IntHashTable.NodeIterator.ResizeChain resizeChain;
      private ConcurrentChainedLong2IntHashTable.TableEntry last;
      private int nextBin;
      private int increment;

      protected NodeIterator(final ConcurrentChainedLong2IntHashTable map) {
         super();
         this.map = map;
         this.currentTable = map.table;
         this.increment = 1;
      }

      private ConcurrentChainedLong2IntHashTable.TableEntry[] pullResizeChain(final int index) {
         ConcurrentChainedLong2IntHashTable.NodeIterator.ResizeChain resizeChain = this.resizeChain;
         if (resizeChain == null) {
            this.currentTable = null;
            return null;
         }

         ConcurrentChainedLong2IntHashTable.NodeIterator.ResizeChain prevChain = resizeChain.prev;
         this.resizeChain = prevChain;
         if (prevChain == null) {
            this.currentTable = null;
            return null;
         }

         ConcurrentChainedLong2IntHashTable.TableEntry[] newTable = prevChain.table;
         int newIdx = index & newTable.length - 1;
         ConcurrentChainedLong2IntHashTable.NodeIterator.ResizeChain nextPrevChain = prevChain.prev;
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

      private ConcurrentChainedLong2IntHashTable.TableEntry[] pushResizeChain(final ConcurrentChainedLong2IntHashTable.TableEntry[] table) {
         ConcurrentChainedLong2IntHashTable.NodeIterator.ResizeChain chain = this.resizeChain;
         if (chain == null) {
            ConcurrentChainedLong2IntHashTable.TableEntry[] nextTable = this.map.fetchNewTable(table);
            ConcurrentChainedLong2IntHashTable.NodeIterator.ResizeChain oldChain = new ConcurrentChainedLong2IntHashTable.NodeIterator.ResizeChain(
               table, null, null
            );
            ConcurrentChainedLong2IntHashTable.NodeIterator.ResizeChain currChain = new ConcurrentChainedLong2IntHashTable.NodeIterator.ResizeChain(
               nextTable, oldChain, null
            );
            oldChain.next = currChain;
            this.increment = table.length;
            this.resizeChain = currChain;
            this.currentTable = nextTable;
            return nextTable;
         } else {
            ConcurrentChainedLong2IntHashTable.NodeIterator.ResizeChain currChain = chain.next;
            if (currChain == null) {
               ConcurrentChainedLong2IntHashTable.TableEntry[] ret = this.map.fetchNewTable(table);
               currChain = new ConcurrentChainedLong2IntHashTable.NodeIterator.ResizeChain(ret, chain, null);
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

      protected final ConcurrentChainedLong2IntHashTable.TableEntry findNext() {
         ConcurrentChainedLong2IntHashTable.TableEntry last = this.last;
         if (last != null) {
            ConcurrentChainedLong2IntHashTable.TableEntry next = last.getNextVolatile();
            if (next != null) {
               this.last = next;
               return next;
            }
         }

         ConcurrentChainedLong2IntHashTable.TableEntry[] table = this.currentTable;
         if (table == null) {
            return null;
         }

         int idx = this.nextBin;
         int increment = this.increment;

         do {
            while (idx < table.length) {
               ConcurrentChainedLong2IntHashTable.TableEntry entry = ConcurrentChainedLong2IntHashTable.getAtIndexAcquire(table, idx);
               if (entry == null) {
                  idx += increment;
               } else {
                  if (entry != ConcurrentChainedLong2IntHashTable.RESIZE_NODE) {
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
         public final ConcurrentChainedLong2IntHashTable.TableEntry[] table;
         public final ConcurrentChainedLong2IntHashTable.NodeIterator.ResizeChain prev;
         public ConcurrentChainedLong2IntHashTable.NodeIterator.ResizeChain next;

         public ResizeChain(
            final ConcurrentChainedLong2IntHashTable.TableEntry[] table,
            final ConcurrentChainedLong2IntHashTable.NodeIterator.ResizeChain prev,
            final ConcurrentChainedLong2IntHashTable.NodeIterator.ResizeChain next
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
      boolean test(final int value);
   }

   public static final class TableEntry {
      private static final VarHandle TABLE_ENTRY_ARRAY_HANDLE = ConcurrentUtil.getArrayHandle(ConcurrentChainedLong2IntHashTable.TableEntry[].class);
      private final long key;
      private volatile int value;
      private static final VarHandle VALUE_HANDLE = ConcurrentUtil.getVarHandle(ConcurrentChainedLong2IntHashTable.TableEntry.class, "value", int.class);
      private volatile ConcurrentChainedLong2IntHashTable.TableEntry next;
      private static final VarHandle NEXT_HANDLE = ConcurrentUtil.getVarHandle(
         ConcurrentChainedLong2IntHashTable.TableEntry.class, "next", ConcurrentChainedLong2IntHashTable.TableEntry.class
      );

      private int getValuePlain() {
         return (int)VALUE_HANDLE.get((ConcurrentChainedLong2IntHashTable.TableEntry)this);
      }

      private int getValueAcquire() {
         return (int)VALUE_HANDLE.getAcquire((ConcurrentChainedLong2IntHashTable.TableEntry)this);
      }

      private int getValueVolatile() {
         return (int)VALUE_HANDLE.getVolatile((ConcurrentChainedLong2IntHashTable.TableEntry)this);
      }

      private void setValuePlain(final int value) {
         VALUE_HANDLE.set((ConcurrentChainedLong2IntHashTable.TableEntry)this, (int)value);
      }

      private void setValueRelease(final int value) {
         VALUE_HANDLE.setRelease((ConcurrentChainedLong2IntHashTable.TableEntry)this, (int)value);
      }

      private void setValueVolatile(final int value) {
         VALUE_HANDLE.setVolatile((ConcurrentChainedLong2IntHashTable.TableEntry)this, (int)value);
      }

      private ConcurrentChainedLong2IntHashTable.TableEntry getNextPlain() {
         return (ConcurrentChainedLong2IntHashTable.TableEntry)NEXT_HANDLE.get((ConcurrentChainedLong2IntHashTable.TableEntry)this);
      }

      private ConcurrentChainedLong2IntHashTable.TableEntry getNextAcquire() {
         return (ConcurrentChainedLong2IntHashTable.TableEntry)NEXT_HANDLE.getAcquire((ConcurrentChainedLong2IntHashTable.TableEntry)this);
      }

      private ConcurrentChainedLong2IntHashTable.TableEntry getNextVolatile() {
         return (ConcurrentChainedLong2IntHashTable.TableEntry)NEXT_HANDLE.getVolatile((ConcurrentChainedLong2IntHashTable.TableEntry)this);
      }

      private void setNextPlain(final ConcurrentChainedLong2IntHashTable.TableEntry next) {
         NEXT_HANDLE.set((ConcurrentChainedLong2IntHashTable.TableEntry)this, (ConcurrentChainedLong2IntHashTable.TableEntry)next);
      }

      private void setNextRelease(final ConcurrentChainedLong2IntHashTable.TableEntry next) {
         NEXT_HANDLE.setRelease((ConcurrentChainedLong2IntHashTable.TableEntry)this, (ConcurrentChainedLong2IntHashTable.TableEntry)next);
      }

      private void setNextVolatile(final ConcurrentChainedLong2IntHashTable.TableEntry next) {
         NEXT_HANDLE.setVolatile((ConcurrentChainedLong2IntHashTable.TableEntry)this, (ConcurrentChainedLong2IntHashTable.TableEntry)next);
      }

      protected TableEntry(final long key, final int value) {
         super();
         this.key = key;
         this.setValuePlain(value);
      }

      public long getKey() {
         return this.key;
      }

      public int getValue() {
         return this.getValueVolatile();
      }
   }

   protected static final class ValueIterator extends BaseIntIterator {
      private final ConcurrentChainedLong2IntHashTable.EntryNodeIterator iterator;

      public ValueIterator(final ConcurrentChainedLong2IntHashTable map) {
         super();
         this.iterator = new ConcurrentChainedLong2IntHashTable.EntryNodeIterator(map);
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
