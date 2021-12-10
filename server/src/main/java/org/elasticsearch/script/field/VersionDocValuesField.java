/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
// CS427 issue link: https://github.com/nextcloud/android/issues/9102
package org.elasticsearch.script.field;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.elasticsearch.index.fielddata.ScriptDocValues;
import org.elasticsearch.index.fielddata.SortedBinaryDocValues;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

public class VersionDocValuesField implements DocValuesField<String>, ScriptDocValues.Supplier<String>{


    private final String name;
    private final SortedBinaryDocValues input;

    private BytesRefBuilder[] values = new BytesRefBuilder[0];
    private int count;

    // used for backwards compatibility for old-style "doc" access
    // as a delegate to this field class
    private ScriptDocValues.Strings strings = null;

    /**
     * Constructor that sets the name and input of a version
     * @param input SortedBinaryDocValue
     * @param name name of version
     */
    public VersionDocValuesField(SortedBinaryDocValues input, String name) {
        this.name = name;
        this.input = input;

    }
    private ScriptDocValues.Strings getScriptDocValuesStrings() {
        return strings;
    }
    @Override
    public void setNextDocId(int docId) throws IOException {
        if (input.advanceExact(docId)) {
            resize(input.docValueCount());
            for (int i = 0; i < count; i++) {
                //have to copy because SortedBinaryDocValues reuses returned bytesref
                values[i].copyBytes(input.nextValue());
            }
        } else {
            resize(0);
        }

    }

    /**
     * Resize array to fit the amount of docs in the given input
     * @param newSize newSize to increase array to
     */
    protected void resize(int newSize) {
        count = newSize;
        assert count >= 0 : "size must be positive (got " + count + "): likely integer overflow?";
        if (newSize > values.length) {
            final int oldLength = values.length;
            values = ArrayUtil.grow(values, count);
            for (int i = oldLength; i < values.length; ++i) {
                values[i] = new BytesRefBuilder();
            }
        }
    }

    /**
     * Support boolean return values for old-style "doc" access
     * @param index index of docs
     * @return string value at specified index
     */
    @Override
    public String getInternal(int index) {
        return bytesToString(values[index].toBytesRef());
    }

    /**
     * Converts bytes to string
     * @param bytesRef bytes reference
     * @return bytes converted to string
     */
    protected String bytesToString(BytesRef bytesRef) {
        return bytesRef.utf8ToString();
    }

    @Override
    public ScriptDocValues<String> getScriptDocValues() {
        if (strings == null) {
            strings = new ScriptDocValues.Strings(this);
        }
        return getScriptDocValuesStrings();
    }

    public String asString(String defaultValue) {
        return asString(0, defaultValue);
    }

    public String asString(int index, String defaultValue) {
        if (isEmpty() || index < 0 || index >= size()) {
            return defaultValue;
        }

        return getInternal(index);
    }

    public List<String> asStrings() {
        if (isEmpty()) {
            return Collections.emptyList();
        }

        List<String> values = new ArrayList<>(size());
        for (int i = 0; i < size(); i++) {
            values.add(getInternal(i));
        }

        return values;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public int size() {
        return count;
    }
    public String get(String defaultValue) {
        return get(0, defaultValue);
    }

    public String get(int index, String defaultValue) {
        if (isEmpty() || index < 0 || index >= count) {
            return defaultValue;
        }

        return bytesToString(values[index].toBytesRef());
    }

    /**
     * Iterator to go through docs
     * @return iterator
     */
    @Override
    public Iterator<String> iterator() {
        return new Iterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < count;
            }

            @Override
            public String next() {
                if (hasNext() == false) {
                    throw new NoSuchElementException();
                }
                return bytesToString(values[index++].toBytesRef());
            }
        };
    }

}
