package md.utm.telecom.incidents.exception;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DependencyErrorsTest {
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new FailedDatabaseController())
            .setControllerAdvice(new DependencyErrors()).build();

    @Test
    void failedDatabaseReadReturnsRetryableServiceError() throws Exception {
        mvc.perform(get("/test/database-read"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void failedTransactionStartReturnsTheSameServiceError() throws Exception {
        mvc.perform(post("/test/database-write"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("UNAVAILABLE"));
    }

    @Test
    void droppedBorrowedConnectionDuringJpaRollbackIsUnavailable() throws Exception {
        mvc.perform(get("/test/jpa-rollback"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("UNAVAILABLE"));
    }

    @RestController
    static class FailedDatabaseController {
        @GetMapping("/test/database-read")
        String read() {
            throw new DataAccessResourceFailureException("connection unavailable");
        }

        @PostMapping("/test/database-write")
        String write() {
            throw new CannotCreateTransactionException("connection unavailable");
        }

        @GetMapping("/test/jpa-rollback")
        String rollback() {
            throw new JpaSystemException(new RuntimeException(
                    new SQLException("Connection is closed", "57P01")));
        }
    }
}
