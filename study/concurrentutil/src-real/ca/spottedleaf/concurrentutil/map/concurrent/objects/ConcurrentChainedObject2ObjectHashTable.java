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
 * Concurrent hashtable implementation supporting mapping arbitrary non-null {@code K} values onto non-null {@code V}
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
 * @param <V> The type for values in this map
 * @see java.util.concurrent.ConcurrentMap
 */
public class ConcurrentChainedObject2ObjectHashTable<K,V> implements Iterable<ConcurrentChainedObject2ObjectHashTable.TableEntry<K,V>> {

    private static final TableEntry<?,?> RESIZE_NODE = new TableEntry<>(null, null);

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

    protected volatile TableEntry<K,V>[] table;
    protected volatile TableEntry<K,V>[] nextTable;

    protected static final int THRESHOLD_NO_RESIZE = -1;
    protected static final int THRESHOLD_RESIZING  = -2;
    protected volatile int threshold;
    protected static final VarHandle THRESHOLD_HANDLE = ConcurrentUtil.getVarHandle(ConcurrentChainedObject2ObjectHashTable.class, "threshold", int.class);

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

    public ConcurrentChainedObject2ObjectHashTable() {
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

    protected ConcurrentChainedObject2ObjectHashTable(final int capacity, final float loadFactor) {
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
        this.nextTable = this.table = (TableEntry<K,V>[])new TableEntry[tableSize];
    }

    public static <K,V> ConcurrentChainedObject2ObjectHashTable<K,V> createWithCapacity(final int capacity) {
        return createWithCapacity(capacity, DEFAULT_LOAD_FACTOR);
    }

    public static <K,V> ConcurrentChainedObject2ObjectHashTable<K,V> createWithCapacity(final int capacity, final float loadFactor) {
        return new ConcurrentChainedObject2ObjectHashTable<>(capacity, loadFactor);
    }

    public static <K,V> ConcurrentChainedObject2ObjectHashTable<K,V> createWithExpected(final int expected) {
        return createWithExpected(expected, DEFAULT_LOAD_FACTOR);
    }

    public static <K,V> ConcurrentChainedObject2ObjectHashTable<K,V> createWithExpected(final int expected, final float loadFactor) {
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

    protected static <K,V> TableEntry<K,V> getAtIndexAcquire(final TableEntry<K,V>[] table, final int index) {
        //noinspection unchecked
        return (TableEntry<K,V>)TableEntry.TABLE_ENTRY_ARRAY_HANDLE.getAcquire(table, index);
    }

    protected static <K,V> void setAtIndexRelease(final TableEntry<K,V>[] table, final int index, final TableEntry<K,V> value) {
        TableEntry.TABLE_ENTRY_ARRAY_HANDLE.setRelease(table, index, value);
    }

    protected static <K,V> void setAtIndexVolatile(final TableEntry<K,V>[] table, final int index, final TableEntry<K,V> value) {
        TableEntry.TABLE_ENTRY_ARRAY_HANDLE.setVolatile(table, index, value);
    }

    protected static <K,V> TableEntry<K,V> compareAndExchangeAtIndexVolatile(final TableEntry<K,V>[] table, final int index,
                                                                                    final TableEntry<K,V> expect, final TableEntry<K,V> update) {
        //noinspection unchecked
        return (TableEntry<K,V>)TableEntry.TABLE_ENTRY_ARRAY_HANDLE.compareAndExchange(table, index, expect, update);
    }

    protected TableEntry<K,V>[] fetchNewTable(final TableEntry<K,V>[] expectedCurr) {
        final TableEntry<K,V>[] candidate = this.nextTable;
        final TableEntry<K,V>[] current = this.table;
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
     * The node returned may have a {@code null} {@link TableEntry#value}, in which case the node is a placeholder for
     * a compute/computeIfAbsent call. The placeholder node should not be considered mapped in order to preserve
     * happens-before relationships between writes and reads in the map.
     */
    protected final TableEntry<K,V> getNode(final K key) {
        final int hash = (HashUtil.mix(key.hashCode()));

        TableEntry<K,V>[] table = this.table;
        for (;;) {
            TableEntry<K,V> node = getAtIndexAcquire(table, hash & (table.length - 1));

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
     * Returns the currently mapped value associated with the specified key, or {@code null} if there is none.
     *
     * @param key Specified key
     * @throws NullPointerException If key is null
     */
    public V get(final K key) {
        Objects.requireNonNull(key, "Key may not be null");

        final TableEntry<K,V> node = this.getNode(key);
        // note: getValueVolatile == null if a placement node, so we don't need to check it
        return node == null ? null : node.getValueVolatile();
    }

    /**
     * Returns the currently mapped value associated with the specified key, or the specified default value if there is none.
     *
     * @param key Specified key
     * @param defaultValue Specified default value
     * @throws NullPointerException If key is null
     */
    public V getOrDefault(final K key, final V defaultValue) {
        Objects.requireNonNull(key, "Key may not be null");

        final TableEntry<K,V> node = this.getNode(key);
        if (node == null) {
            return defaultValue;
        }

        final V ret = node.getValueVolatile();
        if (ret == null) {
            // ret == null for nodes pre-allocated to compute() and friends
            return defaultValue;
        }

        return ret;
    }

    /**
     * Returns whether the specified key is mapped to some value.
     * @param key Specified key
     * @throws NullPointerException If key is null
     */
    public boolean containsKey(final K key) {
        Objects.requireNonNull(key, "Key may not be null");

        // must also check for null, for nodes pre-allocated to compute() and friends
        final TableEntry<K,V> node = this.getNode(key);
        return node != null && node.getValueVolatile() != null;
    }

    /**
     * Returns whether the specified value has a key mapped to it.
     * @param value Specified value
     * @throws NullPointerException If value is null
     */
    public boolean containsValue(final V value) {
        Objects.requireNonNull(value, "Value may not be null");

        final NodeIterator<K,V> iterator = new NodeIterator<>(this);

        TableEntry<K,V> node;
        while ((node = iterator.findNext()) != null) {
            // need to use acquire here to ensure the happens-before relationship
            final V nodeValue = node.getValueAcquire();
            if ((value == nodeValue || value.equals(nodeValue))) {
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
     public boolean contains(final K key, final V value) {
        Objects.requireNonNull(key, "Key may not be null");
        Objects.requireNonNull(value, "Value may not be null");

        final TableEntry<K,V> node = this.getNode(key);

        if (node == null) {
            return false;
        }

        final V nodeValue = node.getValueAcquire();

        // value != null due to check, so we check placeholder node implicitly
        return (value == nodeValue || value.equals(nodeValue));
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
        final TableEntry<K,V>[] newTable = new TableEntry[capacity];

        // transfer nodes from old table

        // does not need to be volatile read, just plain
        final TableEntry<K,V>[] oldTable = this.table;
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
        final TableEntry<K,V>[] work = new TableEntry[1 << capDiffShift]; // typically, capDiffShift = 1

        for (int i = 0, len = oldTable.length; i < len; ++i) {
            TableEntry<K,V> binNode = getAtIndexAcquire(oldTable, i);

            for (;;) {
                if (binNode == null) {
                    // just need to replace the bin node, do not need to move anything
                    if (null == (binNode = compareAndExchangeAtIndexVolatile(oldTable, i, null, (TableEntry<K,V>)RESIZE_NODE))) {
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

                    TableEntry<K,V> next = binNode.getNextPlain();

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

                        for (TableEntry<K,V> curr = binNode; curr != null; curr = curr.getNextPlain()) {
                            final int newTableIdx = (HashUtil.mix(curr.key.hashCode())) & (capacity - 1);
                            final int workIdx = newTableIdx >>> capOldShift;

                            final TableEntry<K,V> replace = new TableEntry<>(curr.key, curr.getValuePlain());

                            final TableEntry<K,V> workNode = work[workIdx];
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

                    setAtIndexRelease(oldTable, i, (TableEntry<K,V>)RESIZE_NODE);
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
     * @return Old value previously associated with key, or {@code null} if none.
     */
    public V put(final K key, final V value) {
        Objects.requireNonNull(key, "Key may not be null");
        Objects.requireNonNull(value, "Value may not be null");

        final int hash = (HashUtil.mix(key.hashCode()));

        TableEntry<K,V>[] table = this.table;
        table_loop:
        for (;;) {
            final int index = hash & (table.length - 1);

            TableEntry<K,V> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                if (node == null) {
                    if (null == (node = compareAndExchangeAtIndexVolatile(table, index, null, new TableEntry<>(key, value)))) {
                        // successfully inserted
                        this.addSize(1L);
                        return null;
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
                    TableEntry<K,V> prev = null;
                    for (; node != null; prev = node, node = node.getNextPlain()) {
                        if ((node.key == key || node.key.equals(key))) {
                            final V ret = node.getValuePlain();
                            node.setValueVolatile(value);
                            return ret;
                        }
                    }

                    // volatile ordering ensured by addSize(), but we need release here
                    // to ensure proper ordering with reads and other writes
                    prev.setNextRelease(new TableEntry<>(key, value));
                }

                this.addSize(1L);
                return null;
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
     * @return Value currently associated with key, or {@code null} if none and {@code value} was associated.
     */
    public V putIfAbsent(final K key, final V value) {
        Objects.requireNonNull(key, "Key may not be null");
        Objects.requireNonNull(value, "Value may not be null");

        final int hash = (HashUtil.mix(key.hashCode()));

        TableEntry<K,V>[] table = this.table;
        table_loop:
        for (;;) {
            final int index = hash & (table.length - 1);

            TableEntry<K,V> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                if (node == null) {
                    if (null == (node = compareAndExchangeAtIndexVolatile(table, index, null, new TableEntry<>(key, value)))) {
                        // successfully inserted
                        this.addSize(1L);
                        return null;
                    } // else: node != null, fall through
                }

                if (node == RESIZE_NODE) {
                    table = this.fetchNewTable(table);
                    continue table_loop;
                }

                // optimise ifAbsent calls: check if first node is key before attempting lock acquire
                if ((node.key == key || node.key.equals(key))) {
                    final V ret = node.getValueVolatile();
                    if (ret != null) {
                        return ret;
                    } // else: fall back to lock to read the node
                }

                synchronized (node) {
                    if (node != (node = getAtIndexAcquire(table, index))) {
                        continue node_loop;
                    }
                    // plain reads are fine during synchronised access, as we are the only writer
                    TableEntry<K,V> prev = null;
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
                return null;
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
     * @return Old value previously associated with key, or {@code null} if none.
     */
    public V replace(final K key, final V value) {
        Objects.requireNonNull(key, "Key may not be null");
        Objects.requireNonNull(value, "Value may not be null");

        final int hash = (HashUtil.mix(key.hashCode()));

        TableEntry<K,V>[] table = this.table;
        table_loop:
        for (;;) {
            final int index = hash & (table.length - 1);

            TableEntry<K,V> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                if (node == null) {
                    return null;
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
                            final V ret = node.getValuePlain();
                            node.setValueVolatile(value);
                            return ret;
                        }
                    }
                }

                return null;
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
     *         value. If the key is not mapped to any value, then returns {@code null}. If neither of the two cases are
     *         true, then returns {@code expect}.
     */
    public V replace(final K key, final V expect, final V update) {
        Objects.requireNonNull(expect, "Expect may not be null");
        Objects.requireNonNull(update, "Update may not be null");

        final int hash = (HashUtil.mix(key.hashCode()));

        TableEntry<K,V>[] table = this.table;
        table_loop:
        for (;;) {
            final int index = hash & (table.length - 1);

            TableEntry<K,V> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                if (node == null) {
                    return null;
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
                            final V ret = node.getValuePlain();

                            if (!(expect == ret || expect.equals(ret))) {
                                return ret;
                            }

                            node.setValueVolatile(update);
                            return ret;
                        }
                    }
                }

                return null;
            }
        }
    }

    /**
     * Atomically removes the mapping for the specified key and returns the value it was associated with. If the key
     * is not mapped to a value, then does nothing and returns {@code null}.
     * @param key Specified key
     * @throws NullPointerException If key is null
     * @return Old value previously associated with key, or {@code null} if none.
     */
    public V remove(final K key) {
        Objects.requireNonNull(key, "Key may not be null");

        final int hash = (HashUtil.mix(key.hashCode()));

        TableEntry<K,V>[] table = this.table;
        table_loop:
        for (;;) {
            final int index = hash & (table.length - 1);

            TableEntry<K,V> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                if (node == null) {
                    return null;
                }

                if (node == RESIZE_NODE) {
                    table = this.fetchNewTable(table);
                    continue table_loop;
                }

                boolean removed = false;
                V ret = null;

                synchronized (node) {
                    if (node != (node = getAtIndexAcquire(table, index))) {
                        continue node_loop;
                    }

                    TableEntry<K,V> prev = null;

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
     * is not mapped to a value, then does nothing and returns {@code null}. If the key is mapped to a value that is not
     * equal to {@code expect}, then returns that value.
     * @param key Specified key
     * @param expect Specified expected value
     * @throws NullPointerException If key is null
     * @return The specified expected value if the key was mapped to {@code expect}. If
     *         the key is not mapped to any value, then returns {@code null}. If neither of those cases are true,
     *         then returns the current mapped value for key.
     */
    public V remove(final K key, final V expect) {
        Objects.requireNonNull(key, "Key may not be null");

        final int hash = (HashUtil.mix(key.hashCode()));

        TableEntry<K,V>[] table = this.table;
        table_loop:
        for (;;) {
            final int index = hash & (table.length - 1);

            TableEntry<K,V> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                if (node == null) {
                    return null;
                }

                if (node == RESIZE_NODE) {
                    table = this.fetchNewTable(table);
                    continue table_loop;
                }

                boolean removed = false;
                V ret = null;

                synchronized (node) {
                    if (node != (node = getAtIndexAcquire(table, index))) {
                        continue node_loop;
                    }

                    TableEntry<K,V> prev = null;

                    // plain reads are fine during synchronised access, as we are the only writer
                    for (; node != null; prev = node, node = node.getNextPlain()) {
                        if ((node.key == key || node.key.equals(key))) {
                            ret = node.getValuePlain();
                            if ((expect ==  ret || expect.equals( ret))) {
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
    public static interface RemoveIfPredicate<K,V> {
        public boolean test(final V value);
    }

    /**
     * Atomically removes the mapping for the specified key the predicate returns true for its currently mapped value. If the key
     * is not mapped to a value, then does nothing and returns {@code null}.
     *
     * <p>
     * This function is a "functional method" as defined by {@link ConcurrentChainedObject2ObjectHashTable}.
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
    public V removeIf(final K key, final RemoveIfPredicate<K,? super V> predicate) {
        Objects.requireNonNull(key, "Key may not be null");
        Objects.requireNonNull(predicate, "Predicate may not be null");

        final int hash = (HashUtil.mix(key.hashCode()));

        TableEntry<K,V>[] table = this.table;
        table_loop:
        for (;;) {
            final int index = hash & (table.length - 1);

            TableEntry<K,V> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                if (node == null) {
                    return null;
                }

                if (node == RESIZE_NODE) {
                    table = this.fetchNewTable(table);
                    continue table_loop;
                }

                boolean removed = false;
                V ret = null;

                synchronized (node) {
                    if (node != (node = getAtIndexAcquire(table, index))) {
                        continue node_loop;
                    }

                    TableEntry<K,V> prev = null;

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




    @FunctionalInterface
    public static interface BiObjectObjectObjectFunction<P1,P2,R> {
        public R apply(final P1 key, final P2 value);
    }

    /**
     * See {@link java.util.concurrent.ConcurrentMap#compute(Object, BiFunction)}
     * <p>
     * This function is a "functional method" as defined by {@link ConcurrentChainedObject2ObjectHashTable}.
     * </p>
     */
    public V compute(final K key, final BiObjectObjectObjectFunction<K,? super V,? extends V> function) {
        Objects.requireNonNull(key, "Key may not be null");
        Objects.requireNonNull(function, "Function may not be null");

        final int hash = (HashUtil.mix(key.hashCode()));

        TableEntry<K,V>[] table = this.table;
        table_loop:
        for (;;) {
            final int index = hash & (table.length - 1);

            TableEntry<K,V> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                V ret = null;
                if (node == null) {
                    final TableEntry<K,V> insert = new TableEntry<>(key, null);

                    boolean added = false;

                    synchronized (insert) {
                        if (null == (node = compareAndExchangeAtIndexVolatile(table, index, null, insert))) {
                            try {
                                ret = function.apply(key, null);
                            } catch (final Throwable throwable) {
                                setAtIndexVolatile(table, index, null);
                                ThrowUtil.throwUnchecked(throwable);
                                // unreachable
                                return null;
                            }

                            if (ret == null) {
                                setAtIndexVolatile(table, index, null);
                                return ret;
                            } else {
                                // volatile ordering ensured by addSize(), but we need release here
                                // to ensure proper ordering with reads and other writes
                                insert.setValueRelease(ret);
                                added = true;
                            }
                        } // else: node != null, fall through
                    }

                    if (added) {
                        this.addSize(1L);
                        return ret;
                    }
                }

                if (node == RESIZE_NODE) {
                    table = this.fetchNewTable(table);
                    continue table_loop;
                }

                boolean removed = false;
                boolean added = false;

                synchronized (node) {
                    if (node != (node = getAtIndexAcquire(table, index))) {
                        continue node_loop;
                    }
                    // plain reads are fine during synchronised access, as we are the only writer
                    TableEntry<K,V> prev = null;
                    for (; node != null; prev = node, node = node.getNextPlain()) {
                        if ((node.key == key || node.key.equals(key))) {
                            final V old = node.getValuePlain();

                            final V computed = function.apply(key, old);

                            if (computed != null) {
                                node.setValueVolatile(computed);
                                return computed;
                            }

                            // volatile ordering ensured by addSize(), but we need release here
                            // to ensure proper ordering with reads and other writes
                            if (prev == null) {
                                setAtIndexRelease(table, index, node.getNextPlain());
                            } else {
                                prev.setNextRelease(node.getNextPlain());
                            }

                            removed = true;
                            break;
                        }
                    }

                    if (!removed) {
                        final V computed = function.apply(key, null);
                        if (computed != null) {
                            // volatile ordering ensured by addSize(), but we need release here
                            // to ensure proper ordering with reads and other writes
                            prev.setNextRelease(new TableEntry<>(key, computed));
                            ret = computed;
                            added = true;
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
        }
    }


    @FunctionalInterface
    public static interface BiObjectObjectFunction<K,V> {
        public V apply(final K key);
    }

    /**
     * See {@link java.util.concurrent.ConcurrentMap#computeIfAbsent(Object, Function)}
     * <p>
     * This function is a "functional method" as defined by {@link ConcurrentChainedObject2ObjectHashTable}.
     * </p>
     */
    public V computeIfAbsent(final K key, final BiObjectObjectFunction<? super K,? extends V> function) {
        Objects.requireNonNull(key, "Key may not be null");
        Objects.requireNonNull(function, "Function may not be null");

        final int hash = (HashUtil.mix(key.hashCode()));

        TableEntry<K,V>[] table = this.table;
        table_loop:
        for (;;) {
            final int index = hash & (table.length - 1);

            TableEntry<K,V> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                V ret = null;
                if (node == null) {
                    final TableEntry<K,V> insert = new TableEntry<>(key, null);

                    boolean added = false;

                    synchronized (insert) {
                        if (null == (node = compareAndExchangeAtIndexVolatile(table, index, null, insert))) {
                            try {
                                ret = function.apply(key);
                            } catch (final Throwable throwable) {
                                setAtIndexVolatile(table, index, null);
                                ThrowUtil.throwUnchecked(throwable);
                                // unreachable
                                return null;
                            }

                            if (ret == null) {
                                setAtIndexVolatile(table, index, null);
                                return null;
                            } else {
                                // volatile ordering ensured by addSize(), but we need release here
                                // to ensure proper ordering with reads and other writes
                                insert.setValueRelease(ret);
                                added = true;
                            }
                        } // else: node != null, fall through
                    }

                    if (added) {
                        this.addSize(1L);
                        return ret;
                    }
                }

                if (node == RESIZE_NODE) {
                    table = this.fetchNewTable(table);
                    continue table_loop;
                }

                // optimise ifAbsent calls: check if first node is key before attempting lock acquire
                if ((node.key == key || node.key.equals(key))) {
                    ret = node.getValueVolatile();
                    if (ret != null) {
                        return ret;
                    } // else: fall back to lock to read the node
                }

                boolean added = false;

                synchronized (node) {
                    if (node != (node = getAtIndexAcquire(table, index))) {
                        continue node_loop;
                    }
                    // plain reads are fine during synchronised access, as we are the only writer
                    TableEntry<K,V> prev = null;
                    for (; node != null; prev = node, node = node.getNextPlain()) {
                        if ((node.key == key || node.key.equals(key))) {
                            ret = node.getValuePlain();
                            return ret;
                        }
                    }

                    final V computed = function.apply(key);
                    if (computed != null) {
                        // volatile ordering ensured by addSize(), but we need release here
                        // to ensure proper ordering with reads and other writes
                        prev.setNextRelease(new TableEntry<>(key, computed));
                        ret = computed;
                        added = true;
                    }
                }

                if (added) {
                    this.addSize(1L);
                }

                return ret;
            }
        }
    }

    /**
     * See {@link java.util.concurrent.ConcurrentMap#computeIfPresent(Object, BiFunction)}
     * <p>
     * This function is a "functional method" as defined by {@link ConcurrentChainedObject2ObjectHashTable}.
     * </p>
     */
    public V computeIfPresent(final K key, final BiObjectObjectObjectFunction<K,? super V,? extends V> function) {
        Objects.requireNonNull(key, "Key may not be null");
        Objects.requireNonNull(function, "Function may not be null");

        final int hash = (HashUtil.mix(key.hashCode()));

        TableEntry<K,V>[] table = this.table;
        table_loop:
        for (;;) {
            final int index = hash & (table.length - 1);

            TableEntry<K,V> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                if (node == null) {
                    return null;
                }

                if (node == RESIZE_NODE) {
                    table = this.fetchNewTable(table);
                    continue table_loop;
                }

                boolean removed = false;

                synchronized (node) {
                    if (node != (node = getAtIndexAcquire(table, index))) {
                        continue node_loop;
                    }
                    // plain reads are fine during synchronised access, as we are the only writer
                    TableEntry<K,V> prev = null;
                    for (; node != null; prev = node, node = node.getNextPlain()) {
                        if ((node.key == key || node.key.equals(key))) {
                            final V old = node.getValuePlain();

                            final V computed = function.apply(key, old);

                            if (computed != null) {
                                node.setValueVolatile(computed);
                                return computed;
                            }

                            // volatile ordering ensured by addSize(), but we need release here
                            // to ensure proper ordering with reads and other writes
                            if (prev == null) {
                                setAtIndexRelease(table, index, node.getNextPlain());
                            } else {
                                prev.setNextRelease(node.getNextPlain());
                            }

                            removed = true;
                            break;
                        }
                    }
                }

                if (removed) {
                    this.subSize(1L);
                }

                return null;
            }
        }
    }

    /**
     * See {@link java.util.concurrent.ConcurrentMap#merge(Object, Object, BiFunction)}
     * <p>
     * This function is a "functional method" as defined by {@link ConcurrentChainedObject2ObjectHashTable}.
     * </p>
     */
    public V merge(final K key, final V def, final BiFunction<? super V, ? super V, ? extends V> function) {
        Objects.requireNonNull(key, "Key may not be null");
        Objects.requireNonNull(def, "Default value may not be null");
        Objects.requireNonNull(function, "Function may not be null");

        final int hash = (HashUtil.mix(key.hashCode()));

        TableEntry<K,V>[] table = this.table;
        table_loop:
        for (;;) {
            final int index = hash & (table.length - 1);

            TableEntry<K,V> node = getAtIndexAcquire(table, index);
            node_loop:
            for (;;) {
                if (node == null) {
                    if (null == (node = compareAndExchangeAtIndexVolatile(table, index, null, new TableEntry<>(key, def)))) {
                        // successfully inserted
                        this.addSize(1L);
                        return def;
                    } // else: node != null, fall through
                }

                if (node == RESIZE_NODE) {
                    table = this.fetchNewTable(table);
                    continue table_loop;
                }

                boolean removed = false;
                boolean added = false;
                V ret = null;

                synchronized (node) {
                    if (node != (node = getAtIndexAcquire(table, index))) {
                        continue node_loop;
                    }
                    // plain reads are fine during synchronised access, as we are the only writer
                    TableEntry<K,V> prev = null;
                    for (; node != null; prev = node, node = node.getNextPlain()) {
                        if ((node.key == key || node.key.equals(key))) {
                            final V old = node.getValuePlain();

                            final V computed = function.apply(old, def);

                            if (computed != null) {
                                node.setValueVolatile(computed);
                                return computed;
                            }

                            // volatile ordering ensured by addSize(), but we need release here
                            // to ensure proper ordering with reads and other writes
                            if (prev == null) {
                                setAtIndexRelease(table, index, node.getNextPlain());
                            } else {
                                prev.setNextRelease(node.getNextPlain());
                            }

                            removed = true;
                            break;
                        }
                    }

                    if (!removed) {
                        // volatile ordering ensured by addSize(), but we need release here
                        // to ensure proper ordering with reads and other writes
                        prev.setNextRelease(new TableEntry<>(key, def));
                        ret = def;
                        added = true;
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
        final NodeIterator<K,V> nodeIterator = new NodeIterator<>(this);

        TableEntry<K,V> node;
        while ((node = nodeIterator.findNext()) != null) {
            this.remove(node.key);
        }
    }

    /**
     * Returns an iterator over the entries in this map. The iterator is only guaranteed to see entries that were
     * added before the beginning of this call, but it may see entries added during.
     */
    public BaseObjectIterator<TableEntry<K,V>> entryIterator() {
        return new EntryNodeIterator<>(this);
    }

    @Override
    public final BaseObjectIterator<TableEntry<K,V>> iterator() {
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
    public BaseObjectIterator<V> valueIterator() {
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
        protected final ConcurrentChainedObject2ObjectHashTable<K,?> map;

        protected Keys(final ConcurrentChainedObject2ObjectHashTable<K,?> map) {
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

    protected Values<V> values;
    public Collection<V> values() {
        final Values<V> values = this.values;
        if (values != null) {
            return values;
        }
        return this.values = new Values<>(this);
    }

    protected static class Values<V> extends BaseMapCollection<V> {
        protected final ConcurrentChainedObject2ObjectHashTable<?,V> map;

        protected Values(final ConcurrentChainedObject2ObjectHashTable<?,V> map) {
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
            final List<V> ret = new ArrayList<>(this.map.size());

            for (final V element : this) {
                ret.add(element);
            }

            return ret;
        }

        @Override
        public BaseObjectIterator<V> iterator() {
            return this.map.valueIterator();
        }
    }

    protected EntrySet<K,V> entrySet;
    public Set<TableEntry<K,V>> entrySet() {
        final EntrySet<K,V> entrySet = this.entrySet;
        if (entrySet != null) {
            return entrySet;
        }
        return this.entrySet = new EntrySet<>(this);
    }

    protected static class EntrySet<K,V> extends BaseMapSet<TableEntry<K,V>> {
        protected final ConcurrentChainedObject2ObjectHashTable<K,V> map;

        protected EntrySet(final ConcurrentChainedObject2ObjectHashTable<K,V> map) {
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
            if (!(value instanceof ConcurrentChainedObject2ObjectHashTable.TableEntry<?,?> entry)) {
                return false;
            }

            final Object entryKey = entry.getKey();
            final Object entryValue = entry.getValue();

            if (entryValue == null) {
                // placeholder entry: not mapped
                return false;
            }
            return this.map.contains((K)entryKey, (V)entryValue);
        }

        @Override
        protected List<TableEntry<K,V>> asList() {
            final List<TableEntry<K,V>> ret = new ArrayList<>(this.map.size());

            for (final TableEntry<K,V> element : this) {
                ret.add(element);
            }

            return ret;
        }

        @Override
        public BaseObjectIterator<TableEntry<K,V>> iterator() {
            return this.map.entryIterator();
        }
    }

    protected static final class EntryNodeIterator<K,V> extends BaseObjectIterator<TableEntry<K,V>> {
        protected TableEntry<K,V> lastReturned;
        protected TableEntry<K,V> nextToReturn;
        protected final NodeIterator<K,V> nodeIterator;

        protected EntryNodeIterator(final ConcurrentChainedObject2ObjectHashTable<K,V> map) {
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
        public TableEntry<K,V> next() throws NoSuchElementException {
            TableEntry<K,V> ret = this.nextToReturn;
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
            final TableEntry<K,V> lastReturned = this.lastReturned;
            if (lastReturned == null) {
                throw new NoSuchElementException();
            }
            this.lastReturned = null;
            this.nodeIterator.map.remove(lastReturned.key);
        }
    }

    protected static final class KeyIterator<K,V> extends BaseObjectIterator<K> {

        private final EntryNodeIterator<K,V> iterator;

        public KeyIterator(final ConcurrentChainedObject2ObjectHashTable<K,V> map) {
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

    protected static final class ValueIterator<K,V> extends BaseObjectIterator<V> {

        private final EntryNodeIterator<K,V> iterator;

        public ValueIterator(final ConcurrentChainedObject2ObjectHashTable<K,V> map) {
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
        public V next() {
            return this.iterator.next().getValueVolatile();
        }
    }

    protected static class NodeIterator<K,V> {

        protected final ConcurrentChainedObject2ObjectHashTable<K,V> map;
        private TableEntry<K,V>[] currentTable;
        private ResizeChain<K,V> resizeChain;
        private TableEntry<K,V> last;
        private int nextBin;
        private int increment;

        protected NodeIterator(final ConcurrentChainedObject2ObjectHashTable<K,V> map) {
            this.map = map;
            this.currentTable = map.table;
            this.increment = 1;
        }

        private TableEntry<K,V>[] pullResizeChain(final int index) {
            final ResizeChain<K,V> resizeChain = this.resizeChain;
            if (resizeChain == null) {
                this.currentTable = null;
                return null;
            }

            final ResizeChain<K,V> prevChain = resizeChain.prev;
            this.resizeChain = prevChain;
            if (prevChain == null) {
                this.currentTable = null;
                return null;
            }

            final TableEntry<K,V>[] newTable = prevChain.table;

            // we recover the original index by modding by the new table length, as the increments applied to the index
            // are a multiple of the new table's length
            int newIdx = index & (newTable.length - 1);

            // the increment is always the previous table's length
            final ResizeChain<K,V> nextPrevChain = prevChain.prev;
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

        private TableEntry<K,V>[] pushResizeChain(final TableEntry<K,V>[] table) {
            final ResizeChain<K,V> chain = this.resizeChain;

            if (chain == null) {
                final TableEntry<K,V>[] nextTable = this.map.fetchNewTable(table);

                final ResizeChain<K,V> oldChain = new ResizeChain<>(table, null, null);
                final ResizeChain<K,V> currChain = new ResizeChain<>(nextTable, oldChain, null);
                oldChain.next = currChain;

                this.increment = table.length;
                this.resizeChain = currChain;
                this.currentTable = nextTable;

                return nextTable;
            } else {
                ResizeChain<K,V> currChain = chain.next;
                if (currChain == null) {
                    final TableEntry<K,V>[] ret = this.map.fetchNewTable(table);
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

        protected final TableEntry<K,V> findNext() {
            for (;;) {
                final TableEntry<K,V> last = this.last;
                if (last != null) {
                    final TableEntry<K,V> next = last.getNextVolatile();
                    if (next != null) {
                        this.last = next;
                        if (next.getValuePlain() == null) {
                            // compute() node not yet available
                            continue;
                        }
                        return next;
                    }
                }

                TableEntry<K,V>[] table = this.currentTable;

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

                    final TableEntry<K,V> entry = getAtIndexAcquire(table, idx);
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
                    if (entry.getValuePlain() == null) {
                        // compute() node not yet available
                        break;
                    }
                    return entry;
                }
            }
        }

        private static final class ResizeChain<K,V> {

            public final TableEntry<K,V>[] table;
            public final ResizeChain<K,V> prev;
            public ResizeChain<K,V> next;

            public ResizeChain(final TableEntry<K,V>[] table, final ResizeChain<K,V> prev, final ResizeChain<K,V> next) {
                this.table = table;
                this.prev = prev;
                this.next = next;
            }
        }
    }

    public static final class TableEntry<K,V> {

        private static final VarHandle TABLE_ENTRY_ARRAY_HANDLE = ConcurrentUtil.getArrayHandle(TableEntry[].class);

        private final K key;

        private volatile V value;
        private static final VarHandle VALUE_HANDLE = ConcurrentUtil.getVarHandle(TableEntry.class, "value", Object.class);

        private V getValuePlain() {
            //noinspection unchecked
            return (V)VALUE_HANDLE.get(this);
        }

        private V getValueAcquire() {
            //noinspection unchecked
            return (V)VALUE_HANDLE.getAcquire(this);
        }

        private V getValueVolatile() {
            //noinspection unchecked
            return (V)VALUE_HANDLE.getVolatile(this);
        }

        private void setValuePlain(final V value) {
            VALUE_HANDLE.set(this, (Object)value);
        }

        private void setValueRelease(final V value) {
            VALUE_HANDLE.setRelease(this, (Object)value);
        }

        private void setValueVolatile(final V value) {
            VALUE_HANDLE.setVolatile(this, (Object)value);
        }

        private volatile TableEntry<K,V> next;
        private static final VarHandle NEXT_HANDLE = ConcurrentUtil.getVarHandle(TableEntry.class, "next", TableEntry.class);

        private TableEntry<K,V> getNextPlain() {
            //noinspection unchecked
            return (TableEntry<K,V>)NEXT_HANDLE.get(this);
        }

        private TableEntry<K,V> getNextAcquire() {
            //noinspection unchecked
            return (TableEntry<K,V>)NEXT_HANDLE.getAcquire(this);
        }

        private TableEntry<K,V> getNextVolatile() {
            //noinspection unchecked
            return (TableEntry<K,V>)NEXT_HANDLE.getVolatile(this);
        }

        private void setNextPlain(final TableEntry<K,V> next) {
            NEXT_HANDLE.set(this, next);
        }

        private void setNextRelease(final TableEntry<K,V> next) {
            NEXT_HANDLE.setRelease(this, next);
        }

        private void setNextVolatile(final TableEntry<K,V> next) {
            NEXT_HANDLE.setVolatile(this, next);
        }

        protected TableEntry(final K key, final V value) {
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
}
