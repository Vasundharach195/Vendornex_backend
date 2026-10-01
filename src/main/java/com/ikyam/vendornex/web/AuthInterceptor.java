package com.ikyam.vendornex.web;

import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.security.CurrentUser;
import com.ikyam.vendornex.security.Role;
import com.ikyam.vendornex.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Reproduces Router.handle's authentication/authorization step: a method with no
 * {@link Roles} is public; otherwise the Authorization header is verified via the
 * unchanged {@link AuthService#authenticate}, the resulting user's role is checked
 * against the declared set, and the {@link CurrentUser} is stashed on the request for
 * {@link CurrentUserArgumentResolver} to hand to the controller method.
 */
public class AuthInterceptor implements HandlerInterceptor {

    public static final String CURRENT_USER_ATTR = "com.ikyam.vendornex.currentUser";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod hm)) return true; // e.g. CORS preflight handler
        Roles roles = hm.getMethodAnnotation(Roles.class);
        if (roles == null) return true; // public route

        CurrentUser cu = AuthService.authenticate(request.getHeader("Authorization"));
        boolean allowed = false;
        for (Role r : roles.value()) {
            if (cu.role() == r) { allowed = true; break; }
        }
        if (!allowed) throw ApiException.forbidden("Your role cannot perform this action");

        request.setAttribute(CURRENT_USER_ATTR, cu);
        return true;
    }
}
