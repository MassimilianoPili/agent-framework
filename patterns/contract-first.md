# Contract-First API Development

## Problem
When backend and frontend teams (or agent workers) develop in parallel, they need a stable interface to code against. If the backend defines the API implicitly through implementation, the frontend discovers breaking changes only at integration time. Agents compound this problem because they work asynchronously and cannot negotiate changes in real-time.

## Solution
Define the **OpenAPI specification first**, validate it with linting (Spectral), detect breaking changes automatically (oasdiff), and generate server stubs and client code from the spec. The contract is the single source of truth.

### Workflow

```
1. Contract worker writes/updates  contracts/user-api.yaml
2. CI runs: Spectral lint + oasdiff breaking change check
3. If green: codegen triggers
   - Backend: Spring interfaces + DTOs (openapi-generator-maven-plugin)
   - Frontend: TypeScript client (openapi-typescript-codegen)
4. BE worker implements the generated interface
5. FE worker uses the generated client
```

### Directory Structure

```
contracts/
├── user-api.yaml           # OpenAPI 3.1 spec
├── order-api.yaml
├── .spectral.yml           # Linting rules
└── oasdiff-config.yml      # Breaking change detection config
```

### Spectral Linting

```yaml
# contracts/.spectral.yml
extends: ["spectral:oas"]

rules:
  operation-operationId: error        # Every operation needs an operationId
  operation-description: warn         # Descriptions recommended
  oas3-valid-schema-example: error    # Examples must match schema
  path-params: error                  # Path params must be defined
  no-eval-in-markdown: error          # Security
  info-contact: warn                  # Contact info recommended

  # Custom rules
  error-envelope-required:
    description: All 4xx/5xx responses must use ErrorEnvelope schema
    given: "$.paths[*][*].responses[?(@property >= '400')]"
    then:
      field: "content.application/json.schema.$ref"
      function: pattern
      functionOptions:
        match: "#/components/schemas/ErrorEnvelope"
    severity: error
```

### Breaking Change Detection (oasdiff)

```bash
# CI step — compare current spec against the version on the target branch
oasdiff breaking contracts/user-api.yaml contracts/user-api.yaml.base \
  --fail-on ERR \
  --format json
```

Breaking changes include: removing an endpoint, removing a required field from a response, adding a required field to a request, changing a field type.

### Backend Codegen (Maven)

```xml
<!-- backend/pom.xml -->
<plugin>
    <groupId>org.openapitools</groupId>
    <artifactId>openapi-generator-maven-plugin</artifactId>
    <configuration>
        <inputSpec>${project.basedir}/../contracts/user-api.yaml</inputSpec>
        <generatorName>spring</generatorName>
        <library>spring-boot</library>
        <configOptions>
            <interfaceOnly>true</interfaceOnly>
            <useSpringBoot3>true</useSpringBoot3>
            <useTags>true</useTags>
            <dateLibrary>java8-localdatetime</dateLibrary>
        </configOptions>
        <modelPackage>com.example.api.model</modelPackage>
        <apiPackage>com.example.api</apiPackage>
    </configuration>
</plugin>
```

### Frontend Codegen

```bash
npx openapi-typescript-codegen \
  --input ../contracts/user-api.yaml \
  --output src/api/generated \
  --client axios \
  --useOptions
```

### Versioning Strategy

| Change Type | Action |
|-------------|--------|
| Additive (new optional field, new endpoint) | Same version, backward compatible |
| Breaking (remove field, change type) | Increment major version path: `/v1/` -> `/v2/` |
| Deprecation | Mark with `deprecated: true`, remove after 2 release cycles |

## Trade-offs

- **Pro**: Frontend and backend can work in parallel with confidence. Agents never produce incompatible code.
- **Pro**: Spectral catches style and correctness issues before any code is written.
- **Pro**: oasdiff in CI prevents accidental breaking changes from being merged.
- **Con**: Codegen output can be verbose and unfamiliar. Developers must resist the urge to edit generated files (they will be overwritten).
- **Con**: Complex schemas (polymorphism, oneOf) sometimes generate awkward code. May require manual mapping in edge cases.
