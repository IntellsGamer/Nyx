package dev.nyx.checks;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CheckData {
    String name();
    String description() default "";
    String[] exemptPermissions() default {};
    boolean experimental() default false;
}
