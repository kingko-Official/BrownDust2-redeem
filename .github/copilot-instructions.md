# Copilot Instructions for BrownDust2-redeem

## Project Overview

This is a **Mirai Console plugin** for Brown Dust 2 (BD2) redemption code automation. The plugin enables users to redeem game codes through a bot interface, simplifying the process of entering redemption codes by integrating with the official BD2 redemption API.

**Repository size**: ~1.7MB  
**Language**: Kotlin  
**Framework**: Mirai Console (v2.13.2)  
**Build System**: Gradle 7.3.1  
**Target Runtime**: JVM (Java 11+, tested with Java 17)  
**Single Source File**: `src/main/kotlin/PluginMain.kt` (274 lines)

## Build and Validation

### Prerequisites

- **Java**: JDK 11 or higher (project uses Java 17 in CI)
- **Gradle**: Uses Gradle wrapper 7.3.1 (no manual installation needed)

### CRITICAL: gradlew Permissions

**ALWAYS run `chmod +x ./gradlew` before any Gradle command.** The gradlew script is not executable by default in fresh clones.

```bash
chmod +x ./gradlew
```

### Build Commands

All commands must be run from the repository root:

#### Clean Build (recommended)
```bash
./gradlew clean build
```
**Time**: ~15-20 seconds (first build may take longer due to dependency downloads)  
**Output**: `build/libs/mirai-console-BD2redeem-plugin-1.2.2.jar`

#### Quick Build (incremental)
```bash
./gradlew build
```
**Time**: ~2-3 seconds for incremental builds

#### Build Plugin Distribution
```bash
./gradlew buildPlugin
```
**Output**: `build/mirai/mirai-console-BD2redeem-plugin-1.2.2.mirai2.jar`

### Testing

```bash
./gradlew test
```
**Note**: Project has no unit tests. This command succeeds immediately.

### Available Gradle Tasks

- `./gradlew clean` - Delete build artifacts
- `./gradlew build` - Compile and package
- `./gradlew buildPlugin` - Create Mirai plugin JAR
- `./gradlew buildPluginLegacy` - Create legacy format plugin
- `./gradlew runConsole` - Run Mirai console with plugin loaded
- `./gradlew tasks` - List all available tasks

## CI/CD Pipeline

### GitHub Actions Workflow

**File**: `.github/workflows/Gradle CI.yml`

**Triggers**:
- Push to `master` branch
- Pull requests to `master` branch
- Manual workflow dispatch

**Steps**:
1. Checkout repository
2. Setup Java JDK 11 (Zulu distribution)
3. Validate Gradle wrapper
4. `chmod +x ./gradlew` (REQUIRED)
5. `./gradlew build`

**Environment**: ubuntu-20.04

### Maven Repository Configuration

The build.gradle.kts uses conditional repository configuration:

```kotlin
if (System.getenv("CI")?.toBoolean() != true) {
    maven("https://maven.aliyun.com/repository/public") // 阿里云国内代理仓库
}
mavenCentral()
```

**When CI=true** (GitHub Actions): Only uses Maven Central  
**When CI is unset**: Uses Aliyun mirror for faster downloads in China

## Project Structure

### Root Files
```
.editorconfig          - Kotlin code style (120 char line length, 4 space indent)
.gitignore            - Excludes: .gradle/, build/, .idea/, test/, out/, run/, debug-sandbox
build.gradle.kts      - Main build configuration
settings.gradle.kts   - Project name: "mirai-console-BD2redeem-plugin"
gradle.properties     - kotlin.code.style=official
gradlew / gradlew.bat - Gradle wrapper scripts (Gradle 7.3.1)
LICENSE               - GNU Affero General Public License v3
README.md             - Chinese documentation
```

### Source Code Structure
```
src/main/
├── kotlin/
│   └── PluginMain.kt                           # Single source file with all plugin logic
└── resources/
    └── META-INF/
        └── services/
            └── net.mamoe.mirai.console.plugin.jvm.JvmPlugin  # Plugin service loader
```

**Key Classes in PluginMain.kt**:
- `ConfigData` - Auto-save plugin data for API URL and app ID
- `BindingData` - Stores user ID bindings (QQ -> game userId)
- `UsedCodeData` - Tracks recently used redemption codes
- `PluginMain` - Main plugin class, handles all commands

### Configuration Files

**EditorConfig** (`.editorconfig`):
- Max line length: 120 characters
- Indent size: 4 spaces
- Tab width: 4
- Continuation indent: 4

## Dependencies

### Core Dependencies
- `net.mamoe:mirai-console:2.13.2` - Mirai console framework
- `org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.6.10` → `1.7.10` (transitive upgrade)
- `com.squareup.okhttp3:okhttp:4.9.3` - HTTP client for API calls
- `com.google.code.gson:gson:2.8.9` - JSON parsing

### Kotlin Version
- Plugin compiled with Kotlin 1.6.10
- Runtime uses Kotlin 1.7.10 (from Mirai dependencies)

## Bot Commands Reference

Users interact via these commands:
- `/绑定 <游戏名>` - Bind user to game ID
- `/解绑` - Unbind user
- `/兑换 [游戏名] <兑换码>` or `/redeem [userId] <code>` - Redeem code
- `/绑定信息` - List all bindings
- `/查询绑定 <@user>` - Query specific user binding
- `/查询记录` - Query redemption history

## Common Workflows

### Making Code Changes

1. Make your changes to `src/main/kotlin/PluginMain.kt`
2. **Build immediately to catch errors**:
   ```bash
   ./gradlew clean build
   ```
3. Fix any compilation errors
4. If modifying dependencies, update `build.gradle.kts`
5. Always rebuild after changes

### Adding New Dependencies

When adding dependencies to `build.gradle.kts`:

1. Add to `dependencies` block
2. Run `./gradlew build --refresh-dependencies`
3. Verify build succeeds
4. Check that CI build will work (consider Maven Central availability)

### Version Updates

Current version: `1.2.2` (in both `build.gradle.kts` and `PluginMain.kt`)

To update version:
1. Edit `version = "X.Y.Z"` in `build.gradle.kts`
2. Edit `version = "X.Y.Z"` in `PluginMain.kt` JvmPluginDescription
3. Rebuild with `./gradlew clean build`

## Known Issues and Workarounds

### 1. Gradlew Permission Error
**Error**: `bash: ./gradlew: Permission denied`  
**Solution**: Always run `chmod +x ./gradlew` first

### 2. First Build Takes Long Time
**Issue**: Initial build downloads ~30+ MB of dependencies  
**Expected**: 30-60 seconds on first run  
**Normal**: 2-3 seconds for subsequent builds

### 3. Kotlin Version Conflicts
**Note**: Plugin uses Kotlin 1.6.10, but dependencies bring in 1.7.10  
**Status**: This is expected and works correctly due to Kotlin's compatibility

### 4. No Test Suite
**Note**: `./gradlew test` passes immediately because no tests exist  
**Recommendation**: Manual testing required for all changes

## Architecture Notes

### Data Persistence
- `ConfigData.yml` - API configuration (created at runtime)
- `BindingData.yml` - User bindings (created at runtime)
- `UsedCodeData.yml` - Redemption history (created at runtime)

These files are auto-saved by Mirai Console framework.

### API Integration
- **Endpoint**: `https://loj2urwaua.execute-api.ap-northeast-1.amazonaws.com/prod/coupon`
- **Method**: POST
- **Content-Type**: application/json
- **Headers**: Origin, Referer, Accept, User-Agent (mimics browser)
- **Body**: `{ "appId": "bd2-live", "userId": "<user>", "code": "<code>" }`

### Error Messages (Chinese)
The plugin provides localized feedback for API errors:
- `InvalidCode` → "打错了吧"
- `AlreadyUsed` → "换过了吧"
- `IncorrectUser` → "。名字绑错了"
- `ExpiredCode` → "过期了吧"
- `UnavailableCode` → "还不让用"
- `BadRequest` → "请求格式错误，请检查用户名格式是否正确"

## Quick Reference

### File Paths to Know
- Main source: `/src/main/kotlin/PluginMain.kt`
- Build config: `/build.gradle.kts`
- CI workflow: `/.github/workflows/Gradle CI.yml`
- Plugin output: `/build/mirai/mirai-console-BD2redeem-plugin-*.mirai2.jar`

### Commands Cheat Sheet
```bash
# Setup (one time)
chmod +x ./gradlew

# Development cycle
./gradlew clean build      # Clean + compile + package
./gradlew buildPlugin      # Build Mirai plugin JAR

# CI simulation
CI=true ./gradlew clean build
```

## Tips for Coding Agents

1. **Trust these instructions** - They are validated and accurate. Only search for additional information if something is unclear or incorrect.

2. **Always chmod gradlew first** - This is the #1 cause of build failures in fresh environments.

3. **Build incrementally** - After each code change, run `./gradlew build` to verify. Don't accumulate changes.

4. **Single source file** - All code is in `PluginMain.kt`. No need to search for other Kotlin files.

5. **No linting tools configured** - Follow .editorconfig style manually or use IDE formatting.

6. **CI environment variable matters** - When testing CI builds locally, set `CI=true` to match GitHub Actions behavior.

7. **Kotlin code style** - Use 4-space indentation, 120 character line limit per .editorconfig.

8. **Chinese comments and strings** - This is intentional for the target audience. Preserve them.

9. **Version consistency** - When bumping version, update both build.gradle.kts and PluginMain.kt.

10. **Manual testing only** - No automated tests exist. Describe test steps for maintainers.
