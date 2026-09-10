package com.weizhi.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Tool {

    String name() default "";

    String description() default "";

    boolean readOnly() default false;

    boolean concurrencySafe() default false;
}
