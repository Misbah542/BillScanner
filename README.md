# Bill Scanner Android App

A modern Android application built with Kotlin and Jetpack Compose that allows users to scan bills using their camera and get detailed breakdowns of items and totals.

## Features

- 📱 **Easy Camera Scanning**: Capture bills using the device camera
- 📊 **Detailed Item Breakdown**: View individual items with quantities, rates, and amounts
- 💰 **Tax & Total Calculations**: Automatic calculation of GST, service charges, and net amounts
- ⚡ **Fast Processing**: Quick API integration for bill processing
- 🎨 **Modern UI**: Clean Material Design 3 interface with Jetpack Compose

## Architecture

The app follows modern Android development practices:

- **MVVM Architecture**: Clean separation of concerns
- **Jetpack Compose**: Modern declarative UI toolkit
- **Hilt**: Dependency injection
- **Retrofit**: Network communication
- **Navigation Compose**: Type-safe navigation
- **StateFlow**: Reactive state management

## Project Structure

```
BillScanner/
├── app/
│   ├── src/main/java/com/billscanner/app/
│   │   ├── data/
│   │   │   ├── api/           # API interfaces
│   │   │   ├── model/         # Data models
│   │   │   └── repository/    # Repository pattern
│   │   ├── di/                # Dependency injection modules
│   │   ├── navigation/        # Navigation setup
│   │   ├── ui/
│   │   │   ├── screen/        # Compose screens
│   │   │   ├── theme/         # App theming
│   │   │   └── viewmodel/     # ViewModels
│   │   ├── MainActivity.kt
│   │   └── BillScannerApplication.kt
│   └── src/main/res/          # Resources (strings, colors, themes)
├── build.gradle.kts
└── settings.gradle.kts
```

## API Integration

The app expects a backend API with the following endpoint:

### POST /scan-bill

**Request**: Multipart form data with image file
**Response**: JSON with bill details

```json
{
  "items": [
    {
      "name": "Aerated Beverages",
      "quantity": 2,
      "rate": 120.0,
      "amount": 240.0
    }
  ],
  "totals": {
    "item_total": 2285.0,
    "service_charge": 228.5,
    "state_gst": 30.84,
    "central_gst": 30.84,
    "round_off": -0.18,
    "net_amount": 2575.0
  }
}
```

## Setup Instructions

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd BillScanner
   ```

2. **Update API Base URL**
   - Open `BillScanner/app/src/main/java/com/billscanner/app/di/NetworkModule.kt`
   - Replace `"https://your-api-base-url.com/api/"` with your actual API base URL

3. **Open in Android Studio**
   - Open Android Studio
   - Select "Open an existing project"
   - Navigate to the BillScanner folder and select it

4. **Build and Run**
   - Wait for Gradle sync to complete
   - Connect an Android device or start an emulator
   - Click "Run" or press Shift+F10

## Dependencies

- **Kotlin**: 1.9.22
- **Compose BOM**: 2024.02.00
- **Hilt**: 2.48
- **Retrofit**: 2.9.0
- **CameraX**: 1.3.1
- **Navigation Compose**: 2.7.7

## Permissions

The app requires the following permissions:
- `CAMERA`: For capturing bill images
- `INTERNET`: For API communication
- `READ_EXTERNAL_STORAGE`: For accessing stored images
- `WRITE_EXTERNAL_STORAGE`: For saving captured images

## Screens

1. **Home Screen**: Welcome screen with scan button and feature list
2. **Camera Screen**: Camera interface for capturing bill images
3. **Results Screen**: Displays scanned bill details with item breakdown and totals

## Customization

### Theming
- Colors: `app/src/main/java/com/billscanner/app/ui/theme/Color.kt`
- Typography: `app/src/main/java/com/billscanner/app/ui/theme/Type.kt`
- Theme: `app/src/main/java/com/billscanner/app/ui/theme/Theme.kt`

### API Configuration
- Network module: `app/src/main/java/com/billscanner/app/di/NetworkModule.kt`
- API interface: `app/src/main/java/com/billscanner/app/data/api/BillScannerApi.kt`

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests if applicable
5. Submit a pull request

## License

This project is licensed under the MIT License - see the LICENSE file for details.
