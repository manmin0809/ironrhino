package org.ironrhino.core.metadata;

import static java.lang.annotation.ElementType.PACKAGE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Target({ TYPE, PACKAGE })
@Retention(RUNTIME)
@Inherited
public @interface AutoConfig {

	String namespace() default "";

	String actionName() default "";

	String fileupload() default "";

	boolean lenientPathVariable() default false;

}
