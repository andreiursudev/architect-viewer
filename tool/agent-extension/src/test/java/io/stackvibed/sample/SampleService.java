package io.stackvibed.sample;

import org.springframework.stereotype.Service;

/** A stereotype class in a base package, with every kind of method {@code MethodMatchers.methods()} sorts. */
@Service
public class SampleService {

    private String name = "sample";

    public SampleService() {}

    public String issue(String sku, int quantity) {
        return sku + quantity + helper();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public static String create(String sku) {
        return sku;
    }

    protected String guarded() {
        return name;
    }

    private String helper() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }

    /** Named like the subclass Spring's CGLIB generates for a proxied bean. */
    @Service
    public static class Proxy$$SpringCGLIB$$0 extends SampleService {}
}
