package io.github.karimagnusson.zio.path.utils;


public class Header {

    String key;
    String value;

    public Header(String headerKey, String headerValue) {
        key = headerKey;
        value = headerValue;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }
}