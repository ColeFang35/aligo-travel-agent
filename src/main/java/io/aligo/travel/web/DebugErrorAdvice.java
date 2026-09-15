package io.aligo.travel.web;

import java.util.Map;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 调试期：把异常栈写进响应，便于快速定位。 */
@RestControllerAdvice
public class DebugErrorAdvice {

    @ExceptionHandler(Exception.class)
    public Map<String, Object> onAny(Exception e) {
        e.printStackTrace();
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        return Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage(),
                "stack", sw.toString().substring(0, Math.min(2000, sw.toString().length())));
    }
}
