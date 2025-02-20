/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.mapper;

import org.elasticsearch.action.admin.indices.get.GetIndexRequest;
import org.elasticsearch.action.admin.indices.get.GetIndexResponse;
import org.elasticsearch.action.admin.indices.mapping.get.GetFieldMappingsResponse;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.elasticsearch.action.fieldcaps.FieldCapabilities;
import org.elasticsearch.action.fieldcaps.FieldCapabilitiesRequest;
import org.elasticsearch.action.fieldcaps.FieldCapabilitiesResponse;
import org.elasticsearch.cluster.metadata.MappingMetadata;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.indices.IndicesModule;
import org.elasticsearch.plugins.MapperPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.elasticsearch.test.TestGeoShapeFieldMapperPlugin;
import org.elasticsearch.xcontent.XContentType;
import org.junit.Before;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.elasticsearch.cluster.metadata.MetadataTests.assertLeafs;
import static org.elasticsearch.cluster.metadata.MetadataTests.assertMultiField;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;

public class FieldFilterMapperPluginTests extends ESSingleNodeTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Arrays.asList(FieldFilterPlugin.class, TestGeoShapeFieldMapperPlugin.class);
    }

    @Before
    public void putMappings() {
        assertAcked(client().admin().indices().prepareCreate("index1"));
        assertAcked(client().admin().indices().prepareCreate("filtered"));
        assertAcked(client().admin().indices().preparePutMapping("index1", "filtered").setSource(TEST_ITEM, XContentType.JSON));
    }

    public void testGetMappings() {
        GetMappingsResponse getMappingsResponse = client().admin().indices().prepareGetMappings().get();
        assertExpectedMappings(getMappingsResponse.mappings());
    }

    public void testGetIndex() {
        GetIndexResponse getIndexResponse = client().admin()
            .indices()
            .prepareGetIndex()
            .setFeatures(GetIndexRequest.Feature.MAPPINGS)
            .get();
        assertExpectedMappings(getIndexResponse.mappings());
    }

    public void testGetFieldMappings() {
        GetFieldMappingsResponse getFieldMappingsResponse = client().admin().indices().prepareGetFieldMappings().setFields("*").get();
        Map<String, Map<String, GetFieldMappingsResponse.FieldMappingMetadata>> mappings = getFieldMappingsResponse.mappings();
        assertEquals(2, mappings.size());
        assertFieldMappings(mappings.get("index1"), ALL_FLAT_FIELDS);
        assertFieldMappings(mappings.get("filtered"), FILTERED_FLAT_FIELDS);
        // double check that submitting the filtered mappings to an unfiltered index leads to the same get field mappings output
        // as the one coming from a filtered index with same mappings
        GetMappingsResponse getMappingsResponse = client().admin().indices().prepareGetMappings("filtered").get();
        MappingMetadata filtered = getMappingsResponse.getMappings().get("filtered");
        assertAcked(client().admin().indices().prepareCreate("test").setMapping(filtered.getSourceAsMap()));
        GetFieldMappingsResponse response = client().admin().indices().prepareGetFieldMappings("test").setFields("*").get();
        assertEquals(1, response.mappings().size());
        assertFieldMappings(response.mappings().get("test"), FILTERED_FLAT_FIELDS);
    }

    public void testGetNonExistentFieldMapping() {
        GetFieldMappingsResponse response = client().admin().indices().prepareGetFieldMappings("index1").setFields("non-existent").get();
        Map<String, Map<String, GetFieldMappingsResponse.FieldMappingMetadata>> mappings = response.mappings();
        assertEquals(1, mappings.size());
        Map<String, GetFieldMappingsResponse.FieldMappingMetadata> fieldmapping = mappings.get("index1");
        assertEquals(0, fieldmapping.size());
    }

    public void testFieldCapabilities() {
        List<String> allFields = new ArrayList<>(ALL_FLAT_FIELDS);
        allFields.addAll(ALL_OBJECT_FIELDS);
        FieldCapabilitiesResponse index1 = client().fieldCaps(new FieldCapabilitiesRequest().fields("*").indices("index1")).actionGet();
        assertFieldCaps(index1, allFields);
        FieldCapabilitiesResponse filtered = client().fieldCaps(new FieldCapabilitiesRequest().fields("*").indices("filtered")).actionGet();
        List<String> filteredFields = new ArrayList<>(FILTERED_FLAT_FIELDS);
        filteredFields.addAll(ALL_OBJECT_FIELDS);
        assertFieldCaps(filtered, filteredFields);
        // double check that submitting the filtered mappings to an unfiltered index leads to the same field_caps output
        // as the one coming from a filtered index with same mappings
        GetMappingsResponse getMappingsResponse = client().admin().indices().prepareGetMappings("filtered").get();
        MappingMetadata filteredMapping = getMappingsResponse.getMappings().get("filtered");
        assertAcked(client().admin().indices().prepareCreate("test").setMapping(filteredMapping.getSourceAsMap()));
        FieldCapabilitiesResponse test = client().fieldCaps(new FieldCapabilitiesRequest().fields("*").indices("test")).actionGet();
        // properties.value is an object field in the new mapping
        filteredFields.add("properties.value");
        assertFieldCaps(test, filteredFields);
    }

    private static void assertFieldCaps(FieldCapabilitiesResponse fieldCapabilitiesResponse, Collection<String> expectedFields) {
        Map<String, Map<String, FieldCapabilities>> responseMap = new HashMap<>(fieldCapabilitiesResponse.get());
        for (String field : builtInMetadataFields()) {
            Map<String, FieldCapabilities> remove = responseMap.remove(field);
            assertNotNull(" expected field [" + field + "] not found", remove);
        }
        for (String field : expectedFields) {
            Map<String, FieldCapabilities> remove = responseMap.remove(field);
            assertNotNull(" expected field [" + field + "] not found", remove);
        }
        assertEquals("Some unexpected fields were returned: " + responseMap.keySet(), 0, responseMap.size());
    }

    private static Set<String> builtInMetadataFields() {
        Set<String> builtInMetadataFields = new HashSet<>(IndicesModule.getBuiltInMetadataFields());
        // Index is not a time-series index, and it will not contain a _tsid field
        builtInMetadataFields.remove(TimeSeriesIdFieldMapper.NAME);
        return builtInMetadataFields;
    }

    private static void assertFieldMappings(
        Map<String, GetFieldMappingsResponse.FieldMappingMetadata> actual,
        Collection<String> expectedFields
    ) {
        Map<String, GetFieldMappingsResponse.FieldMappingMetadata> fields = new HashMap<>(actual);
        for (String field : builtInMetadataFields()) {
            GetFieldMappingsResponse.FieldMappingMetadata fieldMappingMetadata = fields.remove(field);
            assertNotNull(" expected field [" + field + "] not found", fieldMappingMetadata);
        }
        for (String field : expectedFields) {
            GetFieldMappingsResponse.FieldMappingMetadata fieldMappingMetadata = fields.remove(field);
            assertNotNull("expected field [" + field + "] not found", fieldMappingMetadata);
        }
        assertEquals("Some unexpected fields were returned: " + fields.keySet(), 0, fields.size());
    }

    private void assertExpectedMappings(ImmutableOpenMap<String, MappingMetadata> mappings) {
        assertEquals(2, mappings.size());
        assertNotFiltered(mappings.get("index1"));
        MappingMetadata filtered = mappings.get("filtered");
        assertFiltered(filtered);
        assertMappingsAreValid(filtered.getSourceAsMap());
    }

    private void assertMappingsAreValid(Map<String, Object> sourceAsMap) {
        // check that the returned filtered mappings are still valid mappings by submitting them and retrieving them back
        assertAcked(client().admin().indices().prepareCreate("test").setMapping(sourceAsMap));
        GetMappingsResponse testMappingsResponse = client().admin().indices().prepareGetMappings("test").get();
        assertEquals(1, testMappingsResponse.getMappings().size());
        // the mappings are returned unfiltered for this index, yet they are the same as the previous ones that were returned filtered
        assertFiltered(testMappingsResponse.getMappings().get("test"));
    }

    @SuppressWarnings("unchecked")
    private static void assertFiltered(MappingMetadata mappingMetadata) {
        assertNotNull(mappingMetadata);
        Map<String, Object> sourceAsMap = mappingMetadata.getSourceAsMap();
        assertEquals(4, sourceAsMap.size());
        assertTrue(sourceAsMap.containsKey("_meta"));
        assertTrue(sourceAsMap.containsKey("_routing"));
        assertTrue(sourceAsMap.containsKey("_source"));
        Map<String, Object> typeProperties = (Map<String, Object>) sourceAsMap.get("properties");
        assertEquals(4, typeProperties.size());

        Map<String, Object> name = (Map<String, Object>) typeProperties.get("name");
        assertEquals(1, name.size());
        Map<String, Object> nameProperties = (Map<String, Object>) name.get("properties");
        assertEquals(1, nameProperties.size());
        assertLeafs(nameProperties, "last_visible");

        assertLeafs(typeProperties, "age_visible");

        Map<String, Object> address = (Map<String, Object>) typeProperties.get("address");
        assertNotNull(address);
        assertEquals(1, address.size());
        Map<String, Object> addressProperties = (Map<String, Object>) address.get("properties");
        assertNotNull(addressProperties);
        assertEquals(1, addressProperties.size());
        assertLeafs(addressProperties, "area_visible");

        Map<String, Object> properties = (Map<String, Object>) typeProperties.get("properties");
        assertNotNull(properties);
        assertEquals(2, properties.size());
        assertEquals("nested", properties.get("type"));
        Map<String, Object> propertiesProperties = (Map<String, Object>) properties.get("properties");
        assertNotNull(propertiesProperties);
        assertEquals(2, propertiesProperties.size());
        assertLeafs(propertiesProperties, "key_visible");

        Map<String, Object> value = (Map<String, Object>) propertiesProperties.get("value");
        assertNotNull(value);
        assertEquals(1, value.size());
        Map<String, Object> valueProperties = (Map<String, Object>) value.get("properties");
        assertNotNull(valueProperties);
        assertEquals(1, valueProperties.size());
        assertLeafs(valueProperties, "keyword_visible");
    }

    @SuppressWarnings("unchecked")
    private static void assertNotFiltered(MappingMetadata mappingMetadata) {
        assertNotNull(mappingMetadata);
        Map<String, Object> sourceAsMap = mappingMetadata.getSourceAsMap();
        assertEquals(4, sourceAsMap.size());
        assertTrue(sourceAsMap.containsKey("_meta"));
        assertTrue(sourceAsMap.containsKey("_routing"));
        assertTrue(sourceAsMap.containsKey("_source"));
        Map<String, Object> typeProperties = (Map<String, Object>) sourceAsMap.get("properties");
        assertEquals(5, typeProperties.size());

        Map<String, Object> name = (Map<String, Object>) typeProperties.get("name");
        assertEquals(1, name.size());
        Map<String, Object> nameProperties = (Map<String, Object>) name.get("properties");
        assertEquals(2, nameProperties.size());
        assertLeafs(nameProperties, "first", "last_visible");

        assertLeafs(typeProperties, "birth", "age_visible");

        Map<String, Object> address = (Map<String, Object>) typeProperties.get("address");
        assertNotNull(address);
        assertEquals(1, address.size());
        Map<String, Object> addressProperties = (Map<String, Object>) address.get("properties");
        assertNotNull(addressProperties);
        assertEquals(3, addressProperties.size());
        assertLeafs(addressProperties, "street", "location", "area_visible");

        Map<String, Object> properties = (Map<String, Object>) typeProperties.get("properties");
        assertNotNull(properties);
        assertEquals(2, properties.size());
        assertTrue(properties.containsKey("type"));
        Map<String, Object> propertiesProperties = (Map<String, Object>) properties.get("properties");
        assertNotNull(propertiesProperties);
        assertEquals(2, propertiesProperties.size());
        assertMultiField(propertiesProperties, "key_visible", "keyword");
        assertMultiField(propertiesProperties, "value", "keyword_visible");
    }

    public static class FieldFilterPlugin extends Plugin implements MapperPlugin {

        @Override
        public Function<String, Predicate<String>> getFieldFilter() {
            return index -> index.equals("filtered") ? field -> field.endsWith("visible") : MapperPlugin.NOOP_FIELD_PREDICATE;
        }
    }

    private static final Collection<String> ALL_FLAT_FIELDS = Arrays.asList(
        "name.first",
        "name.last_visible",
        "birth",
        "age_visible",
        "address.street",
        "address.location",
        "address.area_visible",
        "properties.key_visible",
        "properties.key_visible.keyword",
        "properties.value",
        "properties.value.keyword_visible"
    );

    private static final Collection<String> ALL_OBJECT_FIELDS = Arrays.asList("name", "address", "properties");

    private static final Collection<String> FILTERED_FLAT_FIELDS = Arrays.asList(
        "name.last_visible",
        "age_visible",
        "address.area_visible",
        "properties.key_visible",
        "properties.value.keyword_visible"
    );

    private static final String TEST_ITEM = "{\n"
        + "  \"_doc\": {\n"
        + "      \"_meta\": {\n"
        + "        \"version\":0.19\n"
        + "      },"
        + "      \"_routing\": {\n"
        + "        \"required\":true\n"
        + "      },"
        + "      \"_source\": {\n"
        + "        \"enabled\":false\n"
        + "      },"
        + "      \"properties\": {\n"
        + "        \"name\": {\n"
        + "          \"properties\": {\n"
        + "            \"first\": {\n"
        + "              \"type\": \"keyword\"\n"
        + "            },\n"
        + "            \"last_visible\": {\n"
        + "              \"type\": \"keyword\"\n"
        + "            }\n"
        + "          }\n"
        + "        },\n"
        + "        \"birth\": {\n"
        + "          \"type\": \"date\"\n"
        + "        },\n"
        + "        \"age_visible\": {\n"
        + "          \"type\": \"integer\"\n"
        + "        },\n"
        + "        \"address\": {\n"
        + "          \"type\": \"object\",\n"
        + "          \"properties\": {\n"
        + "            \"street\": {\n"
        + "              \"type\": \"keyword\"\n"
        + "            },\n"
        + "            \"location\": {\n"
        + "              \"type\": \"geo_point\"\n"
        + "            },\n"
        + "            \"area_visible\": {\n"
        + "              \"type\": \"geo_shape\""
        + "            }\n"
        + "          }\n"
        + "        },\n"
        + "        \"properties\": {\n"
        + "          \"type\": \"nested\",\n"
        + "          \"properties\": {\n"
        + "            \"key_visible\" : {\n"
        + "              \"type\": \"text\",\n"
        + "              \"fields\": {\n"
        + "                \"keyword\" : {\n"
        + "                  \"type\" : \"keyword\"\n"
        + "                }\n"
        + "              }\n"
        + "            },\n"
        + "            \"value\" : {\n"
        + "              \"type\": \"text\",\n"
        + "              \"fields\": {\n"
        + "                \"keyword_visible\" : {\n"
        + "                  \"type\" : \"keyword\"\n"
        + "                }\n"
        + "              }\n"
        + "            }\n"
        + "          }\n"
        + "        }\n"
        + "      }\n"
        + "    }\n"
        + "  }\n"
        + "}";
}
