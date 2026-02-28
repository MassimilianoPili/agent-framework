package {{packageName}}.service;

import {{packageName}}.api.dto.{{entityName}}Request;
import {{packageName}}.api.dto.{{entityName}}Response;
import {{packageName}}.domain.{{entityName}};
import {{packageName}}.mapper.{{entityName}}Mapper;
import {{packageName}}.repository.{{entityName}}Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class {{entityName}}ServiceTest {

    @Mock
    private {{entityName}}Repository repository;

    @Mock
    private {{entityName}}Mapper mapper;

    @InjectMocks
    private {{entityName}}Service service;

    private UUID testId;
    private {{entityName}} testEntity;
    private {{entityName}}Request testRequest;
    private {{entityName}}Response testResponse;

    @BeforeEach
    void setUp() {
        testId = UUID.randomUUID();
        testEntity = new {{entityName}}();
        // TODO: Set up test fixtures
        // testRequest = new {{entityName}}Request(...);
        // testResponse = new {{entityName}}Response(testId, ...);
    }

    @Test
    void create_shouldSaveAndReturnResponse() {
        when(mapper.toEntity(any())).thenReturn(testEntity);
        when(repository.save(any())).thenReturn(testEntity);
        when(mapper.toResponse(any())).thenReturn(testResponse);

        {{entityName}}Response result = service.create(testRequest);

        assertThat(result).isEqualTo(testResponse);
        verify(repository).save(testEntity);
    }

    @Test
    void getById_whenExists_shouldReturnResponse() {
        when(repository.findById(testId)).thenReturn(Optional.of(testEntity));
        when(mapper.toResponse(testEntity)).thenReturn(testResponse);

        {{entityName}}Response result = service.getById(testId);

        assertThat(result).isEqualTo(testResponse);
    }

    @Test
    void getById_whenNotFound_shouldThrow() {
        when(repository.findById(testId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(testId))
                .isInstanceOf({{entityName}}NotFoundException.class);
    }

    @Test
    void delete_whenExists_shouldDelete() {
        when(repository.existsById(testId)).thenReturn(true);

        service.delete(testId);

        verify(repository).deleteById(testId);
    }

    @Test
    void delete_whenNotFound_shouldThrow() {
        when(repository.existsById(testId)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(testId))
                .isInstanceOf({{entityName}}NotFoundException.class);
    }
}
