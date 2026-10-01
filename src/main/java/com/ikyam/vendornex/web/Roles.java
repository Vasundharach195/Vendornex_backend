package com.ikyam.vendornex.web;

import com.ikyam.vendornex.security.Role;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares which roles may call a controller method, mirroring the old Router's
 * {@code r.get(path, handler, Role...)} varargs. A method with no {@code @Roles}
 * annotation is public (mirrors {@code publicRoute}).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Roles {
    Role[] value();
}
