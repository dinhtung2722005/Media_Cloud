package com.cloudmedia.api;

import com.cloudmedia.api.security.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class JwtConfigurationTests {
    @Test void weakOrInvalidSigningKeysFailAtStartup() {
        for (String value : new String[]{"", "invalid-base64!", "c2hvcnQ="}) {
            JwtUtils jwt = new JwtUtils();
            ReflectionTestUtils.setField(jwt, "jwtSecret", value);
            assertThatThrownBy(jwt::validateConfiguration).isInstanceOf(IllegalStateException.class);
        }
    }
}
