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

package org.elasticsearch.index.mapper;

import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.StringHelper;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.logging.DeprecationLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.fielddata.IndexFieldDataCache;
import org.elasticsearch.index.fielddata.IndexOrdinalsFieldData;
import org.elasticsearch.index.fielddata.UidIndexFieldData;
import org.elasticsearch.index.fielddata.plain.PagedBytesIndexFieldData;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.indices.breaker.CircuitBreakerService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 *
 */
public class UidFieldMapper extends MetadataFieldMapper {

    public static final String NAME = "_uid";

    public static final String CONTENT_TYPE = "_uid";

    public static class Defaults {
        public static final String NAME = UidFieldMapper.NAME;

        public static final MappedFieldType FIELD_TYPE = new UidFieldType();
        public static final MappedFieldType NESTED_FIELD_TYPE;

        static {
            FIELD_TYPE.setIndexOptions(IndexOptions.DOCS);
            FIELD_TYPE.setTokenized(false);
            FIELD_TYPE.setStored(true);
            FIELD_TYPE.setOmitNorms(true);
            FIELD_TYPE.setIndexAnalyzer(Lucene.KEYWORD_ANALYZER);
            FIELD_TYPE.setSearchAnalyzer(Lucene.KEYWORD_ANALYZER);
            FIELD_TYPE.setName(NAME);
            FIELD_TYPE.freeze();

            NESTED_FIELD_TYPE = FIELD_TYPE.clone();
            NESTED_FIELD_TYPE.setStored(false);
            NESTED_FIELD_TYPE.freeze();
        }
    }

    private static final DeprecationLogger DEPRECATION_LOGGER = new DeprecationLogger(Loggers.getLogger(UidFieldMapper.class));

    public static class TypeParser implements MetadataFieldMapper.TypeParser {
        @Override
        public MetadataFieldMapper.Builder<?, ?> parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            throw new MapperParsingException(NAME + " is not configurable");
        }

        @Override
        public MetadataFieldMapper getDefault(MappedFieldType fieldType, ParserContext context) {
            final IndexSettings indexSettings = context.mapperService().getIndexSettings();
            return new UidFieldMapper(indexSettings, fieldType);
        }
    }

    static final class UidFieldType extends TermBasedFieldType {

        UidFieldType() {
        }

        protected UidFieldType(UidFieldType ref) {
            super(ref);
        }

        @Override
        public MappedFieldType clone() {
            return new UidFieldType(this);
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

        @Override
        public IndexFieldData.Builder fielddataBuilder() {
            if (indexOptions() == IndexOptions.NONE) {
                DEPRECATION_LOGGER.deprecated("Fielddata access on the _uid field is deprecated, use _id instead");
                return new IndexFieldData.Builder() {
                    @Override
                    public IndexFieldData<?> build(IndexSettings indexSettings, MappedFieldType fieldType, IndexFieldDataCache cache,
                            CircuitBreakerService breakerService, MapperService mapperService) {
                        MappedFieldType idFieldType = mapperService.fullName(IdFieldMapper.NAME);
                        IndexOrdinalsFieldData idFieldData = (IndexOrdinalsFieldData) idFieldType.fielddataBuilder()
                                .build(indexSettings, idFieldType, cache, breakerService, mapperService);
                        final String type = mapperService.types().iterator().next();
                        return new UidIndexFieldData(indexSettings.getIndex(), type, idFieldData);
                    }
                };
            } else {
                // Old index, _uid was indexed
                return new PagedBytesIndexFieldData.Builder(
                        TextFieldMapper.Defaults.FIELDDATA_MIN_FREQUENCY,
                        TextFieldMapper.Defaults.FIELDDATA_MAX_FREQUENCY,
                        TextFieldMapper.Defaults.FIELDDATA_MIN_SEGMENT_SIZE);
            }
        }

        @Override
        public Query termQuery(Object value, @Nullable QueryShardContext context) {
            return termsQuery(Arrays.asList(value), context);
        }

        @Override
        public Query termsQuery(List<?> values, @Nullable QueryShardContext context) {
            if (indexOptions() != IndexOptions.NONE) {
                return super.termsQuery(values, context);
            }
            Collection<String> indexTypes = context.getMapperService().types();
            if (indexTypes.isEmpty()) {
                return new MatchNoDocsQuery("No types");
            }
            assert indexTypes.size() == 1;
            BytesRef indexType = indexedValueForSearch(indexTypes.iterator().next());
            BytesRefBuilder prefixBuilder = new BytesRefBuilder();
            prefixBuilder.append(indexType);
            prefixBuilder.append((byte) '#');
            BytesRef expectedPrefix = prefixBuilder.get();
            List<BytesRef> ids = new ArrayList<>();
            for (Object uid : values) {
                BytesRef uidBytes = indexedValueForSearch(uid);
                if (StringHelper.startsWith(uidBytes, expectedPrefix)) {
                    BytesRef id = new BytesRef();
                    id.bytes = uidBytes.bytes;
                    id.offset = uidBytes.offset + expectedPrefix.length;
                    id.length = uidBytes.length - expectedPrefix.length;
                    ids.add(id);
                }
            }
            return new TermInSetQuery(IdFieldMapper.NAME, ids);
        }
    }

    static MappedFieldType defaultFieldType(IndexSettings indexSettings) {
        MappedFieldType defaultFieldType = Defaults.FIELD_TYPE.clone();
        if (indexSettings.isSingleType()) {
            defaultFieldType.setIndexOptions(IndexOptions.NONE);
            defaultFieldType.setStored(false);
        } else {
            defaultFieldType.setIndexOptions(IndexOptions.DOCS);
            defaultFieldType.setStored(true);
        }
        return defaultFieldType;
    }

    private UidFieldMapper(IndexSettings indexSettings, MappedFieldType existing) {
        this(existing == null ? defaultFieldType(indexSettings) : existing, indexSettings);
    }

    private UidFieldMapper(MappedFieldType fieldType, IndexSettings indexSettings) {
        super(NAME, fieldType, defaultFieldType(indexSettings), indexSettings.getSettings());
    }

    @Override
    public void preParse(ParseContext context) throws IOException {
        super.parse(context);
    }

    @Override
    public void postParse(ParseContext context) throws IOException {}

    @Override
    public Mapper parse(ParseContext context) throws IOException {
        // nothing to do here, we do everything in preParse
        return null;
    }

    @Override
    protected void parseCreateField(ParseContext context, List<IndexableField> fields) throws IOException {
        if (fieldType.indexOptions() != IndexOptions.NONE || fieldType.stored()) {
            Field uid = new Field(NAME, Uid.createUid(context.sourceToParse().type(), context.sourceToParse().id()), fieldType);
            fields.add(uid);
        }
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        return builder;
    }

    @Override
    protected void doMerge(Mapper mergeWith, boolean updateAllTypes) {
        // do nothing here, no merging, but also no exception
    }
}
