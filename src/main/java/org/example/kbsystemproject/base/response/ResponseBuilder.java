package org.example.kbsystemproject.base.response;

public class ResponseBuilder {

    // 成功响应
    public static <T> ResponseVO<T> success(T data) {
        return ResponseVO.<T>builder()
                .code(200)
                .message("成功")
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static <T> TraceableResponseVO<T> success(ResponseVO<T> responseVO, String requestId, String path) {
        return TraceableResponseVO.<T>builder()
                .code(responseVO.getCode())
                .message(responseVO.getMessage())
                .data(responseVO.getData())
                .timestamp(System.currentTimeMillis())
                .requestId(requestId)
                .path(path)
                .build();
    }

    // 错误响应
    public static <T> ResponseVO<T> error(Integer code, String message) {
        return ResponseVO.<T>builder()
                .code(code)
                .message(message)
                .data(null)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    // 基于枚举的错误响应
    public static <T> ResponseVO<T> error(ResponseCode responseCode) {
        return error(responseCode.getCode(), responseCode.getMessage());
    }

}