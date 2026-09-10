package com.weizhi.agent.tool;

import com.google.gson.Gson;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ReflectiveTool {

    private static final Gson GSON = new Gson();

    private final Object instance;
    private final Method method;
    private final Tool toolAnno;
    private final Map<String, Object> parameters;

    ReflectiveTool(Object instance, Method method) {
        this.instance = instance;
        this.method = method;
        this.toolAnno = method.getAnnotation(Tool.class);
        this.parameters = ToolSchemaGenerator.generate(method);
        method.setAccessible(true);
    }

    static List<ReflectiveTool> from(Object instance) {
        List<ReflectiveTool> tools = new ArrayList<>();
        for (Method m : instance.getClass().getMethods()) {
            if (m.isAnnotationPresent(Tool.class)) {
                tools.add(new ReflectiveTool(instance, m));
            }
        }
        return tools;
    }

    String getName() {
        return toolAnno.name().isEmpty() ? method.getName() : toolAnno.name();
    }

    String getDescription() {
        return toolAnno.description();
    }

    Map<String, Object> getParameters() {
        return parameters;
    }

    boolean isReadOnly() {
        return toolAnno.readOnly();
    }

    String call(Map<String, Object> input) {
        try {
            Object[] args = bindArgs(input);
            Object result = method.invoke(instance, args);
            if (result == null) {
                return "";
            }
            return result instanceof String ? (String) result : GSON.toJson(result);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return "Error: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
    }

    private Object[] bindArgs(Map<String, Object> input) {
        Parameter[] params = method.getParameters();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i];
            ToolParam tp = p.getAnnotation(ToolParam.class);
            if (tp != null) {
                args[i] = convert(input.get(tp.name()), p.getType());
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    private Object convert(Object value, Class<?> type) {
        if (value == null) {
            return defaultFor(type);
        }
        if (type.isInstance(value)) {
            return value;
        }
        try {
            return GSON.fromJson(GSON.toJsonTree(value), type);
        } catch (Exception e) {
            return defaultFor(type);
        }
    }

    private static Object defaultFor(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == float.class) {
            return 0.0f;
        }
        return null;
    }
}
