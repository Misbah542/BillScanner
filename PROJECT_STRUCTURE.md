# Bill Scanner Project Structure

## Complete Android Project Structure

```
BillScanner/
├── app/
│   ├── build.gradle.kts                    # App-level build configuration
│   ├── proguard-rules.pro                  # ProGuard rules for code obfuscation
│   └── src/main/
│       ├── AndroidManifest.xml             # App manifest with permissions
│       ├── java/com/billscanner/app/
│       │   ├── BillScannerApplication.kt   # Hilt Application class
│       │   ├── MainActivity.kt             # Main Activity with Compose setup
│       │   ├── data/
│       │   │   ├── api/
│       │   │   │   └── BillScannerApi.kt   # Retrofit API interface
│       │   │   ├── model/
│       │   │   │   ├── BillItem.kt         # Data model for bill items
│       │   │   │   ├── BillResponse.kt     # API response model
│       │   │   │   └── BillTotals.kt       # Bill totals model
│       │   │   └── repository/
│       │   │       └── BillScannerRepository.kt # Repository pattern
│       │   ├── di/
│       │   │   └── NetworkModule.kt        # Hilt dependency injection
│       │   ├── navigation/
│       │   │   └── BillScannerNavigation.kt # Navigation setup
│       │   └── ui/
│       │       ├── screen/
│       │       │   ├── BillResultsScreen.kt # Results display screen
│       │       │   ├── CameraCaptureScreen.kt # Camera capture screen
│       │       │   └── HomeScreen.kt       # Home/welcome screen
│       │       ├── theme/
│       │       │   ├── Color.kt            # App color scheme
│       │       │   ├── Theme.kt            # Material 3 theme
│       │       │   └── Type.kt             # Typography definitions
│       │       └── viewmodel/
│       │           └── BillScannerViewModel.kt # ViewModel for state management
│       └── res/
│           └── values/
│               ├── colors.xml              # Color resources
│               ├── strings.xml             # String resources
│               └── themes.xml              # XML theme definitions
├── build.gradle.kts                        # Project-level build configuration
├── gradle.properties                       # Gradle properties
├── settings.gradle.kts                     # Gradle settings
├── README.md                              # Project documentation
└── PROJECT_STRUCTURE.md                   # This file
```

## Key Features Implemented

### 1. **Modern Architecture**
- MVVM pattern with Jetpack Compose
- Hilt for dependency injection
- Repository pattern for data management
- StateFlow for reactive state management

### 2. **Camera Integration**
- CameraX for modern camera functionality
- Permission handling for camera access
- Image capture and processing

### 3. **API Integration**
- Retrofit for network communication
- Multipart file upload for bill images
- Error handling and loading states

### 4. **UI/UX**
- Material Design 3 components
- Clean, modern interface
- Responsive layouts
- Loading indicators and error states

### 5. **Navigation**
- Navigation Compose for type-safe navigation
- Three main screens: Home, Camera, Results
- Proper back navigation handling

## Data Flow

1. **Home Screen** → User taps "Scan Bill"
2. **Camera Screen** → User captures bill image
3. **API Call** → Image sent to backend for processing
4. **Results Screen** → Displays parsed bill data

## API Response Format

The app expects this JSON structure from the backend:

```json
{
  "items": [
    {
      "name": "Item Name",
      "quantity": 1,
      "rate": 100.0,
      "amount": 100.0
    }
  ],
  "totals": {
    "item_total": 100.0,
    "service_charge": 10.0,
    "state_gst": 5.0,
    "central_gst": 5.0,
    "round_off": 0.0,
    "net_amount": 120.0
  }
}
```

## Setup Requirements

1. Android Studio Arctic Fox or later
2. Kotlin 1.9.22+
3. Minimum SDK 24 (Android 7.0)
4. Target SDK 34 (Android 14)

## Dependencies Used

- **Jetpack Compose**: Modern UI toolkit
- **Hilt**: Dependency injection
- **Retrofit**: HTTP client
- **CameraX**: Camera functionality
- **Navigation Compose**: Navigation
- **Material 3**: Design system

## Next Steps for Development

1. Update API base URL in `NetworkModule.kt`
2. Test with actual backend API
3. Add error handling improvements
4. Implement offline capabilities
5. Add bill history storage
6. Enhance UI with animations
