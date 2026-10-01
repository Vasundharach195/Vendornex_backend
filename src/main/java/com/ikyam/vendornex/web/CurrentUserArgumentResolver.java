package com.ikyam.vendornex.web;

import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Lets controller methods take a {@code CurrentUser} parameter directly, mirroring {@code req.user()}. */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CurrentUser.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                   NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = (HttpServletRequest) webRequest.getNativeRequest();
        Object cu = request == null ? null : request.getAttribute(AuthInterceptor.CURRENT_USER_ATTR);
        if (cu == null) throw ApiException.unauthorized("Not signed in");
        return cu;
    }
}
