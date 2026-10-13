package ca.spottedleaf.concurrentutil.map.concurrent.objects;

import ca.spottedleaf.common.util.*;
import ca.spottedleaf.concurrentutil.collection.iterator.*;
import ca.spottedleaf.concurrentutil.map.BaseMapCollection;
import ca.spottedleaf.concurrentutil.map.BaseMapSet;
import ca.spottedleaf.concurrentutil.util.*;
import java.lang.invoke.VarHandle;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/**
 * Concurrent hashtable implementation supporting mapping arbitrary non-null {@code K} values onto non-null {@code int}
 * values with support for multiple writer and multiple reader threads.
 *
 * <h2>Happens-before relationship</h2>
 * <p>
 * As with {@link java.util.concurrent.ConcurrentMap}, there is a happens-before relationship between actions in one thread
 * prior to writing to the map and access to the results of those actions in another thread.
 * </p>
 *
 * <h2>Atomicity of functional methods</h2>
 * <p>
 * Functional methods are functions declared in this class which possibly perform a write (remove, replace, or modify)
 * to an entry in this map as a result of invoking a function on an input parameter.
 * Functional methods will be performed atomically, that is, the input parameter is guaranteed to only be invoked at most
 * once per function call. However, the consequence of this behaviour is that a critical lock for a bin entry is held, which
 * means that if the input parameter invocation makes additional calls to write into this hash table that the result
 * is undefined and deadlock-prone.
 * </p>
 *
 * @param <K> The type for keys in this map
 * @see java.util.concurrent.ConcurrentMap
 */
public class ConcurrentChainedObject2IntHashTable<K> implements Iterable<ConcurrentChainedObject2IntHashTable.TableEntry<K>> {

    private static final TableEntry<?> RESIZE_NODE = new TableEntry<>(null, ((int)0));

    protected static final int DEFAULT_CAPACITY = 16;
    protected static final float DEFAULT_LOAD_FACTOR = 0.75f;
    protected static final int MAXIMUM_CAPACITY = Integer.MIN_VALUE >>> 1;

    public static int defaultCapacity() {
        return DEFAULT_CAPACITY;
    }

    public static float defaultLoadFactor() {
        return DEFAULT_LOAD_FACTOR;
    }

    protected final AtomicLong size = new AtomicLong();
    protected final float loadFactor;

    protected volatile TableEntry<K>[] table;
    protected volatile TableEntry<K>[] nextTable;

    protected static final int THRESHOLD_NO_RESIZE = -1;
    protected static final int THRESHOLD_RESIZING  = -2;
    protected volatile int threshold;
    protected static final VarHandle THRESHOLD_HANDLE = ConcurrentUtil.getVarHandle(ConcurrentChainedObject2IntHashTable.class, "threshold", int.class);

    protected final int getThresholdAcquire() {
        return (int)THRESHOLD_HANDLE.getAcquire(this);
    }

    protected final int getThresholdVolatile() {
        return (int)THRESHOLD_HANDLE.getVolatile(this);
    }

    protected final void setThresholdPlain(final int threshold) {
        THRESHOLD_HANDLE.set(this, threshold);
    }

    protected final void setThresholdRelease(final int threshold) {
        THRESHOLD_HANDLE.setRelease(this, threshold);
    }

    protected final void setThresholdVolatile(final int threshold) {
        THRESHOLD_HANDLE.setVolatile(this, threshold);
    }

    protected final int compareExchangeThresholdVolatile(final int expect, final int update) {
        return (int)THRESHOLD_HANDLE.compareAndExchange(this, expect, update);
    }

    public ConcurrentChainedObject2IntHashTable() {
        this(DEFAULT_CAPACITY, DEFAULT_LOAD_FACTOR);
    }

    protected static int getTargetThreshold(final int capacity, final float loadFactor) {
        final double ret = (double)capacity * (double)loadFactor;
        if (Double.isInfinite(ret) || ret >= ((double)Integer.MAX_VALUE - 1)) {
            return THRESHOLD_NO_RESIZE;
        }

        return (int)Math.ceil(ret);
    }

    protected static int getCapacityFor(final int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Invalid capacity: " + capacity);
        }
        if (capacity >= MAXIMUM_CAPACITY) {
            return MAXIMUM_CAPACITY;
        }
        return IntegerUtil.roundCeilLog2(capacity);
    }

    protected ConcurrentChainedObject2IntHashTable(final int capacity, final float loadFactor) {
        final int tableSize = getCapacityFor(capacity);

        if (loadFactor <= 0.0 || !Float.isFinite(loadFactor)) {
            throw new IllegalArgumentException("Invalid load factor: " + loadFactor);
        }

        if (tableSize == MAXIMUM_CAPACITY) {
            this.setThresholdPlain(THRESHOLD_NO_RESIZE);
        } else {
            this.setThresholdPlain(getTargetThreshold(tableSize, loadFactor));
        }

        this.loadFactor = loadFactor;
        // noinspection unchecked
        this.nextTable = this.table = (TableEntry<K>[])new TableEntry[tableSize];
    }

    public static <K> ConcurrentChainedObject2IntHashTable<K> createWithCapacity(final int capacity) {
        return createWithCapacity(capacity, DEFAULT_LOAD_FACTOR);
    }

    public static <K> ConcurrentChainedObject2IntHashTable<K> createWithCapacity(final int capacity, final float loadFactor) {
        return new ConcurrentChainedObject2IntHashTable<>(capacity, loadFactor);
    }

    public static <K> ConcurrentChainedObject2IntHashTable<K> createWithExpected(final int expected) {
        return createWithExpected(expected, DEFAULT_LOAD_FACTOR);
    }

    public static <K> ConcurrentChainedObject2IntHashTable<K> createWithExpected(final int expected, final float loadFactor) {
        double capacity = Math.ceil((double)expected / (double)loadFactor);
        if (!Double.isFinite(capacity)) {
            throw new IllegalArgumentException("Invalid load factor");
        }
        if (capacity > (double)Integer.MAX_VALUE) {
            capacity = (double)Integer.MAX_VALUE;
        }

        return createWithCapacity((int)capacity, loadFactor);
    }

    /** must be deterministic given a key */
    // define for subclasses to use
    protected static int getHash(final Object key) {
        return (HashUtil.mix(key.hashCode()));
    }

    /**
     * Returns the load factor associated with this map.
     */
    public final float getLoadFactor() {
        return this.loadFactor;
    }

    protected static <K> TableEntry<K> getAtIndexAcquire(final TableEntry<K>[] table, final int index) {
        //noinspection unchecked
        return (TableEntry<K>)TableEntry.TABLE_ENTRY_ARRAY_HANDLE.getAcquire(table, index);
    }

    protected static <K> void setAtIndexRelease(final TableEntry<K>[] table, final int index, final TableEntry<K> value) {
        TableEntry.TABLE_ENTRY_ARRAY_HANDLE.setRelease(table, index, value);
    }

    protected static <K> void setAtIndexVolatile(final TableEntry<K>[] table, final int index, final TableEntry<K> value) {
        TableEntry.TABLE_ENTRY_ARRAY_HANDLE.setVolatile(table, index, value);
    }

    protected static <K> TableEntry<K> compareAndExchangeAtIndexVolatile(final TableEntry<K>[] table, final int index,
                                                                                    final TableEntry<K> expect, final TableEntry<K> update) {
        //noinspection unchecked
        return (TableEntry<K>)TableEntry.TABLE_ENTRY_ARRAY_HANDLE.compareAndExchange(table, index, expect, update);
    }

    protected TableEntry<K>[] fetchNewTable(final TableEntry<K>[] expectedCurr) {
        final TableEntry<K>[] candidate = this.nextTable;
        final TableEntry<K>[] current = this.table;
        // Note: We fetch a new table once RESIZE_NODE is encountered in the expectedCurr table.
        //       The resize logic guarantees that the RESIZE_NODE is only written to a bin once
        //       the chain is fully moved to the next table. Provided that we actually fetch the next table,
        //       we can guarantee that we will see the chain in the next table. However, we may not end up fetching
        //       the next table, but rather the resize after that - which will not guarantee that we see the correct
        //       chain. We catch this race condition by checking if the current table is the same as the expected table.
        //       If the current table is not the expected table, then we just use that - as it is guaranteed to either
        //       contain the full chain or be referenced to the next table.
        // Note: the read order of next and current table is important, do not re-order.
        return expectedCurr == current ? candidate : current;
    }

    /**
     * Returns the possible node associated with the key, or {@code null} if there is no such node.
     */
    protected final TableEntry<K> getNode(final K key) {
        final int hash = (HashUtil.mix(key.hashCode()));

        TableEntry<K>[] table = this.table;
        for (;;) {
            TableEntry<K> node = getAtIndexAcquire(table, hash & (table.length - 1));

            if (node == null) {
                // node == null
                return node;
            }

            if (node == RESIZE_NODE) {
                table = this.fetchNewTable(table);
                continue;
            }

            do {
                if ((node.key == key || node.key.equals(key))) {
                    return node;
                }

                node = node.getNextAcquire();
            } while (node != null);

            // node == null
            return node;
        }
    }

    /**
     * Returns the currently mapped value associated with the specified key, or {@code ((int)0)} if there is none.
     *
     * @param key Specified key
     * @throws NullPointerException If key is null
     */
    public int get(final K key) {
        Objects.requireNonNull(key, "Key may not be null");

        final TableEntry<K> node = this.getNode(key);
        return node == null ? ((int)0) : node.getValueVolatile();
    }

    /**
     * Returns the currently mapped value associated with the specified key, or the specified default value if there is none.
     *
     * @param key Specified key
     * @param defaultValue Specified default value
     * @throws NullPointerException If key is null
     */
    public int getOrDefault(final K key, final int defaultValue) {
        Objects.requireNonNull(key, "Key may not be null");

        final TableEntry<K> node = this.getNode(key);
        return node == null ? defaultValue : node.getValueVolatile();
    }

    /**
     * Returns whether the specified key is mapped to some value.
     * @param key Specified key
     * @throws NullPointerException If key is null
     */
    public boolean containsKey(final K key) {
        Objects.requireNonNull(key, "Key may not be null");

        return this.getNode(key) != null;
    }

    /**
     * Returns whether the specified value has a key mapped to it.
     * @param value Specified value
     * @throws NullPointerException If value is null
     */
    public boolean containsValue(final int value) {
        

        final NodeIterator<K> iterator = new NodeIterator<>(this);

        TableEntry<K> node;
        while ((node = iterator.findNext()) != null) {
            // need to use acquire here to ensure the happens-before relationship
            final int nodeValue = node.getValueAcquire();
            if ((value == nodeValue)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns whether the specified key is mapped and is mapped to the specified value.
     * @param key Specified key
     * @param value Specified value
     * @throws NullPointerException If key is null
     * @throws NullPointerException If value is null
     */
     public boolean contains(final K key, final int value) {
        Objects.requireNonNull(key, "Key may not be null");
        

        final TableEntry<K> node = this.getNode(key);

        if (node == null) {
            return false;
        }

        final int nodeValue = node.getValueAcquire();

        return (value == nodeValue);
     }

    /**
     * Returns the number of mappings in this map.
     */
    public int size() {
        return (int)Math.clamp(this.size.get(), 0L, (long)Integer.MAX_VALUE);
    }

    /**
     * Returns whether this map has no mappings.
     */
    public boolean isEmpty() {
        return this.size.get() <= 0L;
    }

    /**
     * Adds count to size and checks threshold for resizing
     */
    protected final void addSize(final long count) {
        final long sum = this.size.addAndGet(count);

        final int threshold = this.getThresholdAcquire();

        if (threshold < 0L) {
            // resizing or no resizing allowed, in either cases we do not need to do anything
            return;
        }

        if (sum < (long)threshold) {
            return;
        }

        if (threshold != this.compareExchangeThresholdVolatile(threshold, THRESHOLD_RESIZING)) {
            // some other thread resized
            return;
        }

        // create new table
        this.resize(sum);
    }

    /**
     * Resizes table, only invoke for the thread which has successfully updated threshold to {@link #THRESHOLD_RESIZING}
     * @param sum Estimate of current mapping count, must be >= old threshold
     */
    private void resize(final long sum) {
        int capacity;

        // add 1.0, as sum may equal threshold (in which case, sum / loadFactor = current capacity)
        // adding 1.0 should at least raise the size by a factor of two due to usage of roundCeilLog2
        final double targetD = ((double)sum / (double)this.loadFactor) + 1.0;
        if (targetD >= (double)MAXIMUM_CAPACITY) {
            capacity = MAXIMUM_CAPACITY;
        } else {
            capacity = (int)Math.ceil(targetD);
            capacity = IntegerUtil.roundCeilLog2(capacity);
            capacity = Math.min(capacity, MAXIMUM_CAPACITY);
        }

        // create new table data

        // noinspection unchecked
        final TableEntry<K>[] newTable = new TableEntry[capacity];

        // transfer nodes from old table

        // does not need to be volatile read, just plain
        final TableEntry<K>[] oldTable = this.table;
        this.nextTable = newTable;

        // when resizing, the old entries at bin i (where i = hash % oldTable.length) are assigned to
        // bin k in the new table (where k = hash % newTable.length)
        // since both table lengths are powers of two (specifically, newTable is a multiple of oldTable),
        // the possible number of locations in the new table to assign any given i is newTable.length/oldTable.length

        // we can build the new linked nodes for the new table by using a work array sized to newTable.length/oldTable.length
        // which holds the _last_ entry in the chain per bin

        final int capOldShift = IntegerUtil.floorLog2(oldTable.length);
        final int capDiffShift = IntegerUtil.floorLog2(capacity) - capOldShift;

        if (capDiffShift == 0) {
            throw new IllegalStateException("Resizing to same size");
        }

        // noinspection unchecked
        final TableEntry<K>[] work = new TableEntry[1 << capDiffShift]; // typically, capDiffShift = 1

        for (int i = 0, len = oldTable.length; i < len; ++i) {
            TableEntry<K> binNode = getAtIndexAcquire(oldTable, i);

            for (;;) {
                if (binNode == null) {
                    // just need to replace the bin node, do not need to move anything
                    if (null == (binNode = compareAndExchangeAtIndexVolatile(oldTable, i, null, (TableEntry<K>)RESIZE_NODE))) {
                        break;
                    } // else: binNode != null, fall through
                }

                // need write lock to block other writers
                synchronized (binNode) {
                    if (binNode != (binNode = getAtIndexAcquire(oldTable, i))) {
                        continue;
                    }

                    // an important detail of resizing is that we do not need to be concerned with synchronisation on
                    // writes to the new table, as no access to any nodes on bin i on oldTable will occur until a thread
                    // sees the resizeNode
                    // specifically, as long as the resizeNode is release written there are no cases where another thread
                    // will see our writes to the new table

                    TableEntry<K> next = binNode.getNextPlain();

                    if (next == null) {
                        // simple case: do not use work array

                        // do not need to create new node, readers only need to see the state of the map at the
                        // beginning of a call, so any additions onto _next_ don't really matter
                        // additionally, the old node is replaced so that writers automatically forward to the new table,
                        // which resolves any issues
                        newTable[(HashUtil.mix(binNode.key.hashCode())) & (capacity - 1)] = binNode;
                    } else {
                        // reset for next usage
                        Arrays.fill(work, null);

                        for (TableEntry<K> curr = binNode; curr != null; curr = curr.getNextPlain()) {
                            final int newTableIdx = (HashUtil.mix(curr.key.hashCode())) & (capacity - 1);
                            final int workIdx = newTableIdx >>> capOldShift;

                            final TableEntry<K> replace = new TableEntry<>(curr.key, curr.getValuePlain());

                            final TableEntry<K> workNode = work[workIdx];
                            work[workIdx] = replace;

                            if (workNode == null) {
                                newTable[newTableIdx] = replace;
                                continue;
                            } else {
                                workNode.setNextPlain(replace);
                                continue;
                            }
                        }
                    }

                    setAtIndexRelease(oldTable, i, (TableEntry<K>)RESIZE_NODE);
                    break;
                }
            }
        }

        // calculate new threshold
        final int newThreshold;
        if (capacity == MAXIMUM_CAPACITY) {
            newThreshold = THRESHOLD_NO_RESIZE;
        } else {
            newThreshold = getTargetThreshold(capacity, loadFactor);
        }

        this.table = newTable;
        // finish resize operation by releasing hold on threshold
        this.setThresholdVolatile(newThreshold);
    }

    /**
     * Subtracts count from size
     */
    protected final void subSize(final long count) {
        this.size.getAndAdd(-count);
    }

    /**
     * Atomically updates the value associated with {@code key} to {@code value}, or inserts a new mapping with {@code key}
     * mapped to {@code value}.
     * @param key Specified key
     * @param value Specified value
     * @throws NullPointerException If key is null
     * @throws NullPointerException If value is null
     * @return Old value previously associated with key, or {@code ((int)0)} if none.
     */
    public int put(final K key, final int value) {
        Objects.requireNonNull(key, "Key may not be null");
        

        final int hash = (HashUtil.mix(key.hashCode()));

        TableEntry<K>[] table = this.table;
        table_loop:
        for (;;) {
            final int index = hash & (table.length - 1);

            TableEntry<K> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                if (node == null) {
                    if (null == (node = compareAndExchangeAtIndexVolatile(table, index, null, new TableEntry<>(key, value)))) {
                        // successfully inserted
                        this.addSize(1L);
                        return ((int)0);
                    } // else: node != null, fall through
                }

                if (node == RESIZE_NODE) {
                    table = this.fetchNewTable(table);
                    continue table_loop;
                }

                synchronized (node) {
                    if (node != (node = getAtIndexAcquire(table, index))) {
                        continue node_loop;
                    }
                    // plain reads are fine during synchronised access, as we are the only writer
                    TableEntry<K> prev = null;
                    for (; node != null; prev = node, node = node.getNextPlain()) {
                        if ((node.key == key || node.key.equals(key))) {
                            final int ret = node.getValuePlain();
                            node.setValueVolatile(value);
                            return ret;
                        }
                    }

                    // volatile ordering ensured by addSize(), but we need release here
                    // to ensure proper ordering with reads and other writes
                    prev.setNextRelease(new TableEntry<>(key, value));
                }

                this.addSize(1L);
                return ((int)0);
            }
        }
    }

    /**
     * Atomically inserts a new mapping with {@code key} mapped to {@code value} if and only if {@code key} is not
     * currently mapped to some value.
     * @param key Specified key
     * @param value Specified value
     * @throws NullPointerException If key is null
     * @throws NullPointerException If value is null
     * @return Value currently associated with key, or {@code ((int)0)} if none and {@code value} was associated.
     */
    public int putIfAbsent(final K key, final int value) {
        Objects.requireNonNull(key, "Key may not be null");
        

        final int hash = (HashUtil.mix(key.hashCode()));

        TableEntry<K>[] table = this.table;
        table_loop:
        for (;;) {
            final int index = hash & (table.length - 1);

            TableEntry<K> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                if (node == null) {
                    if (null == (node = compareAndExchangeAtIndexVolatile(table, index, null, new TableEntry<>(key, value)))) {
                        // successfully inserted
                        this.addSize(1L);
                        return ((int)0);
                    } // else: node != null, fall through
                }

                if (node == RESIZE_NODE) {
                    table = this.fetchNewTable(table);
                    continue table_loop;
                }

                // optimise ifAbsent calls: check if first node is key before attempting lock acquire
                if ((node.key == key || node.key.equals(key))) {
                    return node.getValueVolatile();
                }

                synchronized (node) {
                    if (node != (node = getAtIndexAcquire(table, index))) {
                        continue node_loop;
                    }
                    // plain reads are fine during synchronised access, as we are the only writer
                    TableEntry<K> prev = null;
                    for (; node != null; prev = node, node = node.getNextPlain()) {
                        if ((node.key == key || node.key.equals(key))) {
                            return node.getValuePlain();
                        }
                    }

                    // volatile ordering ensured by addSize(), but we need release here
                    // to ensure proper ordering with reads and other writes
                    prev.setNextRelease(new TableEntry<>(key, value));
                }

                this.addSize(1L);
                return ((int)0);
            }
        }
    }

    /**
     * Atomically updates the value associated with {@code key} to {@code value}, or does nothing if {@code key} is not
     * associated with a value.
     * @param key Specified key
     * @param value Specified value
     * @throws NullPointerException If key is null
     * @throws NullPointerException If value is null
     * @return Old value previously associated with key, or {@code ((int)0)} if none.
     */
    public int replace(final K key, final int value) {
        Objects.requireNonNull(key, "Key may not be null");
        

        final int hash = (HashUtil.mix(key.hashCode()));

        TableEntry<K>[] table = this.table;
        table_loop:
        for (;;) {
            final int index = hash & (table.length - 1);

            TableEntry<K> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                if (node == null) {
                    return ((int)0);
                }

                if (node == RESIZE_NODE) {
                    table = this.fetchNewTable(table);
                    continue table_loop;
                }

                synchronized (node) {
                    if (node != (node = getAtIndexAcquire(table, index))) {
                        continue node_loop;
                    }

                    // plain reads are fine during synchronised access, as we are the only writer
                    for (; node != null; node = node.getNextPlain()) {
                        if ((node.key == key || node.key.equals(key))) {
                            final int ret = node.getValuePlain();
                            node.setValueVolatile(value);
                            return ret;
                        }
                    }
                }

                return ((int)0);
            }
        }
    }

    /**
     * Atomically updates the value associated with {@code key} to {@code update} if the currently associated
     * value is equal to {@code expect}, otherwise does nothing.
     * @param key Specified key
     * @param expect Expected value to check current mapped value with
     * @param update Update value to replace mapped value with
     * @throws NullPointerException If key is null
     * @throws NullPointerException If expect is null
     * @throws NullPointerException If update is null
     * @return If the currently mapped value is not equal to {@code expect}, then returns the currently mapped
     *         value. If the key is not mapped to any value, then returns {@code ((int)0)}. If neither of the two cases are
     *         true, then returns {@code expect}.
     */
    public int replace(final K key, final int expect, final int update) {
        
        

        final int hash = (HashUtil.mix(key.hashCode()));

        TableEntry<K>[] table = this.table;
        table_loop:
        for (;;) {
            final int index = hash & (table.length - 1);

            TableEntry<K> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                if (node == null) {
                    return ((int)0);
                }

                if (node == RESIZE_NODE) {
                    table = this.fetchNewTable(table);
                    continue table_loop;
                }

                synchronized (node) {
                    if (node != (node = getAtIndexAcquire(table, index))) {
                        continue node_loop;
                    }

                    // plain reads are fine during synchronised access, as we are the only writer
                    for (; node != null; node = node.getNextPlain()) {
                        if ((node.key == key || node.key.equals(key))) {
                            final int ret = node.getValuePlain();

                            if (!(expect == ret)) {
                                return ret;
                            }

                            node.setValueVolatile(update);
                            return ret;
                        }
                    }
                }

                return ((int)0);
            }
        }
    }

    /**
     * Atomically removes the mapping for the specified key and returns the value it was associated with. If the key
     * is not mapped to a value, then does nothing and returns {@code ((int)0)}.
     * @param key Specified key
     * @throws NullPointerException If key is null
     * @return Old value previously associated with key, or {@code ((int)0)} if none.
     */
    public int remove(final K key) {
        Objects.requireNonNull(key, "Key may not be null");

        final int hash = (HashUtil.mix(key.hashCode()));

        TableEntry<K>[] table = this.table;
        table_loop:
        for (;;) {
            final int index = hash & (table.length - 1);

            TableEntry<K> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                if (node == null) {
                    return ((int)0);
                }

                if (node == RESIZE_NODE) {
                    table = this.fetchNewTable(table);
                    continue table_loop;
                }

                boolean removed = false;
                int ret = ((int)0);

                synchronized (node) {
                    if (node != (node = getAtIndexAcquire(table, index))) {
                        continue node_loop;
                    }

                    TableEntry<K> prev = null;

                    // plain reads are fine during synchronised access, as we are the only writer
                    for (; node != null; prev = node, node = node.getNextPlain()) {
                        if ((node.key == key || node.key.equals(key))) {
                            ret = node.getValuePlain();
                            removed = true;

                            // volatile ordering ensured by addSize(), but we need release here
                            // to ensure proper ordering with reads and other writes
                            if (prev == null) {
                                setAtIndexRelease(table, index, node.getNextPlain());
                            } else {
                                prev.setNextRelease(node.getNextPlain());
                            }

                            break;
                        }
                    }
                }

                if (removed) {
                    this.subSize(1L);
                }

                return ret;
            }
        }
    }

    /**
     * Atomically removes the mapping for the specified key if it is mapped to {@code expect} and returns {@code expect}. If the key
     * is not mapped to a value, then does nothing and returns {@code ((int)0)}. If the key is mapped to a value that is not
     * equal to {@code expect}, then returns that value.
     * @param key Specified key
     * @param expect Specified expected value
     * @throws NullPointerException If key is null
     * @return The specified expected value if the key was mapped to {@code expect}. If
     *         the key is not mapped to any value, then returns {@code ((int)0)}. If neither of those cases are true,
     *         then returns the current mapped value for key.
     */
    public int remove(final K key, final int expect) {
        Objects.requireNonNull(key, "Key may not be null");

        final int hash = (HashUtil.mix(key.hashCode()));

        TableEntry<K>[] table = this.table;
        table_loop:
        for (;;) {
            final int index = hash & (table.length - 1);

            TableEntry<K> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                if (node == null) {
                    return ((int)0);
                }

                if (node == RESIZE_NODE) {
                    table = this.fetchNewTable(table);
                    continue table_loop;
                }

                boolean removed = false;
                int ret = ((int)0);

                synchronized (node) {
                    if (node != (node = getAtIndexAcquire(table, index))) {
                        continue node_loop;
                    }

                    TableEntry<K> prev = null;

                    // plain reads are fine during synchronised access, as we are the only writer
                    for (; node != null; prev = node, node = node.getNextPlain()) {
                        if ((node.key == key || node.key.equals(key))) {
                            ret = node.getValuePlain();
                            if ((expect ==  ret)) {
                                removed = true;

                                // volatile ordering ensured by addSize(), but we need release here
                                // to ensure proper ordering with reads and other writes
                                if (prev == null) {
                                    setAtIndexRelease(table, index, node.getNextPlain());
                                } else {
                                    prev.setNextRelease(node.getNextPlain());
                                }
                            }
                            break;
                        }
                    }
                }

                if (removed) {
                    this.subSize(1L);
                }

                return ret;
            }
        }
    }

    @FunctionalInterface
    public static interface RemoveIfPredicate<K> {
        public boolean test(final int value);
    }

    /**
     * Atomically removes the mapping for the specified key the predicate returns true for its currently mapped value. If the key
     * is not mapped to a value, then does nothing and returns {@code ((int)0)}.
     *
     * <p>
     * This function is a "functional method" as defined by {@link ConcurrentChainedObject2IntHashTable}.
     * </p>
     *
     * @param key Specified key
     * @param predicate Specified predicate
     * @throws NullPointerException If key is null
     * @throws NullPointerException If predicate is null
     * @return The specified expected value if the key was mapped to {@code expect}. If
     *         the key is not mapped to any value, then returns {@code null}. If neither of those cases are true,
     *         then returns the current (non-null) mapped value for key.
     */
    public int removeIf(final K key, final RemoveIfPredicate<K> predicate) {
        Objects.requireNonNull(key, "Key may not be null");
        Objects.requireNonNull(predicate, "Predicate may not be null");

        final int hash = (HashUtil.mix(key.hashCode()));

        TableEntry<K>[] table = this.table;
        table_loop:
        for (;;) {
            final int index = hash & (table.length - 1);

            TableEntry<K> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                if (node == null) {
                    return ((int)0);
                }

                if (node == RESIZE_NODE) {
                    table = this.fetchNewTable(table);
                    continue table_loop;
                }

                boolean removed = false;
                int ret = ((int)0);

                synchronized (node) {
                    if (node != (node = getAtIndexAcquire(table, index))) {
                        continue node_loop;
                    }

                    TableEntry<K> prev = null;

                    // plain reads are fine during synchronised access, as we are the only writer
                    for (; node != null; prev = node, node = node.getNextPlain()) {
                        if ((node.key == key || node.key.equals(key))) {
                            ret = node.getValuePlain();
                            if (predicate.test(ret)) {
                                removed = true;

                                // volatile ordering ensured by addSize(), but we need release here
                                // to ensure proper ordering with reads and other writes
                                if (prev == null) {
                                    setAtIndexRelease(table, index, node.getNextPlain());
                                } else {
                                    prev.setNextRelease(node.getNextPlain());
                                }
                            }
                            break;
                        }
                    }
                }

                if (removed) {
                    this.subSize(1L);
                }

                return ret;
            }
        }
    }


    /**
     * Atomically inserts a mapping for the specified key with the provided default value if the key is not mapped or
     * updates the current value by adding the specified increment.
     * @param key Specified key
     * @param increment Specified increment
     * @param defaultValue Specified default value
     * @throws NullPointerException If key is null
     * @return The newly mapped value
     */
    public int addTo(final K key, final int increment, final int defaultValue) {
        Objects.requireNonNull(key, "Key may not be null");
        final int hash = (HashUtil.mix(key.hashCode()));

        TableEntry<K>[] table = this.table;
        table_loop:
        for (;;) {
            final int index = hash & (table.length - 1);

            TableEntry<K> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                if (node == null) {
                    if (null == (node = compareAndExchangeAtIndexVolatile(table, index, null, new TableEntry<>(key, defaultValue)))) {
                        // successfully inserted
                        this.addSize(1L);
                        return defaultValue;
                    } // else: node != null, fall through
                }

                if (node == RESIZE_NODE) {
                    table = this.fetchNewTable(table);
                    continue table_loop;
                }

                synchronized (node) {
                    if (node != (node = getAtIndexAcquire(table, index))) {
                        continue node_loop;
                    }
                    // plain reads are fine during synchronised access, as we are the only writer
                    TableEntry<K> prev = null;
                    for (; node != null; prev = node, node = node.getNextPlain()) {
                        if (node.key == key) {
                            final int ret = node.getValuePlain() + increment;
                            node.setValueVolatile(ret);
                            return ret;
                        }
                    }

                    // volatile ordering ensured by addSize(), but we need release here
                    // to ensure proper ordering with reads and other writes
                    prev.setNextRelease(new TableEntry<>(key, defaultValue));
                }

                this.addSize(1L);
                return defaultValue;
            }
        }
    }

    /**
     * Atomically decrements the mapping by the specified decrement and then removes the mapping if it is below or equal to
     * the specified threshold. If the key is not mapped, then an exception is thrown.
     * @param key Specified key
     * @param decrement Specified decrement
     * @param threshold Specified threshold
     * @throws IllegalStateException If there exists no mapping for the key
     * @throws NullPointerException If key is null
     * @return The previous value decremented by the specified decrement
     */
    public int decFrom(final K key, final int decrement, final int threshold) {
        Objects.requireNonNull(key, "Key may not be null");
        final int hash = (HashUtil.mix(key.hashCode()));

        TableEntry<K>[] table = this.table;
        table_loop:
        for (;;) {
            final int index = hash & (table.length - 1);

            TableEntry<K> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                if (node == null) {
                    throw new IllegalStateException();
                }

                if (node == RESIZE_NODE) {
                    table = this.fetchNewTable(table);
                    continue table_loop;
                }

                boolean removed = false;
                boolean found = false;
                int ret = ((int)0);

                synchronized (node) {
                    if (node != (node = getAtIndexAcquire(table, index))) {
                        continue node_loop;
                    }

                    TableEntry<K> prev = null;

                    // plain reads are fine during synchronised access, as we are the only writer
                    for (; node != null; prev = node, node = node.getNextPlain()) {
                        if (node.key == key) {
                            found = true;
                            ret = node.getValuePlain() - decrement;

                            if (ret <= threshold) {
                                removed = true;

                                // volatile ordering ensured by addSize(), but we need release here
                                // to ensure proper ordering with reads and other writes
                                if (prev == null) {
                                    setAtIndexRelease(table, index, node.getNextPlain());
                                } else {
                                    prev.setNextRelease(node.getNextPlain());
                                }
                            } else {
                                // note: cannot set if removing, otherwise the operation is not atomic!
                                node.setValueRelease(ret);
                            }
                            break;
                        }
                    }
                }

                if (!found) {
                    throw new IllegalStateException();
                }

                if (removed) {
                    this.subSize(1L);
                }

                return ret;
            }
        }
    }

    /**
     * Removes at least all entries currently mapped at the beginning of this call. May not remove entries added during
     * this call. As a result, only if this map is not modified during the call, that all entries will be removed by
     * the end of the call.
     *
     * <p>
     * This function is not atomic.
     * </p>
     */
    public void clear() {
        // it is possible to optimise this to directly interact with the table,
        // but we do need to be careful when interacting with resized tables,
        // and the NodeIterator already does this logic
        final NodeIterator<K> nodeIterator = new NodeIterator<>(this);

        TableEntry<K> node;
        while ((node = nodeIterator.findNext()) != null) {
            this.remove(node.key);
        }
    }

    /**
     * Returns an iterator over the entries in this map. The iterator is only guaranteed to see entries that were
     * added before the beginning of this call, but it may see entries added during.
     */
    public BaseObjectIterator<TableEntry<K>> entryIterator() {
        return new EntryNodeIterator<>(this);
    }

    @Override
    public final BaseObjectIterator<TableEntry<K>> iterator() {
        return this.entryIterator();
    }

    /**
     * Returns an iterator over the keys in this map. The iterator is only guaranteed to see keys that were
     * added before the beginning of this call, but it may see keys added during.
     */
    public BaseObjectIterator<K> keyIterator() {
        return new KeyIterator<>(this);
    }

    /**
     * Returns an iterator over the values in this map. The iterator is only guaranteed to see values that were
     * added before the beginning of this call, but it may see values added during.
     */
    public BaseIntIterator valueIterator() {
        return new ValueIterator<>(this);
    }

    protected Keys<K> keys;
    public Set<K> keys() {
        final Keys<K> keys = this.keys;
        if (keys != null) {
            return keys;
        }
        return this.keys = new Keys<>(this);
    }

    protected static class Keys<K> extends BaseMapSet<K> {
        protected final ConcurrentChainedObject2IntHashTable<K> map;

        protected Keys(final ConcurrentChainedObject2IntHashTable<K> map) {
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
            final List<K> ret = new ArrayList<>(this.map.size());

            for (final K element : this) {
                ret.add(element);
            }

            return ret;
        }

        @Override
        public BaseObjectIterator<K> iterator() {
            return this.map.keyIterator();
        }
    }


    protected EntrySet<K> entrySet;
    public Set<TableEntry<K>> entrySet() {
        final EntrySet<K> entrySet = this.entrySet;
        if (entrySet != null) {
            return entrySet;
        }
        return this.entrySet = new EntrySet<>(this);
    }

    protected static class EntrySet<K> extends BaseMapSet<TableEntry<K>> {
        protected final ConcurrentChainedObject2IntHashTable<K> map;

        protected EntrySet(final ConcurrentChainedObject2IntHashTable<K> map) {
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
            if (!(value instanceof ConcurrentChainedObject2IntHashTable.TableEntry<?> entry)) {
                return false;
            }

            final Object entryKey = entry.getKey();
            final int entryValue = entry.getValue();

            return this.map.contains((K)entryKey, (int)entryValue);
        }

        @Override
        protected List<TableEntry<K>> asList() {
            final List<TableEntry<K>> ret = new ArrayList<>(this.map.size());

            for (final TableEntry<K> element : this) {
                ret.add(element);
            }

            return ret;
        }

        @Override
        public BaseObjectIterator<TableEntry<K>> iterator() {
            return this.map.entryIterator();
        }
    }

    protected static final class EntryNodeIterator<K> extends BaseObjectIterator<TableEntry<K>> {
        protected TableEntry<K> lastReturned;
        protected TableEntry<K> nextToReturn;
        protected final NodeIterator<K> nodeIterator;

        protected EntryNodeIterator(final ConcurrentChainedObject2IntHashTable<K> map) {
            this.nodeIterator = new NodeIterator<>(map);
        }

        @Override
        public boolean hasNext() {
            if (this.nextToReturn != null) {
                return true;
            }

            return (this.nextToReturn = this.nodeIterator.findNext()) != null;
        }

        @Override
        public TableEntry<K> next() throws NoSuchElementException {
            TableEntry<K> ret = this.nextToReturn;
            if (ret != null) {
                this.lastReturned = ret;
                this.nextToReturn = null;
                return ret;
            }
            ret = this.nodeIterator.findNext();
            if (ret != null) {
                this.lastReturned = ret;
                return ret;
            }
            throw new NoSuchElementException();
        }

        @Override
        public void remove() {
            final TableEntry<K> lastReturned = this.lastReturned;
            if (lastReturned == null) {
                throw new NoSuchElementException();
            }
            this.lastReturned = null;
            this.nodeIterator.map.remove(lastReturned.key);
        }
    }

    protected static final class KeyIterator<K> extends BaseObjectIterator<K> {

        private final EntryNodeIterator<K> iterator;

        public KeyIterator(final ConcurrentChainedObject2IntHashTable<K> map) {
            this.iterator = new EntryNodeIterator<>(map);
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

    protected static final class ValueIterator<K> extends BaseIntIterator {

        private final EntryNodeIterator<K> iterator;

        public ValueIterator(final ConcurrentChainedObject2IntHashTable<K> map) {
            this.iterator = new EntryNodeIterator<>(map);
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

    protected static class NodeIterator<K> {

        protected final ConcurrentChainedObject2IntHashTable<K> map;
        private TableEntry<K>[] currentTable;
        private ResizeChain<K> resizeChain;
        private TableEntry<K> last;
        private int nextBin;
        private int increment;

        protected NodeIterator(final ConcurrentChainedObject2IntHashTable<K> map) {
            this.map = map;
            this.currentTable = map.table;
            this.increment = 1;
        }

        private TableEntry<K>[] pullResizeChain(final int index) {
            final ResizeChain<K> resizeChain = this.resizeChain;
            if (resizeChain == null) {
                this.currentTable = null;
                return null;
            }

            final ResizeChain<K> prevChain = resizeChain.prev;
            this.resizeChain = prevChain;
            if (prevChain == null) {
                this.currentTable = null;
                return null;
            }

            final TableEntry<K>[] newTable = prevChain.table;

            // we recover the original index by modding by the new table length, as the increments applied to the index
            // are a multiple of the new table's length
            int newIdx = index & (newTable.length - 1);

            // the increment is always the previous table's length
            final ResizeChain<K> nextPrevChain = prevChain.prev;
            final int increment;
            if (nextPrevChain == null) {
                increment = 1;
            } else {
                increment = nextPrevChain.table.length;
            }

            // done with the upper table, so we can skip the resize node
            newIdx += increment;

            this.increment = increment;
            this.nextBin = newIdx;
            this.currentTable = newTable;

            return newTable;
        }

        private TableEntry<K>[] pushResizeChain(final TableEntry<K>[] table) {
            final ResizeChain<K> chain = this.resizeChain;

            if (chain == null) {
                final TableEntry<K>[] nextTable = this.map.fetchNewTable(table);

                final ResizeChain<K> oldChain = new ResizeChain<>(table, null, null);
                final ResizeChain<K> currChain = new ResizeChain<>(nextTable, oldChain, null);
                oldChain.next = currChain;

                this.increment = table.length;
                this.resizeChain = currChain;
                this.currentTable = nextTable;

                return nextTable;
            } else {
                ResizeChain<K> currChain = chain.next;
                if (currChain == null) {
                    final TableEntry<K>[] ret = this.map.fetchNewTable(table);
                    currChain = new ResizeChain<>(ret, chain, null);
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

        protected final TableEntry<K> findNext() {
            for (;;) {
                final TableEntry<K> last = this.last;
                if (last != null) {
                    final TableEntry<K> next = last.getNextVolatile();
                    if (next != null) {
                        this.last = next;
                        return next;
                    }
                }

                TableEntry<K>[] table = this.currentTable;

                if (table == null) {
                    return null;
                }

                int idx = this.nextBin;
                int increment = this.increment;
                for (;;) {
                    if (idx >= table.length) {
                        table = this.pullResizeChain(idx);
                        idx = this.nextBin;
                        increment = this.increment;
                        if (table != null) {
                            continue;
                        } else {
                            this.last = null;
                            return null;
                        }
                    }

                    final TableEntry<K> entry = getAtIndexAcquire(table, idx);
                    if (entry == null) {
                        idx += increment;
                        continue;
                    }

                    if (entry == RESIZE_NODE) {
                        // push onto resize chain
                        table = this.pushResizeChain(table);
                        increment = this.increment;
                        continue;
                    }

                    this.last = entry;
                    this.nextBin = idx + increment;
                    return entry;
                }
            }
        }

        private static final class ResizeChain<K> {

            public final TableEntry<K>[] table;
            public final ResizeChain<K> prev;
            public ResizeChain<K> next;

            public ResizeChain(final TableEntry<K>[] table, final ResizeChain<K> prev, final ResizeChain<K> next) {
                this.table = table;
                this.prev = prev;
                this.next = next;
            }
        }
    }

    public static final class TableEntry<K> {

        private static final VarHandle TABLE_ENTRY_ARRAY_HANDLE = ConcurrentUtil.getArrayHandle(TableEntry[].class);

        private final K key;

        private volatile int value;
        private static final VarHandle VALUE_HANDLE = ConcurrentUtil.getVarHandle(TableEntry.class, "value", int.class);

        private int getValuePlain() {
            //noinspection unchecked
            return (int)VALUE_HANDLE.get(this);
        }

        private int getValueAcquire() {
            //noinspection unchecked
            return (int)VALUE_HANDLE.getAcquire(this);
        }

        private int getValueVolatile() {
            //noinspection unchecked
            return (int)VALUE_HANDLE.getVolatile(this);
        }

        private void setValuePlain(final int value) {
            VALUE_HANDLE.set(this, (Object)value);
        }

        private void setValueRelease(final int value) {
            VALUE_HANDLE.setRelease(this, (Object)value);
        }

        private void setValueVolatile(final int value) {
            VALUE_HANDLE.setVolatile(this, (Object)value);
        }

        private volatile TableEntry<K> next;
        private static final VarHandle NEXT_HANDLE = ConcurrentUtil.getVarHandle(TableEntry.class, "next", TableEntry.class);

        private TableEntry<K> getNextPlain() {
            //noinspection unchecked
            return (TableEntry<K>)NEXT_HANDLE.get(this);
        }

        private TableEntry<K> getNextAcquire() {
            //noinspection unchecked
            return (TableEntry<K>)NEXT_HANDLE.getAcquire(this);
        }

        private TableEntry<K> getNextVolatile() {
            //noinspection unchecked
            return (TableEntry<K>)NEXT_HANDLE.getVolatile(this);
        }

        private void setNextPlain(final TableEntry<K> next) {
            NEXT_HANDLE.set(this, next);
        }

        private void setNextRelease(final TableEntry<K> next) {
            NEXT_HANDLE.setRelease(this, next);
        }

        private void setNextVolatile(final TableEntry<K> next) {
            NEXT_HANDLE.setVolatile(this, next);
        }

        protected TableEntry(final K key, final int value) {
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
}
