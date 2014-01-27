package org.jikesrvm.cha;

import org.jikesrvm.runtime.Magic;

import java.util.AbstractMap;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
// import gnu.java.util.WeakIdentityHashMap;
import java.util.concurrent.ConcurrentMap;

public class ConcurrentWeakIdentityHashMap<K, V> extends AbstractMap<K, V> implements
		ConcurrentMap<K, V> {
	/* ---------------- Constants -------------- */
	/**
	 * The default initial capacity for this table, used when not otherwise
	 * specified in a constructor.
	 */
	static final int DEFAULT_INITIAL_CAPACITY = 16;

	/**
	 * The default load factor for this table, used when not otherwise specified
	 * in a constructor.
	 */
	static final float DEFAULT_LOAD_FACTOR = 0.75f;

	/**
	 * The default concurrency level for this table, used when not otherwise
	 * specified in a constructor.
	 */
	static final int DEFAULT_CONCURRENCY_LEVEL = 16;

	/**
	 * The maximum capacity, used if a higher value is implicitly specified by
	 * either of the constructors with arguments. MUST be a power of two <=
	 * 1<<30 to ensure that entries are indexable using ints.
	 */
	static final int MAXIMUM_CAPACITY = 1 << 30;

	/**
	 * The maximum number of segments to allow; used to bound constructor
	 * arguments.
	 */
	static final int MAX_SEGMENTS = 1 << 16; // slightly conservative

	/* ---------------- Fields -------------- */

	/**
	 * Mask value for indexing into segments. The upper bits of a key's hash
	 * code are used to choose the segment.
	 */
	final int segmentMask;

	/**
	 * Shift value for indexing within segments.
	 */
	final int segmentShift;

	final WeakIdentityHashMap<K, V>[] segments;

	transient Set<K> keySet;

	/**
	 * Generate hash code for input object in order to map it to different
	 * segments.
	 */
	private static int hash(Object o) {
		if (o == null)
			return 0;
    // Problem: this can be triggered before the object is ready to
    //          have its hashCode() method called
		// int hashCode = o.hashCode();

    int hashCode;
    if (!org.jikesrvm.VM.runningVM) {
      hashCode = Magic.bootImageIdentityHashCode(o);
    } else {
      hashCode = System.identityHashCode(o);
    }
    /*
		hashCode ^= (hashCode << 7);
		hashCode ^= (hashCode >>> 3);
		hashCode ^= (hashCode << 27);
		hashCode ^= (hashCode >>> 15);
    */
		return hashCode;
	}

	/**
	 * Returns the segment that should be used for key with given hash
	 * 
	 * @param hash
	 *            the hash code for the key
	 * @return the segment
	 */
	final private WeakIdentityHashMap<K, V> segmentFor(int hash) {
		return segments[(hash >>> segmentShift) & segmentMask];
	}

	/**
	 * Constructs a new, empty HashMap with the specified initial capacity and
	 * the specified load factor.
	 * 
	 * @param initialCapacity
	 *            the initial capacity of the HashMap.
	 * @param loadFactor
	 *            a number between 0.0 and 1.0.
	 * @param concurrencyLevel
	 *            the estimated number of concurrently updating threads. The
	 *            implementation performs internal sizing to try to accommodate
	 *            this many threads.
	 * @throws IllegalArgumentException
	 *             if neither keys nor values use hard references, if the
	 *             initial capacity is less than or equal to zero, or if the
	 *             load factor is less than or equal to zero
	 */
	@SuppressWarnings("unchecked")
	public ConcurrentWeakIdentityHashMap(int initialCapacity, float loadFactor,
			int concurrencyLevel) {
		if (initialCapacity < 0) {
			throw new IllegalArgumentException("Illegal Initial Capacity: "
					+ initialCapacity);
		}

		if ((loadFactor >= 1) || (loadFactor <= 0)) {
			throw new IllegalArgumentException("Illegal Load factor: "
					+ loadFactor);
		}

		if (concurrencyLevel <= 1)
			throw new IllegalArgumentException("Illegal concurrencyLevel: "
					+ concurrencyLevel);

		if (concurrencyLevel > MAX_SEGMENTS)
			concurrencyLevel = MAX_SEGMENTS;

		// Find power-of-two sizes best matching arguments
		int sshift = 0;
		int ssize = 1;
		while (ssize < concurrencyLevel) {
			++sshift;
			ssize <<= 1;
		}
		segmentShift = 32 - sshift;
		segmentMask = ssize - 1;

		this.segments = new WeakIdentityHashMap[ssize];

		if (initialCapacity > MAXIMUM_CAPACITY)
			initialCapacity = MAXIMUM_CAPACITY;
		int c = initialCapacity / ssize;
		if (c * ssize < initialCapacity)
			++c;
		int cap = 1;
		while (cap < c)
			cap <<= 1;

		for (int i = 0; i < this.segments.length; ++i)
			this.segments[i] = new WeakIdentityHashMap<K, V>(cap, loadFactor);
	}

	/**
	 * Constructs a new, empty HashMap with the specified initial capacity and
	 * default load factor.
	 * 
	 * @param initialCapacity
	 *            the initial capacity of the HashMap.
	 */
	public ConcurrentWeakIdentityHashMap(int initialCapacity) {
		this(initialCapacity, DEFAULT_LOAD_FACTOR, DEFAULT_CONCURRENCY_LEVEL);
	}

	/**
	 * Constructs a new, empty HashMap with a default capacity and load factor.
	 */
	public ConcurrentWeakIdentityHashMap() {
		this(32, DEFAULT_LOAD_FACTOR, DEFAULT_CONCURRENCY_LEVEL);
	}

	@Override
	public V put(K key, V value) {
		int hash = hash(key);
		WeakIdentityHashMap<K, V> whm = segmentFor(hash);
		synchronized (whm) {
			return whm.put(key, value);
		}
	}

	/**
	 * Returns the value to which this HashMap maps the specified key. Returns
	 * null if the HashMap contains no mapping for this key.
	 * 
	 * @param key
	 *            key whose associated value is to be returned.
	 */
	@Override
	public V get(Object key) {
		int hash = hash(key);
		WeakIdentityHashMap<K, V> whm = segmentFor(hash);
		//synchronized (whm) {
		return whm.get(key);
		//}
	}

	@Override
	public V remove(Object key) {
		int hash = hash(key);
		WeakIdentityHashMap<K, V> whm = segmentFor(hash);
		synchronized (whm) {
			return whm.remove(key);
		}
	}

	@Override
	public boolean remove(Object key, Object value) {
		int hash = hash(key);
		WeakIdentityHashMap<K, V> whm = segmentFor(hash);
		synchronized (whm) {
			if (whm.containsKey(key) && (whm.get(key) == value)) { // (whm.get(key).equals(value))) {
				whm.remove(key);
				return true;
			}
		}
		return false;
	}

	/**
	 * This function is not accurate under concurrent environment.
	 * 
	 */
	public int size() {
		int sum = 0;
		for (int i = 0; i < segments.length; i++) {
			sum += segments[i].size();
		}

		return sum;
	}

	public boolean containsKey(Object key) {
		int hash = hash(key);
		WeakIdentityHashMap<K, V> whm = segmentFor(hash);
		synchronized (whm) {
			return whm.containsKey(key);
		}
	}

	/**
	 * Clear all mappings from all segment, leaving the ConcurrentWeakIdentityHashMap
	 * empty.
	 */
	public void clear() {
		for (int i = 0; i < segments.length; i++) {
			WeakIdentityHashMap<K, V> whm = segments[i];
			synchronized (whm) {
				whm.clear();
			}
		}
	}

	@SuppressWarnings("unchecked")
	public Set<K> keySet() {
    synchronized (this) {
      if (true) { // keySet == null) {
			keySet = new java.util.AbstractSet() {
				public Iterator iterator() {
					return new HashIterator();
				}

				public int size() {
					return ConcurrentWeakIdentityHashMap.this.size();
				}

				public boolean contains(Object o) {
					return containsKey(o);
				}

				public boolean remove(Object o) {
					return ConcurrentWeakIdentityHashMap.this.remove(o) != null;
				}

				public void clear() {
					ConcurrentWeakIdentityHashMap.this.clear();
				}
			};
		}
    }
		return keySet;
	}

	class HashIterator implements Iterator<K> {
		int currSegmentIndex = 0;
		Iterator<K> internal_iter = segments[0].keySet().iterator();

		HashIterator() {
		}

		final boolean advance() {
			currSegmentIndex++;
			if (currSegmentIndex < segments.length) {
				internal_iter = segments[currSegmentIndex].keySet().iterator();
				return true;
			} else
				return false;
		}

		public boolean hasNext() {
			if (currSegmentIndex >= segments.length)
				return false;
			else {
				boolean ret = internal_iter.hasNext();
				while (!ret && advance()) {
					ret = internal_iter.hasNext();
				}
				return ret;
			}
		}

		public K next() {
			try {
				return internal_iter.next();
			} catch (NoSuchElementException e) {
				if (advance())
					return internal_iter.next();
				else
					throw new NoSuchElementException();
			}
		}

		public void remove() {
			ConcurrentWeakIdentityHashMap.this.remove(next());
		}
	}

	@Override
	public V putIfAbsent(K key, V value) {
		int hash = hash(key);
		WeakIdentityHashMap<K, V> whm = segmentFor(hash);
		synchronized (whm) {
			if (!whm.containsKey(key)) {
				return whm.put(key, value);
			} else
				return whm.get(key);
			}
	}

	@Override
	public boolean replace(K key, V oldValue, V newValue) {
		int hash = hash(key);
		WeakIdentityHashMap<K, V> whm = segmentFor(hash);
		synchronized (whm) {
			if (whm.containsKey(key) && (whm.get(key) == oldValue)) { // (whm.get(key).equals(oldValue))) {
				whm.put(key, newValue);
				return true;
			}
		}
		return false;
	}

	@Override
	public V replace(K key, V value) {
		int hash = hash(key);
		WeakIdentityHashMap<K, V> whm = segmentFor(hash);
		synchronized (whm) {
			if (whm.containsKey(key)) {
				return whm.put(key, value);
			}
		}
		return null;
	}

	@Override
	public Set<java.util.Map.Entry<K, V>> entrySet() {
		// TODO:
		return null;
	}
}
