package com.sixbean.testpike.core.audit.domain.component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Audit {

    
    String objectType();

    
    String action();

    
    String objectId() default "";

    
    String before() default "";

    
    String after() default "";
}
