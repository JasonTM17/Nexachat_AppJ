package com.nexachat.core.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.squareup.moshi.Moshi;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import org.junit.Test;
import retrofit2.http.Body;
import retrofit2.http.HTTP;
import retrofit2.http.POST;
import retrofit2.http.Query;

public final class DeviceTokenApiContractTest {

    @Test
    public void registrationAndUnregistrationMatchServerPathsWithoutTokenQuery() throws Exception {
        Method register = DeviceTokenApi.class.getMethod(
                "register", DeviceTokenApi.RegistrationRequest.class);
        POST registerPost = register.getAnnotation(POST.class);
        assertEquals("api/v1/users/device-token", registerPost.value());
        assertTrue(hasParameterAnnotation(register, Body.class));
        assertEquals("{\"platform\":\"ANDROID\",\"token\":\"fixture\"}",
                new Moshi.Builder().build()
                        .adapter(DeviceTokenApi.RegistrationRequest.class)
                        .toJson(new DeviceTokenApi.RegistrationRequest("fixture")));

        Method unregister = DeviceTokenApi.class.getMethod(
                "unregister", DeviceTokenApi.UnregisterRequest.class, String.class);
        HTTP unregisterHttp = unregister.getAnnotation(HTTP.class);
        assertEquals("DELETE", unregisterHttp.method());
        assertEquals("api/v1/users/device-token", unregisterHttp.path());
        assertTrue(unregisterHttp.hasBody());
        assertTrue(hasParameterAnnotation(unregister, Body.class));
        assertFalse(hasParameterAnnotation(unregister, Query.class));
        assertEquals("{\"token\":\"fixture\"}",
                new Moshi.Builder().build()
                        .adapter(DeviceTokenApi.UnregisterRequest.class)
                        .toJson(new DeviceTokenApi.UnregisterRequest("fixture")));
    }

    private static boolean hasParameterAnnotation(Method method, Class<?> annotationType) {
        for (Annotation annotation : method.getParameterAnnotations()[0]) {
            if (annotation.annotationType().equals(annotationType)) {
                return true;
            }
        }
        return false;
    }
}
