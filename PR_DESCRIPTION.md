Closes #58

## What was done
Implemented environment variable support in install.sh and created proper test scripts that exercise the real installer.

## Checklist
- [ ] `./gradlew build` passes locally
- [ ] All acceptance criteria of the issue are met (tick them in the issue)
- [ ] No changes outside the issue's scope
- [ ] `docs/Architecture.md` and `docs/decisions.md` untouched
- [ ] New dependencies (name, version, license): none
- [ ] SPDX header in all new source files

## Test Coverage

### Criteria Coverage
1. **Installation on Ubuntu/Debian containers with systemd**: Container tests exist but could not be run locally (Docker unavailable)
2. **Checksum verification with manipulated archive**: Tested locally - fails with exit code 1, no files under `/opt/cringle`
3. **Version switching with unchanged config/data**: Tested locally - `current` symlink switches correctly, `/etc/cringle` and `/var/lib/cringle` unchanged
4. **Uninstall behavior**: Tested locally - `--uninstall` removes units/symlink/`opt/cringle`, keeps data+config
5. **cringle --version for normal user**: Tested locally - symlink works, version command executes
6. **docs/daemon-service.md updated**: Updated with installer references

### Test Results
- **Local tests (Linux)**: All tests passed
- **BUILD SUCCESSFUL**

### Tests That Could Not Run
- Criterion 1 requires Docker container tests (Ubuntu LTS and Debian stable)
- Docker was not available on the local machine
- All other criteria (2-6) have corresponding local tests

## Deviations, open questions, follow-up issues
None

## Local Build Result
- Operating System: Linux
- Build status: BUILD SUCCESSFUL
- Tests: 0 new tests (test scripts added but not counted as Gradle tests)
