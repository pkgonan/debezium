/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.transforms.outbox;

import java.util.Iterator;
import java.util.Map.Entry;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.errors.ConnectException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.NullNode;

/**
 * JSON payload SchemaBuilder util for Debezium Outbox Transform Event Router.
 *
 * @author Laurent Broudoux (laurent.broudoux@gmail.com)
 */
public class SchemaBuilderUtil {

    /**
    * Build a new connect Schema inferring structure and types from Json document.
    * @param document A Jackson JsonNode to extract schema from
    * @return A new Schema matching this Json node.
    */
    public static Schema jsonNodeToSchema(JsonNode document) {
        return jsonNodeToSchemaBuilder(document).build();
    }

    private static SchemaBuilder jsonNodeToSchemaBuilder(JsonNode document) {
        final SchemaBuilder schemaBuilder = SchemaBuilder.struct().optional();
        if (document != null) {
            Iterator<Entry<String, JsonNode>> fieldsEntries = document.fields();
            while (fieldsEntries.hasNext()) {
                Entry<String, JsonNode> fieldEntry = fieldsEntries.next();
                addFieldSchema(fieldEntry, schemaBuilder);
            }
        }
        return schemaBuilder;
    }

    private static void addFieldSchema(Entry<String, JsonNode> fieldEntry, SchemaBuilder builder) {
        final String fieldName = fieldEntry.getKey();
        final JsonNode fieldValue = fieldEntry.getValue();
        final Schema fieldSchema = jsonValueToSchema(fieldValue);
        if (fieldSchema != null && !hasField(builder, fieldName)) {
            builder.field(fieldName, fieldSchema);
        }
    }

    private static Schema jsonValueToSchema(JsonNode node) {
        switch (node.getNodeType()) {
            case STRING:
                return Schema.OPTIONAL_STRING_SCHEMA;
            case BOOLEAN:
                return Schema.OPTIONAL_BOOLEAN_SCHEMA;
            case NUMBER:
                if (node.isInt()) {
                    return Schema.OPTIONAL_INT32_SCHEMA;
                }
                if (node.isLong()) {
                    return Schema.OPTIONAL_INT64_SCHEMA;
                }
                return Schema.OPTIONAL_FLOAT64_SCHEMA;
            case ARRAY:
                ArrayNode arrayNode = (ArrayNode) node;
                return arrayNode.isEmpty() ? null : SchemaBuilder.array(findArrayMemberSchema(arrayNode)).optional().build();
            case OBJECT:
                return jsonNodeToSchema(node);
            default:
                return null;
        }
    }

    /** */
    private static JsonNode getFirstArrayElement(ArrayNode array) throws ConnectException {
        JsonNode refNode = NullNode.getInstance();
        Schema refSchema = null;
        // Get first non-null element type and check other member types.
        Iterator<JsonNode> elements = array.elements();
        while (elements.hasNext()) {
            JsonNode element = elements.next();

            // Skip null elements.
            if (element.isNull()) {
                continue;
            }

            // Set first non-null element if not set yet.
            if (refNode.isNull()) {
                refNode = element;
            }

            // Check types of elements.
            if (element.getNodeType() != refNode.getNodeType()) {
                throw new ConnectException(String.format("Field is not a homogenous array (%s x %s).",
                        refNode.asText(), element.getNodeType().toString()));
            }

            // We may return different schemas for NUMBER type, check here they are same.
            if (refNode.getNodeType() == JsonNodeType.NUMBER) {
                if (refSchema == null) {
                    refSchema = jsonValueToSchema(refNode);
                }
                Schema elementSchema = jsonValueToSchema(element);
                if (refSchema != elementSchema) {
                    throw new ConnectException(String.format("Field is not a homogenous array (%s x %s), different number types (%s x %s)",
                            refNode.asText(), element.asText(), refSchema, elementSchema));
                }
            }
        }

        return refNode;
    }

    private static Schema findArrayMemberSchema(ArrayNode array) throws ConnectException {
        final JsonNode sample = getFirstArrayElement(array);
        if (sample.isObject()) {
            return buildDocumentUnionSchema(array);
        }

        final Schema schema = jsonValueToSchema(sample);
        if (schema == null) {
            throw new ConnectException(String.format("Array '%s' has unrecognized member schema.",
                    array.asText()));
        }
        return schema;
    }

    private static Schema buildDocumentUnionSchema(ArrayNode array) {
        SchemaBuilder builder = null;

        Iterator<JsonNode> elements = array.elements();
        while (elements.hasNext()) {
            JsonNode element = elements.next();

            if (!element.isObject()) {
                continue;
            }
            if (builder == null) {
                builder = jsonNodeToSchemaBuilder(element);
                continue;
            }

            Iterator<Entry<String, JsonNode>> fieldsEntries = element.fields();
            while (fieldsEntries.hasNext()) {
                Entry<String, JsonNode> fieldEntry = fieldsEntries.next();
                addFieldSchema(fieldEntry, builder);
            }
        }
        return builder.build();
    }

    private static boolean hasField(SchemaBuilder builder, String fieldName) {
        return builder.field(fieldName) != null;
    }
}
