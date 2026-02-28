package {{packageName}}.mapper;

import {{packageName}}.api.dto.{{entityName}}Request;
import {{packageName}}.api.dto.{{entityName}}Response;
import {{packageName}}.domain.{{entityName}};
import org.springframework.stereotype.Component;

@Component
public class {{entityName}}Mapper {

    public {{entityName}} toEntity({{entityName}}Request request) {
        {{entityName}} entity = new {{entityName}}();
        // TODO: Map request fields to entity fields
        // entity.setName(request.name());
        return entity;
    }

    public {{entityName}}Response toResponse({{entityName}} entity) {
        return new {{entityName}}Response(
                entity.getId()
                // TODO: Map remaining entity fields to response
        );
    }

    public void updateEntity({{entityName}} entity, {{entityName}}Request request) {
        // TODO: Update entity fields from request
        // entity.setName(request.name());
    }
}
