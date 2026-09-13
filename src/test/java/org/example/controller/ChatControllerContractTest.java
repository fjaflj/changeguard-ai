package org.example.controller;

import org.example.dto.AIOpsRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatControllerContractTest {

    @Test
    void changeRiskEndpointAcceptsOptionalRequestBodyForBackwardCompatibility() throws Exception {
        Method method = ChatController.class.getDeclaredMethod("aiOps", AIOpsRequest.class);

        assertEquals(AIOpsRequest.class, method.getParameterTypes()[0]);
        assertFalse(method.getParameters()[0].getAnnotation(RequestBody.class).required());
    }
}
