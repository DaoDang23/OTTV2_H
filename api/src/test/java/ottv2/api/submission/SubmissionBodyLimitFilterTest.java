package ottv2.api.submission;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SubmissionBodyLimitFilterTest {
    @Test
    void rejectsOverLimitSubmissionBodyBeforeCallingController() throws Exception {
        SubmissionBodyLimitFilter filter = new SubmissionBodyLimitFilter(4);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/bots/7/submissions");
        request.setServletPath("/api/bots/7/submissions");
        request.setContent("x".repeat(5000).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertNull(chain.getRequest());
    }
}
