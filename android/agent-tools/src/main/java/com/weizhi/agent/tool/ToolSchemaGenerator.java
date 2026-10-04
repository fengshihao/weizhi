package com.weizhi.agent.tool;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolSchemaGenerator {

    private ToolSchemaGenerator() {
    }

    public static Map<String, Object> generate(Method method) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Parameter p : method.getParameters()) {
            ToolParam tp = p.getAnnotation(ToolParam.class);
            if (tp == null) {
                continue;
            }
            Map<String, Object> prop = typeSchema(p.getType());
            if (!tp.description().isEmpty()) {
                prop.put("description", tp.description());
            }
            properties.put(tp.name(), prop);
            if (tp.required()) {
                required.add(tp.name());
            }
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static Map<String, Object> typeSchema(Class<?> type) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (type == String.class) {
            m.put("type", "string");
        } else if (type == boolean.class || type == Boolean.class) {
            m.put("type", "boolean");
        } else if (type == int.class || type == Integer.class
                || type == long.class || type == Long.class) {
            m.put("type", "integer");
        } else if (type == double.class || type == Double.class
                || type == float.class || type == Float.class) {
            m.put("type", "number");
        } else if (Collection.class.isAssignableFrom(type) || type.isArray()) {
            m.put("type", "array");
            m.put("items", new LinkedHashMap<String, Object>());
        } else {
            m.put("type", "object");
        }
        return m;
    }
}
