package com.sixbean.testpike.common.clientobject;


public class Response<T> {

    private String code;
    private String msg;
    private T data;

    public Response() {
    }

    public Response(String code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> Response<T> builderSuccess() {
        return new Response<>("0", null, null);
    }

    public static <T> Response<T> builderSuccess(T data) {
        return new Response<>("0", null, data);
    }

    public static <T> Response<T> builderFail(String code, String msg) {
        return new Response<>(code, msg, null);
    }

    
    public boolean isSuccess() {
        return "0".equals(code);
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
