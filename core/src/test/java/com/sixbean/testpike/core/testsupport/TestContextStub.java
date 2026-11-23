package com.sixbean.testpike.core.testsupport;

import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestContext;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;


final class TestContextStub implements InvocationHandler {

    private final ApplicationContext applicationContext;

    private TestContextStub(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    
    static TestContext of(ApplicationContext applicationContext) {
        return (TestContext) Proxy.newProxyInstance(
                TestContext.class.getClassLoader(),
                new Class<?>[]{TestContext.class},
                new TestContextStub(applicationContext));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        if ("getApplicationContext".equals(method.getName())) {
            return applicationContext;
        }
        if ("getClass".equals(method.getName())) {
            return TestContext.class;
        }
        if ("equals".equals(method.getName())) {
            return proxy == args[0];
        }
        if ("hashCode".equals(method.getName())) {
            return System.identityHashCode(proxy);
        }
        if ("toString".equals(method.getName())) {
            return "TestContextStub";
        }
        throw new UnsupportedOperationException("测试桩未实现: " + method.getName());
    }
}
