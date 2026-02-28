# Skill: Contract-First API Development

## Workflow

```
1. Write OpenAPI 3.1 YAML in contracts/
2. Run Spectral lint:     npx spectral lint contracts/user-api.yaml
3. Check breaking changes: oasdiff breaking base.yaml current.yaml
4. Generate code:
   - BE: mvn generate-sources (openapi-generator-maven-plugin)
   - FE: npx openapi-typescript-codegen --input ../contracts/user-api.yaml
5. Implement the generated interfaces (BE) / use the generated client (FE)
```

## OpenAPI Spec Template

```yaml
openapi: "3.1.0"
info:
  title: User API
  version: "1.0.0"
  contact:
    name: Agent Framework
paths:
  /v1/users:
    get:
      operationId: listUsers
      summary: List users with pagination
      parameters:
        - $ref: "#/components/parameters/PageParam"
        - $ref: "#/components/parameters/SizeParam"
      responses:
        "200":
          description: Paginated list of users
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/PagedUsers"
        "401":
          $ref: "#/components/responses/Unauthorized"
        "500":
          $ref: "#/components/responses/InternalError"
    post:
      operationId: createUser
      summary: Create a new user
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/CreateUserRequest"
      responses:
        "201":
          description: User created
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/User"
        "400":
          $ref: "#/components/responses/ValidationError"
        "409":
          $ref: "#/components/responses/Conflict"

components:
  schemas:
    ErrorEnvelope:
      type: object
      required: [code, message, traceId]
      properties:
        code:
          type: string
        message:
          type: string
        traceId:
          type: string
        details:
          type: array
          items:
            $ref: "#/components/schemas/FieldError"
    FieldError:
      type: object
      properties:
        field:
          type: string
        reason:
          type: string

  responses:
    ValidationError:
      description: Validation failed
      content:
        application/json:
          schema:
            $ref: "#/components/schemas/ErrorEnvelope"
    Unauthorized:
      description: Authentication required
      content:
        application/json:
          schema:
            $ref: "#/components/schemas/ErrorEnvelope"
    InternalError:
      description: Internal server error
      content:
        application/json:
          schema:
            $ref: "#/components/schemas/ErrorEnvelope"
    Conflict:
      description: Resource conflict
      content:
        application/json:
          schema:
            $ref: "#/components/schemas/ErrorEnvelope"

  parameters:
    PageParam:
      name: page
      in: query
      schema:
        type: integer
        default: 0
        minimum: 0
    SizeParam:
      name: size
      in: query
      schema:
        type: integer
        default: 20
        minimum: 1
        maximum: 100
```

## Validation Pipeline (CI)

```yaml
# In CI pipeline
steps:
  - name: Spectral Lint
    run: npx @stoplight/spectral-cli lint contracts/*.yaml --fail-severity error

  - name: Breaking Change Check
    run: |
      git show origin/develop:contracts/ > /tmp/base-contracts/ 2>/dev/null || true
      for spec in contracts/*.yaml; do
        base="/tmp/base-contracts/$(basename $spec)"
        [ -f "$base" ] && oasdiff breaking "$base" "$spec" --fail-on ERR
      done

  - name: Backend Codegen
    run: cd backend && mvn generate-sources -pl api-stubs

  - name: Frontend Codegen
    run: |
      cd frontend
      npx openapi-typescript-codegen --input ../contracts/user-api.yaml \
        --output src/api/generated --client axios --useOptions
```

## Rules for Workers

1. **contract-worker** owns all files in `contracts/`. Other workers MUST NOT modify specs directly.
2. Every new endpoint MUST have `operationId` (used as method name in codegen).
3. All error responses (4xx, 5xx) MUST reference `ErrorEnvelope`.
4. Use `$ref` for shared schemas. Do not inline complex objects.
5. Adding a new optional field to a response is NOT a breaking change. Adding a required field to a request IS breaking.
6. Pagination parameters MUST use `PageParam` and `SizeParam` from shared components.
