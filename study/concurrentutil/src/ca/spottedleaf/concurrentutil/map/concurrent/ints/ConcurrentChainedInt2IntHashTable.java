package ca.spottedleaf.concurrentutil.map.concurrent.ints;

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

public class ConcurrentChainedInt2IntHashTable implements Iterable<ConcurrentChainedInt2IntHashTable.TableEntry> {
   private static final ConcurrentChainedInt2IntHashTable.TableEntry RESIZE_NODE = new ConcurrentChainedInt2IntHashTable.TableEntry(0, 0);
   protected static final int DEFAULT_CAPACITY = 16;
   protected static final float DEFAULT_LOAD_FACTOR = 0.75F;
   protected static final int MAXIMUM_CAPACITY = 1073741824;
   protected final AtomicLong size = new AtomicLong();
   protected final float loadFactor;
   protected volatile ConcurrentChainedInt2IntHashTable.TableEntry[] table;
   protected volatile ConcurrentChainedInt2IntHashTable.TableEntry[] nextTable;
   protected static final int THRESHOLD_NO_RESIZE = -1;
   protected static final int THRESHOLD_RESIZING = -2;
   protected volatile int threshold;
   protected static final VarHandle THRESHOLD_HANDLE = ConcurrentUtil.getVarHandle(ConcurrentChainedInt2IntHashTable.class, "threshold", int.class);
   protected ConcurrentChainedInt2IntHashTable.EntrySet entrySet;

   public static int defaultCapacity() {
      return 16;
   }

   public static float defaultLoadFactor() {
      return 0.75F;
   }

   protected final int getThresholdAcquire() {
      return (int)THRESHOLD_HANDLE.getAcquire((ConcurrentChainedInt2IntHashTable)this);
   }

   protected final int getThresholdVolatile() {
      return (int)THRESHOLD_HANDLE.getVolatile((ConcurrentChainedInt2IntHashTable)this);
   }

   protected final void setThresholdPlain(final int threshold) {
      THRESHOLD_HANDLE.set((ConcurrentChainedInt2IntHashTable)this, (int)threshold);
   }

   protected final void setThresholdRelease(final int threshold) {
      THRESHOLD_HANDLE.setRelease((ConcurrentChainedInt2IntHashTable)this, (int)threshold);
   }

   protected final void setThresholdVolatile(final int threshold) {
      THRESHOLD_HANDLE.setVolatile((ConcurrentChainedInt2IntHashTable)this, (int)threshold);
   }

   protected final int compareExchangeThresholdVolatile(final int expect, final int update) {
      return (int)THRESHOLD_HANDLE.compareAndExchange((ConcurrentChainedInt2IntHashTable)this, (int)expect, (int)update);
   }

   public ConcurrentChainedInt2IntHashTable() {
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

   protected ConcurrentChainedInt2IntHashTable(final int capacity, final float loadFactor) {
      super();
      int tableSize = getCapacityFor(capacity);
      if (!(loadFactor <= 0.0) && Float.isFinite(loadFactor)) {
         if (tableSize == 1073741824) {
            this.setThresholdPlain(-1);
         } else {
            this.setThresholdPlain(getTargetThreshold(tableSize, loadFactor));
         }

         this.loadFactor = loadFactor;
         this.nextTable = this.table = new ConcurrentChainedInt2IntHashTable.TableEntry[tableSize];
      } else {
         throw new IllegalArgumentException("Invalid load factor: " + loadFactor);
      }
   }

   public static ConcurrentChainedInt2IntHashTable createWithCapacity(final int capacity) {
      return createWithCapacity(capacity, 0.75F);
   }

   public static ConcurrentChainedInt2IntHashTable createWithCapacity(final int capacity, final float loadFactor) {
      return new ConcurrentChainedInt2IntHashTable(capacity, loadFactor);
   }

   public static ConcurrentChainedInt2IntHashTable createWithExpected(final int expected) {
      return createWithExpected(expected, 0.75F);
   }

   public static ConcurrentChainedInt2IntHashTable createWithExpected(final int expected, final float loadFactor) {
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

   protected static ConcurrentChainedInt2IntHashTable.TableEntry getAtIndexAcquire(final ConcurrentChainedInt2IntHashTable.TableEntry[] table, final int index) {
      return (ConcurrentChainedInt2IntHashTable.TableEntry)ConcurrentChainedInt2IntHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .getAcquire((ConcurrentChainedInt2IntHashTable.TableEntry[])table, (int)index);
   }

   protected static void setAtIndexRelease(
      final ConcurrentChainedInt2IntHashTable.TableEntry[] table, final int index, final ConcurrentChainedInt2IntHashTable.TableEntry value
   ) {
      ConcurrentChainedInt2IntHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .setRelease((ConcurrentChainedInt2IntHashTable.TableEntry[])table, (int)index, (ConcurrentChainedInt2IntHashTable.TableEntry)value);
   }

   protected static void setAtIndexVolatile(
      final ConcurrentChainedInt2IntHashTable.TableEntry[] table, final int index, final ConcurrentChainedInt2IntHashTable.TableEntry value
   ) {
      ConcurrentChainedInt2IntHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .setVolatile((ConcurrentChainedInt2IntHashTable.TableEntry[])table, (int)index, (ConcurrentChainedInt2IntHashTable.TableEntry)value);
   }

   protected static ConcurrentChainedInt2IntHashTable.TableEntry compareAndExchangeAtIndexVolatile(
      final ConcurrentChainedInt2IntHashTable.TableEntry[] table,
      final int index,
      final ConcurrentChainedInt2IntHashTable.TableEntry expect,
      final ConcurrentChainedInt2IntHashTable.TableEntry update
   ) {
      return (ConcurrentChainedInt2IntHashTable.TableEntry)ConcurrentChainedInt2IntHashTable.TableEntry.TABLE_ENTRY_ARRAY_HANDLE
         .compareAndExchange(
            (ConcurrentChainedInt2IntHashTable.TableEntry[])table,
            (int)index,
            (ConcurrentChainedInt2IntHashTable.TableEntry)expect,
            (ConcurrentChainedInt2IntHashTable.TableEntry)update
         );
   }

   protected ConcurrentChainedInt2IntHashTable.TableEntry[] fetchNewTable(final ConcurrentChainedInt2IntHashTable.TableEntry[] expectedCurr) {
      ConcurrentChainedInt2IntHashTable.TableEntry[] candidate = this.nextTable;
      ConcurrentChainedInt2IntHashTable.TableEntry[] current = this.table;
      return expectedCurr == current ? candidate : current;
   }

   protected final ConcurrentChainedInt2IntHashTable.TableEntry getNode(final int key) {
      int hash = HashUtil.mix(key);
      ConcurrentChainedInt2IntHashTable.TableEntry[] table = this.table;

      while (true) {
         ConcurrentChainedInt2IntHashTable.TableEntry node = getAtIndexAcquire(table, hash & table.length - 1);
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

   public int get(final int key) {
      ConcurrentChainedInt2IntHashTable.TableEntry node = this.getNode(key);
      return node == null ? 0 : node.getValueVolatile();
   }

   public int getOrDefault(final int key, final int defaultValue) {
      ConcurrentChainedInt2IntHashTable.TableEntry node = this.getNode(key);
      return node == null ? defaultValue : node.getValueVolatile();
   }

   public boolean containsKey(final int key) {
      return this.getNode(key) != null;
   }

   public boolean containsValue(final int value) {
      ConcurrentChainedInt2IntHashTable.NodeIterator iterator = new ConcurrentChainedInt2IntHashTable.NodeIterator(this);

      ConcurrentChainedInt2IntHashTable.TableEntry node;
      while ((node = iterator.findNext()) != null) {
         int nodeValue = node.getValueAcquire();
         if (value == nodeValue) {
            return true;
         }
      }

      return false;
   }

   public boolean contains(final int key, final int value) {
      ConcurrentChainedInt2IntHashTable.TableEntry node = this.getNode(key);
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

      ConcurrentChainedInt2IntHashTable.TableEntry[] newTable = new ConcurrentChainedInt2IntHashTable.TableEntry[capacity];
      ConcurrentChainedInt2IntHashTable.TableEntry[] oldTable = this.table;
      this.nextTable = newTable;
      int capOldShift = IntegerUtil.floorLog2(oldTable.length);
      int capDiffShift = IntegerUtil.floorLog2(capacity) - capOldShift;
      if (capDiffShift == 0) {
         throw new IllegalStateException("Resizing to same size");
      }

      ConcurrentChainedInt2IntHashTable.TableEntry[] work = new ConcurrentChainedInt2IntHashTable.TableEntry[1 << capDiffShift];
      int i = 0;

      for (int len = oldTable.length; i < len; i++) {
         ConcurrentChainedInt2IntHashTable.TableEntry binNode = getAtIndexAcquire(oldTable, i);

         while (binNode != null || null != (binNode = compareAndExchangeAtIndexVolatile(oldTable, i, null, RESIZE_NODE))) {
            synchronized (binNode) {
               if (binNode == (binNode = getAtIndexAcquire(oldTable, i))) {
                  ConcurrentChainedInt2IntHashTable.TableEntry next = binNode.getNextPlain();
                  if (next == null) {
                     newTable[HashUtil.mix(binNode.key) & capacity - 1] = binNode;
                  } else {
                     Arrays.fill(work, null);

                     for (ConcurrentChainedInt2IntHashTable.TableEntry curr = binNode; curr != null; curr = curr.getNextPlain()) {
                        int newTableIdx = HashUtil.mix(curr.key) & capacity - 1;
                        int workIdx = newTableIdx >>> capOldShift;
                        ConcurrentChainedInt2IntHashTable.TableEntry replace = new ConcurrentChainedInt2IntHashTable.TableEntry(curr.key, curr.getValuePlain());
                        ConcurrentChainedInt2IntHashTable.TableEntry workNode = work[workIdx];
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

   public int put(final int key, final int value) {
      int hash = HashUtil.mix(key);
      ConcurrentChainedInt2IntHashTable.TableEntry[] table = this.table;

      label50:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedInt2IntHashTable.TableEntry node = getAtIndexAcquire(table, index);

         while (
            node != null
               || null != (node = compareAndExchangeAtIndexVolatile(table, index, null, new ConcurrentChainedInt2IntHashTable.TableEntry(key, value)))
         ) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label50;
            }

            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedInt2IntHashTable.TableEntry prev = null;

                  while (node != null) {
                     if (node.key == key) {
                        int ret = node.getValuePlain();
                        node.setValueVolatile(value);
                        return ret;
                     }

                     prev = node;
                     node = node.getNextPlain();
                  }

                  prev.setNextRelease(new ConcurrentChainedInt2IntHashTable.TableEntry(key, value));
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

   public int putIfAbsent(final int key, final int value) {
      int hash = HashUtil.mix(key);
      ConcurrentChainedInt2IntHashTable.TableEntry[] table = this.table;

      label58:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedInt2IntHashTable.TableEntry node = getAtIndexAcquire(table, index);

         while (
            node != null
               || null != (node = compareAndExchangeAtIndexVolatile(table, index, null, new ConcurrentChainedInt2IntHashTable.TableEntry(key, value)))
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

               ConcurrentChainedInt2IntHashTable.TableEntry prev = null;

               while (node != null) {
                  if (node.key == key) {
                     return node.getValuePlain();
                  }

                  prev = node;
                  node = node.getNextPlain();
               }

               prev.setNextRelease(new ConcurrentChainedInt2IntHashTable.TableEntry(key, value));
            }

            this.addSize(1L);
            return 0;
         }

         this.addSize(1L);
         return 0;
      }
   }

   public int replace(final int key, final int value) {
      int hash = HashUtil.mix(key);
      ConcurrentChainedInt2IntHashTable.TableEntry[] table = this.table;

      label45:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedInt2IntHashTable.TableEntry node = getAtIndexAcquire(table, index);

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

   public int replace(final int key, final int expect, final int update) {
      int hash = HashUtil.mix(key);
      ConcurrentChainedInt2IntHashTable.TableEntry[] table = this.table;

      label51:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedInt2IntHashTable.TableEntry node = getAtIndexAcquire(table, index);

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

   public int remove(final int key) {
      int hash = HashUtil.mix(key);
      ConcurrentChainedInt2IntHashTable.TableEntry[] table = this.table;

      boolean removed;
      int ret;
      label53:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedInt2IntHashTable.TableEntry node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label53;
            }

            removed = false;
            ret = 0;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedInt2IntHashTable.TableEntry prev = null;

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

   public int remove(final int key, final int expect) {
      int hash = HashUtil.mix(key);
      ConcurrentChainedInt2IntHashTable.TableEntry[] table = this.table;

      boolean removed;
      int ret;
      label55:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedInt2IntHashTable.TableEntry node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label55;
            }

            removed = false;
            ret = 0;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedInt2IntHashTable.TableEntry prev = null;

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

   public int removeIf(final int key, final ConcurrentChainedInt2IntHashTable.RemoveIfPredicate predicate) {
      Objects.requireNonNull(predicate, "Predicate may not be null");
      int hash = HashUtil.mix(key);
      ConcurrentChainedInt2IntHashTable.TableEntry[] table = this.table;

      boolean removed;
      int ret;
      label55:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedInt2IntHashTable.TableEntry node = getAtIndexAcquire(table, index);

         while (node != null) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label55;
            }

            removed = false;
            ret = 0;
            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedInt2IntHashTable.TableEntry prev = null;

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

   public int addTo(final int key, final int increment, final int defaultValue) {
      int hash = HashUtil.mix(key);
      ConcurrentChainedInt2IntHashTable.TableEntry[] table = this.table;

      label50:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedInt2IntHashTable.TableEntry node = getAtIndexAcquire(table, index);

         while (
            node != null
               || null != (node = compareAndExchangeAtIndexVolatile(table, index, null, new ConcurrentChainedInt2IntHashTable.TableEntry(key, defaultValue)))
         ) {
            if (node == RESIZE_NODE) {
               table = this.fetchNewTable(table);
               continue label50;
            }

            synchronized (node) {
               if (node == (node = getAtIndexAcquire(table, index))) {
                  ConcurrentChainedInt2IntHashTable.TableEntry prev = null;

                  while (node != null) {
                     if (node.key == key) {
                        int ret = node.getValuePlain() + increment;
                        node.setValueVolatile(ret);
                        return ret;
                     }

                     prev = node;
                     node = node.getNextPlain();
                  }

                  prev.setNextRelease(new ConcurrentChainedInt2IntHashTable.TableEntry(key, defaultValue));
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

   public int decFrom(final int key, final int decrement, final int threshold) {
      int hash = HashUtil.mix(key);
      ConcurrentChainedInt2IntHashTable.TableEntry[] table = this.table;

      boolean removed;
      boolean found;
      int ret;
      label60:
      while (true) {
         int index = hash & table.length - 1;
         ConcurrentChainedInt2IntHashTable.TableEntry node = getAtIndexAcquire(table, index);

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
                  ConcurrentChainedInt2IntHashTable.TableEntry prev = null;

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
      ConcurrentChainedInt2IntHashTable.NodeIterator nodeIterator = new ConcurrentChainedInt2IntHashTable.NodeIterator(this);

      ConcurrentChainedInt2IntHashTable.TableEntry node;
      while ((node = nodeIterator.findNext()) != null) {
         this.remove(node.key);
      }
   }

   public BaseObjectIterator<ConcurrentChainedInt2IntHashTable.TableEntry> entryIterator() {
      return new ConcurrentChainedInt2IntHashTable.EntryNodeIterator(this);
   }

   public final BaseObjectIterator<ConcurrentChainedInt2IntHashTable.TableEntry> iterator() {
      return this.entryIterator();
   }

   public BaseIntIterator keyIterator() {
      return new ConcurrentChainedInt2IntHashTable.KeyIterator(this);
   }

   public BaseIntIterator valueIterator() {
      return new ConcurrentChainedInt2IntHashTable.ValueIterator(this);
   }

   public Set<ConcurrentChainedInt2IntHashTable.TableEntry> entrySet() {
      ConcurrentChainedInt2IntHashTable.EntrySet entrySet = this.entrySet;
      return entrySet != null ? entrySet : (this.entrySet = new ConcurrentChainedInt2IntHashTable.EntrySet(this));
   }

   protected static final class EntryNodeIterator extends BaseObjectIterator<ConcurrentChainedInt2IntHashTable.TableEntry> {
      protected ConcurrentChainedInt2IntHashTable.TableEntry lastReturned;
      protected ConcurrentChainedInt2IntHashTable.TableEntry nextToReturn;
      protected final ConcurrentChainedInt2IntHashTable.NodeIterator nodeIterator;

      protected EntryNodeIterator(final ConcurrentChainedInt2IntHashTable map) {
         super();
         this.nodeIterator = new ConcurrentChainedInt2IntHashTable.NodeIterator(map);
      }

      @Override
      public boolean hasNext() {
         return this.nextToReturn != null ? true : (this.nextToReturn = this.nodeIterator.findNext()) != null;
      }

      public ConcurrentChainedInt2IntHashTable.TableEntry next() throws NoSuchElementException {
         ConcurrentChainedInt2IntHashTable.TableEntry ret = this.nextToReturn;
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
         ConcurrentChainedInt2IntHashTable.TableEntry lastReturned = this.lastReturned;
         if (lastReturned == null) {
            throw new NoSuchElementException();
         }

         this.lastReturned = null;
         this.nodeIterator.map.remove(lastReturned.key);
      }
   }

   protected static class EntrySet extends BaseMapSet<ConcurrentChainedInt2IntHashTable.TableEntry> {
      protected final ConcurrentChainedInt2IntHashTable map;

      protected EntrySet(final ConcurrentChainedInt2IntHashTable map) {
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
         if (value instanceof ConcurrentChainedInt2IntHashTable.TableEntry entry) {
            int entryKey = entry.getKey();
            int entryValue = entry.getValue();
            return this.map.contains(entryKey, entryValue);
         } else {
            return false;
         }
      }

      @Override
      protected List<ConcurrentChainedInt2IntHashTable.TableEntry> asList() {
         List<ConcurrentChainedInt2IntHashTable.TableEntry> ret = new ArrayList<>(this.map.size());

         for (ConcurrentChainedInt2IntHashTable.TableEntry element : this) {
            ret.add(element);
         }

         return ret;
      }

      @Override
      public BaseObjectIterator<ConcurrentChainedInt2IntHashTable.TableEntry> iterator() {
         return this.map.entryIterator();
      }
   }

   protected static final class KeyIterator extends BaseIntIterator {
      private final ConcurrentChainedInt2IntHashTable.EntryNodeIterator iterator;

      public KeyIterator(final ConcurrentChainedInt2IntHashTable map) {
         super();
         this.iterator = new ConcurrentChainedInt2IntHashTable.EntryNodeIterator(map);
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
      protected final ConcurrentChainedInt2IntHashTable map;
      private ConcurrentChainedInt2IntHashTable.TableEntry[] currentTable;
      private ConcurrentChainedInt2IntHashTable.NodeIterator.ResizeChain resizeChain;
      private ConcurrentChainedInt2IntHashTable.TableEntry last;
      private int nextBin;
      private int increment;

      protected NodeIterator(final ConcurrentChainedInt2IntHashTable map) {
         super();
         this.map = map;
         this.currentTable = map.table;
         this.increment = 1;
      }

      private ConcurrentChainedInt2IntHashTable.TableEntry[] pullResizeChain(final int index) {
         ConcurrentChainedInt2IntHashTable.NodeIterator.ResizeChain resizeChain = this.resizeChain;
         if (resizeChain == null) {
            this.currentTable = null;
            return null;
         }

         ConcurrentChainedInt2IntHashTable.NodeIterator.ResizeChain prevChain = resizeChain.prev;
         this.resizeChain = prevChain;
         if (prevChain == null) {
            this.currentTable = null;
            return null;
         }

         ConcurrentChainedInt2IntHashTable.TableEntry[] newTable = prevChain.table;
         int newIdx = index & newTable.length - 1;
         ConcurrentChainedInt2IntHashTable.NodeIterator.ResizeChain nextPrevChain = prevChain.prev;
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

      private ConcurrentChainedInt2IntHashTable.TableEntry[] pushResizeChain(final ConcurrentChainedInt2IntHashTable.TableEntry[] table) {
         ConcurrentChainedInt2IntHashTable.NodeIterator.ResizeChain chain = this.resizeChain;
         if (chain == null) {
            ConcurrentChainedInt2IntHashTable.TableEntry[] nextTable = this.map.fetchNewTable(table);
            ConcurrentChainedInt2IntHashTable.NodeIterator.ResizeChain oldChain = new ConcurrentChainedInt2IntHashTable.NodeIterator.ResizeChain(
               table, null, null
            );
            ConcurrentChainedInt2IntHashTable.NodeIterator.ResizeChain currChain = new ConcurrentChainedInt2IntHashTable.NodeIterator.ResizeChain(
               nextTable, oldChain, null
            );
            oldChain.next = currChain;
            this.increment = table.length;
            this.resizeChain = currChain;
            this.currentTable = nextTable;
            return nextTable;
         } else {
            ConcurrentChainedInt2IntHashTable.NodeIterator.ResizeChain currChain = chain.next;
            if (currChain == null) {
               ConcurrentChainedInt2IntHashTable.TableEntry[] ret = this.map.fetchNewTable(table);
               currChain = new ConcurrentChainedInt2IntHashTable.NodeIterator.ResizeChain(ret, chain, null);
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

      protected final ConcurrentChainedInt2IntHashTable.TableEntry findNext() {
         ConcurrentChainedInt2IntHashTable.TableEntry last = this.last;
         if (last != null) {
            ConcurrentChainedInt2IntHashTable.TableEntry next = last.getNextVolatile();
            if (next != null) {
               this.last = next;
               return next;
            }
         }

         ConcurrentChainedInt2IntHashTable.TableEntry[] table = this.currentTable;
         if (table == null) {
            return null;
         }

         int idx = this.nextBin;
         int increment = this.increment;

         do {
            while (idx < table.length) {
               ConcurrentChainedInt2IntHashTable.TableEntry entry = ConcurrentChainedInt2IntHashTable.getAtIndexAcquire(table, idx);
               if (entry == null) {
                  idx += increment;
               } else {
                  if (entry != ConcurrentChainedInt2IntHashTable.RESIZE_NODE) {
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
         public final ConcurrentChainedInt2IntHashTable.TableEntry[] table;
         public final ConcurrentChainedInt2IntHashTable.NodeIterator.ResizeChain prev;
         public ConcurrentChainedInt2IntHashTable.NodeIterator.ResizeChain next;

         public ResizeChain(
            final ConcurrentChainedInt2IntHashTable.TableEntry[] table,
            final ConcurrentChainedInt2IntHashTable.NodeIterator.ResizeChain prev,
            final ConcurrentChainedInt2IntHashTable.NodeIterator.ResizeChain next
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
      private static final VarHandle TABLE_ENTRY_ARRAY_HANDLE = ConcurrentUtil.getArrayHandle(ConcurrentChainedInt2IntHashTable.TableEntry[].class);
      private final int key;
      private volatile int value;
      private static final VarHandle VALUE_HANDLE = ConcurrentUtil.getVarHandle(ConcurrentChainedInt2IntHashTable.TableEntry.class, "value", int.class);
      private volatile ConcurrentChainedInt2IntHashTable.TableEntry next;
      private static final VarHandle NEXT_HANDLE = ConcurrentUtil.getVarHandle(
         ConcurrentChainedInt2IntHashTable.TableEntry.class, "next", ConcurrentChainedInt2IntHashTable.TableEntry.class
      );

      private int getValuePlain() {
         return (int)VALUE_HANDLE.get((ConcurrentChainedInt2IntHashTable.TableEntry)this);
      }

      private int getValueAcquire() {
         return (int)VALUE_HANDLE.getAcquire((ConcurrentChainedInt2IntHashTable.TableEntry)this);
      }

      private int getValueVolatile() {
         return (int)VALUE_HANDLE.getVolatile((ConcurrentChainedInt2IntHashTable.TableEntry)this);
      }

      private void setValuePlain(final int value) {
         VALUE_HANDLE.set((ConcurrentChainedInt2IntHashTable.TableEntry)this, (int)value);
      }

      private void setValueRelease(final int value) {
         VALUE_HANDLE.setRelease((ConcurrentChainedInt2IntHashTable.TableEntry)this, (int)value);
      }

      private void setValueVolatile(final int value) {
         VALUE_HANDLE.setVolatile((ConcurrentChainedInt2IntHashTable.TableEntry)this, (int)value);
      }

      private ConcurrentChainedInt2IntHashTable.TableEntry getNextPlain() {
         return (ConcurrentChainedInt2IntHashTable.TableEntry)NEXT_HANDLE.get((ConcurrentChainedInt2IntHashTable.TableEntry)this);
      }

      private ConcurrentChainedInt2IntHashTable.TableEntry getNextAcquire() {
         return (ConcurrentChainedInt2IntHashTable.TableEntry)NEXT_HANDLE.getAcquire((ConcurrentChainedInt2IntHashTable.TableEntry)this);
      }

      private ConcurrentChainedInt2IntHashTable.TableEntry getNextVolatile() {
         return (ConcurrentChainedInt2IntHashTable.TableEntry)NEXT_HANDLE.getVolatile((ConcurrentChainedInt2IntHashTable.TableEntry)this);
      }

      private void setNextPlain(final ConcurrentChainedInt2IntHashTable.TableEntry next) {
         NEXT_HANDLE.set((ConcurrentChainedInt2IntHashTable.TableEntry)this, (ConcurrentChainedInt2IntHashTable.TableEntry)next);
      }

      private void setNextRelease(final ConcurrentChainedInt2IntHashTable.TableEntry next) {
         NEXT_HANDLE.setRelease((ConcurrentChainedInt2IntHashTable.TableEntry)this, (ConcurrentChainedInt2IntHashTable.TableEntry)next);
      }

      private void setNextVolatile(final ConcurrentChainedInt2IntHashTable.TableEntry next) {
         NEXT_HANDLE.setVolatile((ConcurrentChainedInt2IntHashTable.TableEntry)this, (ConcurrentChainedInt2IntHashTable.TableEntry)next);
      }

      protected TableEntry(final int key, final int value) {
         super();
         this.key = key;
         this.setValuePlain(value);
      }

      public int getKey() {
         return this.key;
      }

      public int getValue() {
         return this.getValueVolatile();
      }
   }

   protected static final class ValueIterator extends BaseIntIterator {
      private final ConcurrentChainedInt2IntHashTable.EntryNodeIterator iterator;

      public ValueIterator(final ConcurrentChainedInt2IntHashTable map) {
         super();
         this.iterator = new ConcurrentChainedInt2IntHashTable.EntryNodeIterator(map);
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
