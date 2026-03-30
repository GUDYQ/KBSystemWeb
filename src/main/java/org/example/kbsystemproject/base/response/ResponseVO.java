package org.example.kbsystemproject.base.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@EqualsAndHashCode
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResponseVO<T> {
    private Integer code;
    private String message;
    private T data;
    private Long timestamp;
}
