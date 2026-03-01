package com.agentframework.rag.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class CodeGraphServiceTest {

    private CodeGraphService service;
    private JdbcTemplate mockJdbc;

    @BeforeEach
    void setUp() {
        mockJdbc = mock(JdbcTemplate.class);
        service = new CodeGraphService(mockJdbc);
    }

    @Test
    void shouldAddClassNode() {
        service.addClassNode("UserService", "src/UserService.java", "com.example");
        verify(mockJdbc).execute(argThat((String sql) ->
                sql.contains("MERGE") && sql.contains("Class") && sql.contains("UserService")));
    }

    @Test
    void shouldAddImportEdge() {
        service.addImportEdge("UserService", "com.example.UserRepository");
        verify(mockJdbc).execute(argThat((String sql) ->
                sql.contains("IMPORTS") && sql.contains("UserRepository")));
    }

    @Test
    void shouldFindClassesByName() {
        when(mockJdbc.queryForList(anyString()))
                .thenReturn(List.of(Map.of("result", "UserService")));

        List<Map<String, Object>> result = service.findClassesByName("User");
        assertFalse(result.isEmpty());
    }

    @Test
    void shouldExtractPackageFromJavaSource() {
        String source = "package com.example.service;\n\npublic class MyService {}";
        assertEquals("com.example.service", CodeGraphService.extractPackage(source));
    }

    @Test
    void shouldExtractClassNamesFromJavaSource() {
        String source = """
                public class MyService extends BaseService implements Serializable {
                    // implementation
                }
                public interface MyRepository {
                    void save();
                }
                """;
        List<String> names = CodeGraphService.extractClassNames(source);
        assertTrue(names.contains("MyService"));
        assertTrue(names.contains("MyRepository"));
    }

    @Test
    void shouldExtractAndPopulateFromJavaSource() {
        String source = """
                package com.example;

                import org.springframework.stereotype.Service;

                @Service
                public class UserService extends BaseService implements CrudOperations {
                    // impl
                }
                """;
        service.extractAndPopulate(source, "UserService.java");

        // Should create Class node + EXTENDS edge + IMPLEMENTS edge + IMPORTS edge
        verify(mockJdbc, atLeast(3)).execute(anyString());
    }
}
