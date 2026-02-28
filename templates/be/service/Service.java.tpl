package {{packageName}}.service;

import {{packageName}}.api.dto.{{entityName}}Request;
import {{packageName}}.api.dto.{{entityName}}Response;
import {{packageName}}.domain.{{entityName}};
import {{packageName}}.mapper.{{entityName}}Mapper;
import {{packageName}}.repository.{{entityName}}Repository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class {{entityName}}Service {

    private final {{entityName}}Repository repository;
    private final {{entityName}}Mapper mapper;

    @Transactional
    public {{entityName}}Response create({{entityName}}Request request) {
        {{entityName}} entity = mapper.toEntity(request);
        {{entityName}} saved = repository.save(entity);
        return mapper.toResponse(saved);
    }

    public {{entityName}}Response getById(UUID id) {
        {{entityName}} entity = repository.findById(id)
                .orElseThrow(() -> new {{entityName}}NotFoundException(id));
        return mapper.toResponse(entity);
    }

    public List<{{entityName}}Response> getAll() {
        return repository.findAll().stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional
    public {{entityName}}Response update(UUID id, {{entityName}}Request request) {
        {{entityName}} entity = repository.findById(id)
                .orElseThrow(() -> new {{entityName}}NotFoundException(id));
        mapper.updateEntity(entity, request);
        {{entityName}} saved = repository.save(entity);
        return mapper.toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw new {{entityName}}NotFoundException(id);
        }
        repository.deleteById(id);
    }
}
