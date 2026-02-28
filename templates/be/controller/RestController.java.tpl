package {{packageName}}.api;

import {{packageName}}.api.dto.{{entityName}}Request;
import {{packageName}}.api.dto.{{entityName}}Response;
import {{packageName}}.service.{{entityName}}Service;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/{{resourcePath}}")
@RequiredArgsConstructor
public class {{entityName}}Controller {

    private final {{entityName}}Service service;

    @PostMapping
    public ResponseEntity<{{entityName}}Response> create(@Valid @RequestBody {{entityName}}Request request) {
        {{entityName}}Response response = service.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<{{entityName}}Response> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getById(id));
    }

    @GetMapping
    public ResponseEntity<List<{{entityName}}Response>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @PutMapping("/{id}")
    public ResponseEntity<{{entityName}}Response> update(
            @PathVariable UUID id,
            @Valid @RequestBody {{entityName}}Request request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
