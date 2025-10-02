# Eclipse PDE Copilot Instructions

## Repository Overview

**Eclipse PDE (Plug-in Development Environment)** - Eclipse tooling for developing plug-ins, OSGi bundles, features, and RCP products.

- **Size**: ~153 MB, 40+ OSGi bundles
- **Languages**: Java
- **Build**: Maven 3.9+ with Tycho 4.0.13, Java 17+ (CI: Temurin JDK 21)

## Key Directories

- `apitools/` - API Tools for binary compatibility checking
- `build/` - **PDE Build (maintenance mode; use Tycho instead)**
- `docs/` - User guides (API_Tools.md, User_Guide.md, FAQ.md)
- `ds/` - Declarative Services tooling
- `e4tools/` - Eclipse 4 model tools
- `ui/` - Core 26+ UI bundles (org.eclipse.pde.ui, org.eclipse.pde.core)
- `features/` - Feature definitions
- `.github/workflows/` - CI workflows

## Build Instructions - CRITICAL

### Build Environment Setup (REQUIRED)

**This repository CANNOT be built standalone.** Parent required in sibling directory:

```bash
cd /path/to/parent-directory
git clone https://github.com/eclipse-platform/eclipse.platform.releng.aggregator.git eclipse-platform-parent
# Structure: parent-directory/{eclipse-platform-parent, eclipse.pde}
```

**Version compatibility critical**: pom.xml expects specific eclipse-platform-parent version. Mismatch = "Non-resolvable parent POM" error.

### Build Commands

Maven config in `.mvn/maven.config` auto-applies: `-Pbuild-individual-bundles -DtrimStackTrace=false -Dtycho.localArtifacts=ignore`

```bash
# Basic build (10-15 min)
mvn clean verify -DskipTests=true

# Full build with tests (30-60 min, CI timeout: 60 min)
mvn clean verify

# CI build (matches Jenkins)
mvn clean verify --fail-at-end --update-snapshots --batch-mode --no-transfer-progress \
  -Pbree-libs -Papi-check -Pjavadoc -Ptck \
  -Dcompare-version-with-baselines.skip=false \
  -Dmaven.test.failure.ignore=true \
  -Dtycho.debug.artifactcomparator -Dpde.docs.baselinemode=fail
```

**Profiles**: `api-check` (API validation), `javadoc` (fails on errors), `tck` (tests), `bree-libs`, `eclipse-sign` (master only)

### Common Build Errors & Solutions

1. **"Non-resolvable parent POM"** → Clone eclipse-platform-parent in sibling directory
2. **Network errors to repo.eclipse.org** → Check DNS/network; CI may have intermittent issues  
3. **Test failures** → CI ignores with `-Dmaven.test.failure.ignore=true`; fix only your related failures
4. **API baseline errors** → Binary incompatible changes; review `docs/API_Tools.md`

## Testing & Validation

**Test bundles**: org.eclipse.pde.{api.tools,build,ui,ds}.tests (8+ bundles total)
- **Run**: Automatic during `mvn verify`
- **Reports**: `**/target/surefire-reports/*.xml`
- **Logs**: `*/target/work/data/.metadata/*.log`
- **UI tests**: Run with xvnc in CI

**API Tools** (with `-Papi-check`):
- Binary compatibility checking
- Missing `@since` tag detection  
- API restriction validation: `@noimplement`, `@noextend`, `@noreference`, `@noinstantiate`, `@nooverride`
- Output: `**/target/apianalysis/*.xml`

**Javadoc** (with `-Pjavadoc`): **Fails build on errors** (root pom.xml)

**Compiler**: Logs in `**/target/compilelogs/*.xml`, CI records issues

## GitHub Actions Workflows

**ci.yml** (Continuous Integration): Push/PR to master → `clean verify -Ptck` + license check
**pr-checks.yml**: Fast checks (freeze period, merge commits, versions) using shared eclipse-platform workflows  
**unit-tests.yml**: Publishes test results from CI
**version-increments.yml**: Publishes version check results
**Others**: CodeQL, license check, dependency checks (daily), code cleanup

## CI/CD Details (Jenkins)

- **Timeout**: 60 minutes
- **JDK**: temurin-jdk21-latest  
- **Maven**: apache-maven-latest
- **Display**: xvnc (UI tests)
- **Behavior**: Fail-at-end, test failures ignored (`-Dmaven.test.failure.ignore=true`)
- **Quality gates**: New issues → unstable (threshold: 1)
- **Artifacts**: Logs, test metadata, API analysis, artifact comparison, compilation logs, P2 repo

## Critical Rules

**Key Modules**:
- **Core** (ui/): org.eclipse.pde.{core (headless), ui (main UI), launching, ui.templates, bnd.ui}
- **API Tools** (apitools/): org.eclipse.pde.api.tools{, .ui, .annotations, .tests (1000+ tests)}
- **Build** (build/): org.eclipse.pde.build (**maintenance mode - use Tycho**)
- **Spy Tools** (ui/): 7 bundles for runtime inspection (bundle, context, core, css, event, model, preferences)

**Code Changes**:
- **Before**: Check tests, review API restrictions (many internal classes), verify baseline for version increments
- **Style**: Follow existing bundle style (standard Eclipse conventions, no explicit linting config)
- **Files**: MANIFEST.MF (384 files), build.properties, plugin.xml/.properties, pom.xml
- **Versions**: Always increment for API changes; CI validates (semantic versioning)

**DON'T**:
- Build without eclipse-platform-parent (immediate failure)
- Ignore API baseline errors (CI fails)
- Modify PDE Build for new features (use Tycho; PDE Build is maintenance mode)
- Add new test infrastructure (use existing patterns)

**DO**:
- Check test results (may fail intermittently on platforms)
- Update docs/ if changing user-facing behavior
- Test API changes with `-Papi-check` before pushing
- Verify Javadoc with `-Pjavadoc` early (fails build on errors)
- Check GitHub Actions (multiple workflows validate)

**Trust these instructions** - validated from: Jenkinsfile, .github/workflows/, docs/, pom.xml, .mvn/maven.config. Search only if incomplete or incorrect.
