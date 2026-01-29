# Android VPN Client

A streamlined Android VPN client based on V2rayNG, customized for VLESS + Reality protocol integration with our VPN service system.

## System Requirements

- **Minimum SDK**: Android 5.0 (API Level 21)
- **Target SDK**: Android 14 (API Level 34)
- **Architecture**: arm64-v8a, armeabi-v7a, x86_64, x86
- **Permissions**: Network access, VPN service permissions

## Development Environment Setup

### Prerequisites

1. **Android Studio** (latest version recommended)
2. **Android SDK** with API Level 21-34
3. **Java Development Kit (JDK)** 17 or higher
4. **Gradle** 8.0+
5. **Android NDK** (for native library compilation)

### SDK Configuration

Ensure you have the following SDK components installed:
```bash
# Install required SDK platforms and tools
sdkmanager "platforms;android-21"
sdkmanager "platforms;android-34"
sdkmanager "build-tools;34.0.0"
sdkmanager "ndk;25.1.8937393"
```

## Building the Project

### 1. Clone and Setup

```bash
# Navigate to the project directory
cd android/

# Ensure gradlew has execute permissions (Linux/macOS)
chmod +x gradlew
```

### 2. Build Debug APK

```bash
# Windows
gradlew.bat assembleDebug

# Linux/macOS
./gradlew assembleDebug
```

The debug APK will be generated at:
`V2rayNG/app/build/outputs/apk/debug/app-debug.apk`

### 3. Build Release APK

```bash
# Windows
gradlew.bat assembleRelease

# Linux/macOS
./gradlew assembleRelease
```

**Note**: Release builds require signing configuration in `app/build.gradle.kts`

### 4. Clean Build

```bash
# Windows
gradlew.bat clean

# Linux/macOS
./gradlew clean
```

## Testing Instructions

### 1. Unit Testing

```bash
# Run unit tests
./gradlew test

# Run tests with coverage report
./gradlew jacocoTestReport
```

### 2. Instrumentation Testing

```bash
# Run instrumentation tests on connected device/emulator
./gradlew connectedAndroidTest
```

### 3. Manual Testing

1. **Install Debug APK**:
   ```bash
   adb install V2rayNG/app/build/outputs/apk/debug/app-debug.apk
   ```

2. **Test Configuration Import**:
   - Launch the app
   - Use the configuration import feature
   - Verify VLESS configuration parsing
   - Test VPN connection establishment

3. **Test Backend Integration**:
   - Verify API communication with backend
   - Test configuration updates
   - Validate user authentication flow

### 4. Performance Testing

```bash
# Profile app performance
./gradlew app:profileInstall

# Memory leak detection
./gradlew app:lintDebug
```

## Code Architecture

### Key Components

- **MainActivity.kt**: Main application entry point and UI controller
- **AngConfigManager**: Configuration management and import logic
- **V2rayNG Core**: Core VPN functionality and protocol handling
- **Backend Integration**: API communication layer for configuration retrieval

### Configuration Import Flow

```kotlin
// Import configuration from backend
val vlessLink = "vless://..."
val (count, _) = AngConfigManager.importBatchConfig(vlessLink, subscriptionId, true)

// Refresh server list after successful import
if (count > 0) {
    mainViewModel.reloadServerList()
}
```

## Build Variants

| Variant | Description | Use Case |
|---------|-------------|----------|
| `debug` | Debug build with logging | Development and testing |
| `release` | Optimized production build | Distribution |

## Supported Architectures

- **arm64-v8a**: 64-bit ARM devices (recommended)
- **armeabi-v7a**: 32-bit ARM devices
- **x86_64**: 64-bit x86 devices (emulators)
- **x86**: 32-bit x86 devices (legacy emulators)

## Troubleshooting

### Common Build Issues

1. **SDK Path Issues**:
   ```bash
   # Set ANDROID_HOME environment variable
   export ANDROID_HOME="/path/to/android/sdk"
   export PATH="$PATH:$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools"
   ```

2. **NDK Build Failures**:
   - Ensure NDK is properly installed via SDK Manager
   - Check NDK version compatibility in `build.gradle.kts`

3. **Gradle Sync Issues**:
   ```bash
   # Clean and rebuild
   ./gradlew clean
   ./gradlew build --refresh-dependencies
   ```

4. **Permission Errors**:
   ```bash
   # Grant execute permission (Linux/macOS)
   chmod +x gradlew
   ```

### Runtime Issues

1. **VPN Service Permission**: Ensure VPN permissions are granted in device settings
2. **Network Configuration**: Verify device has internet connectivity
3. **Backend Connectivity**: Check backend API accessibility

## Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature-name`
3. Make your changes and test thoroughly
4. Submit a pull request with detailed description

## License

Based on V2rayNG project. Please refer to the original project license terms.

## Support

For technical support and bug reports, please open an issue in this repository.