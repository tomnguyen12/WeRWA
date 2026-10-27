package vn.werwa.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * API có trong đặc tả nhưng mô-đun chưa cài (Q20): phương thức mặc định của interface sinh từ OpenAPI trả 501 không có
 * thân; đổi thành problem+json {@code NOT_IMPLEMENTED} cho thống nhất với mọi lỗi khác.
 */
@Configuration(proxyBeanMethods = false)
class NotImplementedInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/v1/**");
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView mav)
            throws Exception {
        if (response.getStatus() == 501 && !response.isCommitted()) {
            Problems.write(request, response, PlatformError.NOT_IMPLEMENTED, null);
        }
    }
}
