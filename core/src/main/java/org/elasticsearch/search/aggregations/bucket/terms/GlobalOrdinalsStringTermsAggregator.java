/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.aggregations.bucket.terms;

import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.RandomAccessOrds;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.LongBitSet;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.lease.Releasables;
import org.elasticsearch.common.util.IntArray;
import org.elasticsearch.common.util.LongHash;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.fielddata.AbstractRandomAccessOrds;
import org.elasticsearch.index.fielddata.ordinals.GlobalOrdinalMapping;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.AggregatorFactories;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.aggregations.LeafBucketCollector;
import org.elasticsearch.search.aggregations.LeafBucketCollectorBase;
import org.elasticsearch.search.aggregations.bucket.terms.support.BucketPriorityQueue;
import org.elasticsearch.search.aggregations.bucket.terms.support.IncludeExclude;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.elasticsearch.search.aggregations.support.ValuesSource;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * An aggregator of string values that relies on global ordinals in order to build buckets.
 */
public class GlobalOrdinalsStringTermsAggregator extends AbstractStringTermsAggregator {

    protected final ValuesSource.Bytes.WithOrdinals valuesSource;
    protected final IncludeExclude.OrdinalsFilter includeExclude;

    // TODO: cache the acceptedglobalValues per aggregation definition.
    // We can't cache this yet in ValuesSource, since ValuesSource is reused per field for aggs during the execution.
    // If aggs with same field, but different include/exclude are defined, then the last defined one will override the
    // first defined one.
    // So currently for each instance of this aggregator the acceptedglobalValues will be computed, this is unnecessary
    // especially if this agg is on a second layer or deeper.
    protected final LongBitSet acceptedGlobalOrdinals;
    protected final long valueCount;
    protected final GlobalOrdLookupFunction lookupGlobalOrd;

    private final LongHash bucketOrds;

    public interface GlobalOrdLookupFunction {
        BytesRef apply(long ord) throws IOException;
    }

    public GlobalOrdinalsStringTermsAggregator(String name, AggregatorFactories factories,
                                               ValuesSource.Bytes.WithOrdinals valuesSource,
                                               Terms.Order order,
                                               DocValueFormat format,
                                               BucketCountThresholds bucketCountThresholds,
                                               IncludeExclude.OrdinalsFilter includeExclude,
                                               SearchContext context,
                                               Aggregator parent,
                                               boolean forceRemapGlobalOrds,
                                               SubAggCollectionMode collectionMode,
                                               boolean showTermDocCountError,
                                               List<PipelineAggregator> pipelineAggregators,
                                               Map<String, Object> metaData) throws IOException {
        super(name, factories, context, parent, order, format, bucketCountThresholds, collectionMode, showTermDocCountError,
                pipelineAggregators, metaData);
        this.valuesSource = valuesSource;
        this.includeExclude = includeExclude;
        final IndexReader reader = context.searcher().getIndexReader();
        final RandomAccessOrds values = reader.leaves().size() > 0 ?
            valuesSource.globalOrdinalsValues(context.searcher().getIndexReader().leaves().get(0)) : DocValues.emptySortedSet();
        this.valueCount = values.getValueCount();
        this.lookupGlobalOrd = values::lookupOrd;
        this.acceptedGlobalOrdinals = includeExclude != null ? includeExclude.acceptedGlobalOrdinals(values) : null;

        /**
         * Remap global ords to dense bucket ordinals if any sub-aggregator cannot be deferred.
         * Sub-aggregators expect dense buckets and allocate memories based on this assumption.
         * Deferred aggregators are safe because the selected ordinals are remapped when the buckets
         * are replayed.
         */
        boolean remapGlobalOrds = forceRemapGlobalOrds || Arrays.stream(subAggregators).anyMatch((a) -> shouldDefer(a) == false);
        this.bucketOrds = remapGlobalOrds ? new LongHash(1, context.bigArrays()) : null;
    }


    boolean remapGlobalOrds() {
        return bucketOrds != null;
    }

    protected final long getBucketOrd(long globalOrd) {
        return bucketOrds == null ? globalOrd : bucketOrds.find(globalOrd);
    }

    private void collectGlobalOrd(int doc, long globalOrd, LeafBucketCollector sub) throws IOException {
        if (bucketOrds == null) {
            collectExistingBucket(sub, doc, globalOrd);
        } else {
            long bucketOrd = bucketOrds.add(globalOrd);
            if (bucketOrd < 0) {
                bucketOrd = -1 - bucketOrd;
                collectExistingBucket(sub, doc, bucketOrd);
            } else {
                collectBucket(sub, doc, bucketOrd);
            }
        }
    }

    private RandomAccessOrds getGlobalOrds(LeafReaderContext ctx) throws IOException {
        return acceptedGlobalOrdinals == null ?
            valuesSource.globalOrdinalsValues(ctx) : new FilteredOrdinals(valuesSource.globalOrdinalsValues(ctx), acceptedGlobalOrdinals);
    }

    @Override
    public LeafBucketCollector getLeafCollector(LeafReaderContext ctx, final LeafBucketCollector sub) throws IOException {
        final RandomAccessOrds globalOrds = getGlobalOrds(ctx);
        if (bucketOrds == null) {
            grow(globalOrds.getValueCount());
        }
        final SortedDocValues singleValues = DocValues.unwrapSingleton(globalOrds);
        if (singleValues != null) {
            return new LeafBucketCollectorBase(sub, globalOrds) {
                @Override
                public void collect(int doc, long bucket) throws IOException {
                    assert bucket == 0;
                    final int ord = singleValues.getOrd(doc);
                    if (ord >= 0) {
                        collectGlobalOrd(doc, ord, sub);
                    }
                }
            };
        } else {
            return new LeafBucketCollectorBase(sub, globalOrds) {
                @Override
                public void collect(int doc, long bucket) throws IOException {
                    assert bucket == 0;
                    globalOrds.setDocument(doc);
                    final int numOrds = globalOrds.cardinality();
                    for (int i = 0; i < numOrds; i++) {
                        final long globalOrd = globalOrds.ordAt(i);
                        collectGlobalOrd(doc, globalOrd, sub);
                    }
                }
            };
        }
    }

    protected static void copy(BytesRef from, BytesRef to) {
        if (to.bytes.length < from.length) {
            to.bytes = new byte[ArrayUtil.oversize(from.length, 1)];
        }
        to.offset = 0;
        to.length = from.length;
        System.arraycopy(from.bytes, from.offset, to.bytes, 0, from.length);
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrdinal) throws IOException {
        if (valueCount == 0) { // no context in this reader
            return buildEmptyAggregation();
        }

        final int size;
        if (bucketCountThresholds.getMinDocCount() == 0) {
            // if minDocCount == 0 then we can end up with more buckets then maxBucketOrd() returns
            size = (int) Math.min(valueCount, bucketCountThresholds.getShardSize());
        } else {
            size = (int) Math.min(maxBucketOrd(), bucketCountThresholds.getShardSize());
        }
        long otherDocCount = 0;
        BucketPriorityQueue<OrdBucket> ordered = new BucketPriorityQueue<>(size, order.comparator(this));
        OrdBucket spare = new OrdBucket(-1, 0, null, showTermDocCountError, 0);
        for (long globalTermOrd = 0; globalTermOrd < valueCount; ++globalTermOrd) {
            if (includeExclude != null && !acceptedGlobalOrdinals.get(globalTermOrd)) {
                continue;
            }
            final long bucketOrd = getBucketOrd(globalTermOrd);
            final int bucketDocCount = bucketOrd < 0 ? 0 : bucketDocCount(bucketOrd);
            if (bucketCountThresholds.getMinDocCount() > 0 && bucketDocCount == 0) {
                continue;
            }
            otherDocCount += bucketDocCount;
            spare.globalOrd = globalTermOrd;
            spare.bucketOrd = bucketOrd;
            spare.docCount = bucketDocCount;
            if (bucketCountThresholds.getShardMinDocCount() <= spare.docCount) {
                spare = ordered.insertWithOverflow(spare);
                if (spare == null) {
                    spare = new OrdBucket(-1, 0, null, showTermDocCountError, 0);
                }
            }
        }

        // Get the top buckets
        final StringTerms.Bucket[] list = new StringTerms.Bucket[ordered.size()];
        long survivingBucketOrds[] = new long[ordered.size()];
        for (int i = ordered.size() - 1; i >= 0; --i) {
            final OrdBucket bucket = ordered.pop();
            survivingBucketOrds[i] = bucket.bucketOrd;
            BytesRef scratch = new BytesRef();
            copy(lookupGlobalOrd.apply(bucket.globalOrd), scratch);
            list[i] = new StringTerms.Bucket(scratch, bucket.docCount, null, showTermDocCountError, 0, format);
            list[i].bucketOrd = bucket.bucketOrd;
            otherDocCount -= list[i].docCount;
        }
        //replay any deferred collections
        runDeferredCollections(survivingBucketOrds);

        //Now build the aggs
        for (int i = 0; i < list.length; i++) {
            StringTerms.Bucket bucket = list[i];
            bucket.aggregations = bucket.docCount == 0 ? bucketEmptyAggregations() : bucketAggregations(bucket.bucketOrd);
            bucket.docCountError = 0;
        }

        return new StringTerms(name, order, bucketCountThresholds.getRequiredSize(), bucketCountThresholds.getMinDocCount(),
                pipelineAggregators(), metaData(), format, bucketCountThresholds.getShardSize(), showTermDocCountError,
                otherDocCount, Arrays.asList(list), 0);
    }

    /**
     * This is used internally only, just for compare using global ordinal instead of term bytes in the PQ
     */
    static class OrdBucket extends InternalTerms.Bucket<OrdBucket> {
        long globalOrd;

        OrdBucket(long globalOrd, long docCount, InternalAggregations aggregations, boolean showDocCountError, long docCountError) {
            super(docCount, aggregations, showDocCountError, docCountError, null);
            this.globalOrd = globalOrd;
        }

        @Override
        public int compareTerm(Terms.Bucket other) {
            return Long.compare(globalOrd, ((OrdBucket) other).globalOrd);
        }

        @Override
        public String getKeyAsString() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object getKey() {
            throw new UnsupportedOperationException();
        }

        @Override
        OrdBucket newBucket(long docCount, InternalAggregations aggs, long docCountError) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Number getKeyAsNumber() {
            throw new UnsupportedOperationException();
        }

        @Override
        protected void writeTermTo(StreamOutput out) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        protected final XContentBuilder keyToXContent(XContentBuilder builder) throws IOException {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    protected void doClose() {
        Releasables.close(bucketOrds);
    }

    /**
     * Variant of {@link GlobalOrdinalsStringTermsAggregator} that resolves global ordinals post segment collection
     * instead of on the fly for each match.This is beneficial for low cardinality fields, because it can reduce
     * the amount of look-ups significantly.
     */
    static class LowCardinality extends GlobalOrdinalsStringTermsAggregator {

        private IntArray segmentDocCounts;
        private RandomAccessOrds globalOrds;
        private RandomAccessOrds segmentOrds;

        LowCardinality(String name,
                       AggregatorFactories factories,
                       ValuesSource.Bytes.WithOrdinals valuesSource,
                       Terms.Order order,
                       DocValueFormat format,
                       BucketCountThresholds bucketCountThresholds,
                       SearchContext context,
                       Aggregator parent,
                       boolean forceDenseMode,
                       SubAggCollectionMode collectionMode,
                       boolean showTermDocCountError,
                       List<PipelineAggregator> pipelineAggregators,
                       Map<String, Object> metaData) throws IOException {
            super(name, factories, valuesSource, order, format, bucketCountThresholds, null, context, parent, forceDenseMode,
                collectionMode, showTermDocCountError, pipelineAggregators, metaData);
            this.segmentDocCounts = context.bigArrays().newIntArray(1, true);
        }

        @Override
        public LeafBucketCollector getLeafCollector(LeafReaderContext ctx,
                                                    final LeafBucketCollector sub) throws IOException {
            if (segmentOrds != null) {
                mapSegmentCountsToGlobalCounts();
            }
            globalOrds = valuesSource.globalOrdinalsValues(ctx);
            segmentOrds = valuesSource.ordinalsValues(ctx);
            segmentDocCounts = context.bigArrays().grow(segmentDocCounts, 1 + segmentOrds.getValueCount());
            assert sub == LeafBucketCollector.NO_OP_COLLECTOR;
            final SortedDocValues singleValues = DocValues.unwrapSingleton(segmentOrds);
            if (singleValues != null) {
                return new LeafBucketCollectorBase(sub, segmentOrds) {
                    @Override
                    public void collect(int doc, long bucket) throws IOException {
                        assert bucket == 0;
                        final int ord = singleValues.getOrd(doc);
                        segmentDocCounts.increment(ord + 1, 1);
                    }
                };
            } else {
                return new LeafBucketCollectorBase(sub, segmentOrds) {
                    @Override
                    public void collect(int doc, long bucket) throws IOException {
                        assert bucket == 0;
                        segmentOrds.setDocument(doc);
                        final int numOrds = segmentOrds.cardinality();
                        for (int i = 0; i < numOrds; i++) {
                            final long segmentOrd = segmentOrds.ordAt(i);
                            segmentDocCounts.increment(segmentOrd + 1, 1);
                        }
                    }
                };
            }
        }

        @Override
        protected void doPostCollection() {
            if (segmentOrds != null) {
                mapSegmentCountsToGlobalCounts();
            }
        }

        @Override
        protected void doClose() {
            Releasables.close(segmentDocCounts);
        }

        private void mapSegmentCountsToGlobalCounts() {
            // There is no public method in Ordinals.Docs that allows for this mapping...
            // This is the cleanest way I can think of so far

            GlobalOrdinalMapping mapping;
            if (globalOrds.getValueCount() == segmentOrds.getValueCount()) {
                mapping = null;
            } else {
                mapping = (GlobalOrdinalMapping) globalOrds;
            }
            for (long i = 1; i < segmentDocCounts.size(); i++) {
                // We use set(...) here, because we need to reset the slow to 0.
                // segmentDocCounts get reused over the segments and otherwise counts would be too high.
                final int inc = segmentDocCounts.set(i, 0);
                if (inc == 0) {
                    continue;
                }
                final long ord = i - 1; // remember we do +1 when counting
                final long globalOrd = mapping == null ? ord : mapping.getGlobalOrd(ord);
                long bucketOrd = getBucketOrd(globalOrd);
                incrementBucketDocCount(bucketOrd, inc);
            }
        }
    }

    private static final class FilteredOrdinals extends AbstractRandomAccessOrds {

        private final RandomAccessOrds inner;
        private final LongBitSet accepted;

        private int cardinality;
        private long[] ords = new long[0];

        private FilteredOrdinals(RandomAccessOrds inner, LongBitSet accepted) {
            this.inner = inner;
            this.accepted = accepted;
        }

        @Override
        public long getValueCount() {
            return inner.getValueCount();
        }

        @Override
        public long ordAt(int index) {
            return ords[index];
        }

        @Override
        public void doSetDocument(int docId) {
            inner.setDocument(docId);
            final int innerCardinality = inner.cardinality();
            ords = ArrayUtil.grow(ords, innerCardinality);

            cardinality = 0;
            for (int slot = 0; slot < innerCardinality; slot++) {
                long ord = inner.ordAt(slot);
                if (accepted.get(ord)) {
                    ords[cardinality++] = ord;
                }
            }
        }

        @Override
        public int cardinality() {
            return cardinality;
        }

        @Override
        public BytesRef lookupOrd(long ord) {
            return inner.lookupOrd(ord);
        }
    }
}
