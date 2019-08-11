package com.thinkaurelius.titan.diskstorage.keycolumnvalue.keyvalue;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.thinkaurelius.titan.diskstorage.*;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.*;
import com.thinkaurelius.titan.diskstorage.util.*;

import com.thinkaurelius.titan.graphdb.query.BaseQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.util.*;

/**
 * Wraps a {@link OrderedKeyValueStore} and exposes it as a {@link KeyColumnValueStore}.
 * <p/>
 * An optional key length parameter can be specified if it is known and guaranteed that all keys
 * passed into and read through the {@link KeyColumnValueStore} have that length. If this length is
 * static, specifying that length will make the representation of a {@link KeyColumnValueStore} in a {@link OrderedKeyValueStore}
 * more concise and more performant.
 *
 * @author Matthias Br&ouml;cheler (me@matthiasb.com);
 */
public class OrderedKeyValueStoreAdapter extends BaseKeyColumnValueAdapter {

    private final Logger log = LoggerFactory.getLogger(OrderedKeyValueStoreAdapter.class);

    public static final int variableKeyLength = 0;

    public static final int maxVariableKeyLength = Short.MAX_VALUE;
    public static final int variableKeyLengthSize = 2;

    private final OrderedKeyValueStore store;
    private final int keyLength;

    public OrderedKeyValueStoreAdapter(OrderedKeyValueStore store) {
        this(store, variableKeyLength);
    }

    public OrderedKeyValueStoreAdapter(OrderedKeyValueStore store, int keyLength) {
        super(store);
        Preconditions.checkNotNull(store);
        Preconditions.checkArgument(keyLength >= 0);
        this.store = store;
        this.keyLength = keyLength;
        log.debug("Used key length {} for database {}", keyLength, store.getName());
    }

    @Override
    public EntryList getSlice(KeySliceQuery query, StoreTransaction txh) throws BackendException {
        return convert(store.getSlice(convertQuery(query), txh));
    }

    @Override
    public Map<StaticBuffer,EntryList> getSlice(List<StaticBuffer> keys, SliceQuery query, StoreTransaction txh) throws BackendException {
        List<KVQuery> queries = new ArrayList<KVQuery>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            queries.add(convertQuery(new KeySliceQuery(keys.get(i),query)));
        }
        Map<KVQuery,RecordIterator<KeyValueEntry>> results = store.getSlices(queries,txh);
        Map<StaticBuffer,EntryList> convResults = new HashMap<StaticBuffer, EntryList>(keys.size());
        assert queries.size()==keys.size();
        for (int i = 0; i < queries.size(); i++) {
            convResults.put(keys.get(i),convert(results.get(queries.get(i))));
        }
        return convResults;
    }

    @Override
    public void mutate(StaticBuffer key, List<Entry> additions, List<StaticBuffer> deletions, StoreTransaction txh) throws BackendException {
        if (!deletions.isEmpty()) {
            for (StaticBuffer deletion : deletions) {
                StaticBuffer del = concatenate(key, deletion.as(StaticBuffer.STATIC_FACTORY));
                store.delete(del, txh);
            }

        }
        if (!additions.isEmpty()) {
            for (Entry entry : additions) {
                StaticBuffer newkey = concatenate(key, entry.getColumnAs(StaticBuffer.STATIC_FACTORY));
                store.insert(newkey, entry.getValueAs(StaticBuffer.STATIC_FACTORY), txh);
            }
        }
    }


    @Override
    public KeyIterator getKeys(final KeyRangeQuery keyQuery, final StoreTransaction txh) throws BackendException {
        KVQuery query = new KVQuery(
                concatenatePrefix(adjustToLength(keyQuery.getKeyStart()), keyQuery.getSliceStart()),
                concatenatePrefix(adjustToLength(keyQuery.getKeyEnd()), keyQuery.getSliceEnd()),
                new Predicate<StaticBuffer>() {
                    @Override
                    public boolean apply(@Nullable StaticBuffer keycolumn) {
                        StaticBuffer key = getKey(keycolumn);
                        return !(key.compareTo(keyQuery.getKeyStart()) < 0 || key.compareTo(keyQuery.getKeyEnd()) >= 0)
                                && columnInRange(keycolumn, keyQuery.getSliceStart(), keyQuery.getSliceEnd());
                    }
                },
                BaseQuery.NO_LIMIT); //limit will be introduced in iterator

        return new KeyIteratorImpl(keyQuery,store.getSlice(query,txh));
    }

    private final StaticBuffer adjustToLength(StaticBuffer key) {
        if (hasFixedKeyLength() && key.length()!=keyLength) {
            if (key.length()>keyLength) {
                return key.subrange(0,keyLength);
            } else { //Append 0s
                return BufferUtil.padBuffer(key,keyLength);
            }
        }
        return key;
    }



    @Override
    public KeyIterator getKeys(SliceQuery columnQuery, StoreTransaction txh) throws BackendException {
        throw new UnsupportedOperationException("This store has ordered keys, use getKeys(KeyRangeQuery, StoreTransaction) instead");
    }

    @Override
    public void acquireLock(StaticBuffer key, StaticBuffer column, StaticBuffer expectedValue,
                            StoreTransaction txh) throws BackendException {
        store.acquireLock(concatenate(key, column), expectedValue, txh);
    }

    private EntryList convert(RecordIterator<KeyValueEntry> entries) throws BackendException {
        try {
            return StaticArrayEntryList.ofStaticBuffer(entries,kvEntryGetter);
        } finally {
            try {
                entries.close();
            } catch (IOException e) {
            /*
             * IOException could be permanent or temporary. Choosing temporary
             * allows useful retries of transient failures but also allows
             * futile retries of permanent failures.
             */
                throw new TemporaryBackendException(e);
            }
        }
    }

    private final StaticArrayEntry.GetColVal<KeyValueEntry,StaticBuffer> kvEntryGetter = new StaticArrayEntry.GetColVal<KeyValueEntry,StaticBuffer>() {

        @Override
        public StaticBuffer getColumn(KeyValueEntry element) {
            return getColumnFromKey(element.getKey());
        }

        @Override
        public StaticBuffer getValue(KeyValueEntry element) {
            return element.getValue();
        }

        @Override
        public EntryMetaData[] getMetaSchema(KeyValueEntry element) {
            return StaticArrayEntry.EMPTY_SCHEMA;
        }

        @Override
        public Object getMetaData(KeyValueEntry element, EntryMetaData meta) {
            throw new UnsupportedOperationException("Unsupported meta data: " + meta);
        }
    };

    private Entry getEntry(KeyValueEntry entry) {
        return StaticArrayEntry.ofStaticBuffer(entry,kvEntryGetter);
    }

    private boolean hasFixedKeyLength() {
        return keyLength > 0;
    }

    private int getLength(StaticBuffer key) {
        int length = keyLength;
        if (hasFixedKeyLength()) { //fixed key length
            Preconditions.checkArgument(key.length() == length);
        } else { //variable key length
            length = key.length();
            Preconditions.checkArgument(length < maxVariableKeyLength);
        }
        return length;
    }

    final KeyValueEntry concatenate(StaticBuffer front, Entry entry) {
        return new KeyValueEntry(concatenate(front, entry.getColumnAs(StaticBuffer.STATIC_FACTORY)),
                entry.getValueAs(StaticBuffer.STATIC_FACTORY));
    }

    final KVQuery convertQuery(final KeySliceQuery query) {
        Predicate<StaticBuffer> filter = Predicates.alwaysTrue();
        if (!hasFixedKeyLength()) filter = new Predicate<StaticBuffer>() {
            @Override
            public boolean apply(@Nullable StaticBuffer keyAndColumn) {
                return equalKey(keyAndColumn, query.getKey());
            }
        };
        return new KVQuery(
                concatenatePrefix(query.getKey(), query.getSliceStart()),
                concatenatePrefix(query.getKey(), query.getSliceEnd()),
                filter,query.getLimit());
    }

    final StaticBuffer concatenate(StaticBuffer front, StaticBuffer end) {
        return concatenate(front, end, true);
    }

    private StaticBuffer concatenatePrefix(StaticBuffer front, StaticBuffer end) {
        return concatenate(front, end, false);
    }

    private StaticBuffer concatenate(StaticBuffer front, StaticBuffer end, final boolean appendLength) {
        final boolean addKeyLength = !hasFixedKeyLength() && appendLength;
        int length = getLength(front);

        byte[] result = new byte[length + end.length() + (addKeyLength ? variableKeyLengthSize : 0)];
        int position = 0;
        for (int i = 0; i < front.length(); i++) result[position++] = front.getByte(i);
        for (int i = 0; i < end.length(); i++) result[position++] = end.getByte(i);

        if (addKeyLength) {
            result[position++] = (byte) (length >>> 8);
            result[position++] = (byte) length;
        }
        return StaticArrayBuffer.of(result);
    }

    private StaticBuffer getColumnFromKey(StaticBuffer concat) {
        int offset = getKeyLength(concat);
        int length = concat.length() - offset;
        if (!hasFixedKeyLength()) { //variable key length => remove length at end
            length -= variableKeyLengthSize;
        }
        return concat.subrange(offset, length);
    }

    private int getKeyLength(StaticBuffer concat) {
        int length = keyLength;
        if (!hasFixedKeyLength()) { //variable key length
            length = concat.getShort(concat.length() - variableKeyLengthSize);
        }
        return length;
    }

    private StaticBuffer getKey(StaticBuffer concat) {
        return concat.subrange(0, getKeyLength(concat));
    }

    private boolean equalKey(StaticBuffer concat, StaticBuffer key) {
        int keylen = getKeyLength(concat);
        for (int i = 0; i < keylen; i++) if (concat.getByte(i) != key.getByte(i)) return false;
        return true;
    }

    private boolean columnInRange(StaticBuffer concat, StaticBuffer columnStart, StaticBuffer columnEnd) {
        StaticBuffer column = getColumnFromKey(concat);
        return column.compareTo(columnStart) >= 0 && column.compareTo(columnEnd) < 0;
    }

    private class KeyIteratorImpl implements KeyIterator {

        private final KeyRangeQuery query;
        private final RecordIterator<KeyValueEntry> iter;

        private StaticBuffer currentKey = null;
        private EntryIterator currentIter = null;
        private boolean currentKeyReturned = true;
        private KeyValueEntry current;

        private KeyIteratorImpl(KeyRangeQuery query, RecordIterator<KeyValueEntry> iter) {
            this.query = query;
            this.iter = iter;
        }

        private StaticBuffer nextKey() throws BackendException {
            while (iter.hasNext()) {
                current = iter.next();
                StaticBuffer key = getKey(current.getKey());
                if (currentKey == null || !key.equals(currentKey)) {
                    return key;
                }
            }
            return null;
        }

        @Override
        public RecordIterator<Entry> getEntries() {
            Preconditions.checkNotNull(currentIter);
            return currentIter;
        }

        @Override
        public boolean hasNext() {
            if (currentKeyReturned) {
                try {
                    currentKey = nextKey();
                } catch (BackendException e) {
                    throw new RuntimeException(e);
                }
                currentKeyReturned = false;

                if (currentIter != null)
                    currentIter.close();

                currentIter = new EntryIterator();
            }

            return currentKey != null;
        }

        @Override
        public StaticBuffer next() {
            if (!hasNext())
                throw new NoSuchElementException();

            currentKeyReturned = true;
            return currentKey;
        }

        @Override
        public void close() throws IOException {
            iter.close();
        }

        private class EntryIterator implements RecordIterator<Entry>, Closeable {
            private boolean open = true;
            private int count = 0;

            @Override
            public boolean hasNext() {
                Preconditions.checkState(open);

                if (current == null || count >= query.getLimit())
                    return false;

                // We need to check what is "current" right now and notify parent iterator
                // about change of main key otherwise we would be missing portion of the results
                StaticBuffer nextKey = getKey(current.getKey());
                if (!nextKey.equals(currentKey)) {
                    currentKey = nextKey;
                    currentKeyReturned = false;
                    return false;
                }

                return true;
            }

            @Override
            public Entry next() {
                Preconditions.checkState(open);

                if (!hasNext())
                    throw new NoSuchElementException();

                Entry kve = getEntry(current);
                current = iter.hasNext() ? iter.next() : null;
                count++;

                return kve;
            }

            @Override
            public void close() {
                open = false;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
}
