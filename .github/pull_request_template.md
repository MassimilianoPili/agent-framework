## Summary

<!-- Brief description of what this PR does -->

## Changes

- [ ] ...

## Type

- [ ] Feature
- [ ] Bug fix
- [ ] Refactoring
- [ ] Infrastructure
- [ ] Documentation
- [ ] Contract change

## Checklist

- [ ] Tests pass (`mvn verify` / `npm test`)
- [ ] No breaking contract changes (or `contracts/openapi/rules/breaking-change.yml` reviewed)
- [ ] Spectral lint clean (`scripts/contracts/validate-openapi.sh`)
- [ ] No secrets committed (`config/security-policy.yml` patterns checked)
- [ ] Coverage above threshold (`config/quality-gates.yml`)
- [ ] Documentation updated (if applicable)

## Contract Impact

<!-- If this PR modifies contracts/openapi/*.yaml, describe the changes -->
- [ ] No contract changes
- [ ] Non-breaking changes only
- [ ] Breaking changes (requires version bump and migration plan)

## Related Issues

<!-- Link to related issues: Fixes #123, Part of #456 -->
