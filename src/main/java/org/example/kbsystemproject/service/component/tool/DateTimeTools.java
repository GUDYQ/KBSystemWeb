package org.example.kbsystemproject.service.component.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.i18n.LocaleContextHolder;
import java.time.LocalDateTime;
public class DateTimeTools {
    @Tool(description = "获取用户时区的当前日期和时间")
    String getCurrentDateTime() {
        return LocalDateTime.now().atZone(LocaleContextHolder.getTimeZone().toZoneId()).toString();
    }
}