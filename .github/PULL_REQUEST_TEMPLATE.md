## Summary

<!-- What does this PR do? Describe the change and why it is needed. -->

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Refactor (no behaviour change)
- [ ] Documentation
- [ ] Build / CI
- [ ] Other:

## Testing

- [ ] All existing tests pass (`./gradlew test`)
- [ ] New behaviour is covered by tests written before the implementation
- [ ] Extension isolation check passes:
  ```
  ./gradlew :extensions:dependencies --configuration runtimeClasspath | grep infrastructure
  ```
  (must produce no output)

## Checklist

- [ ] Commit messages follow the [Conventional Commits](https://www.conventionalcommits.org/) format
- [ ] No comments explaining *what* code does - names and tests do that
- [ ] No unnecessary abstractions introduced
- [ ] `CHANGELOG.md` updated if this is a user-facing change
