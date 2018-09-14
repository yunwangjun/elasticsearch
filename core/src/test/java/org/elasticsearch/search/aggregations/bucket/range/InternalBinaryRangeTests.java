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
package org.elasticsearch.search.aggregations.bucket.range;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.aggregations.InternalMultiBucketAggregation;
import org.elasticsearch.search.aggregations.ParsedMultiBucketAggregation;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class InternalBinaryRangeTests extends InternalRangeTestCase<InternalBinaryRange> {

    private List<Tuple<BytesRef, BytesRef>> ranges;

    @Override
    protected int minNumberOfBuckets() {
        return 1;
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();

        List<Tuple<BytesRef, BytesRef>> listOfRanges = new ArrayList<>();
        if (randomBoolean()) {
            listOfRanges.add(Tuple.tuple(null, new BytesRef(randomAlphaOfLength(15))));
        }
        if (randomBoolean()) {
            listOfRanges.add(Tuple.tuple(new BytesRef(randomAlphaOfLength(15)), null));
        }
        if (randomBoolean()) {
            listOfRanges.add(Tuple.tuple(null, null));
        }

        final int numRanges = Math.max(0, randomNumberOfBuckets() - listOfRanges.size());
        for (int i = 0; i < numRanges; i++) {
            BytesRef[] values = new BytesRef[2];
            values[0] = new BytesRef(randomAlphaOfLength(15));
            values[1] = new BytesRef(randomAlphaOfLength(15));
            Arrays.sort(values);
            listOfRanges.add(Tuple.tuple(values[0], values[1]));
        }
        Collections.shuffle(listOfRanges, random());
        ranges = Collections.unmodifiableList(listOfRanges);
    }

    @Override
    protected InternalBinaryRange createTestInstance(String name,
                                                     List<PipelineAggregator> pipelineAggregators,
                                                     Map<String, Object> metaData,
                                                     InternalAggregations aggregations,
                                                     boolean keyed) {
        DocValueFormat format = DocValueFormat.RAW;
        List<InternalBinaryRange.Bucket> buckets = new ArrayList<>();

        int nullKey = randomBoolean() ? randomIntBetween(0, ranges.size() -1) : -1;
        for (int i = 0; i < ranges.size(); ++i) {
            final int docCount = randomIntBetween(1, 100);
            final String key = (i == nullKey) ? null: randomAlphaOfLength(10);
            buckets.add(new InternalBinaryRange.Bucket(format, keyed, key, ranges.get(i).v1(), ranges.get(i).v2(), docCount, aggregations));
        }
        return new InternalBinaryRange(name, format, keyed, buckets, pipelineAggregators, metaData);
    }

    @Override
    protected Class<? extends ParsedMultiBucketAggregation> implementationClass() {
        return ParsedBinaryRange.class;
    }

    @Override
    protected Class<? extends InternalMultiBucketAggregation.InternalBucket> internalRangeBucketClass() {
        return InternalBinaryRange.Bucket.class;
    }

    @Override
    protected Class<? extends ParsedMultiBucketAggregation.ParsedBucket> parsedRangeBucketClass() {
        return ParsedBinaryRange.ParsedBucket.class;
    }
}
