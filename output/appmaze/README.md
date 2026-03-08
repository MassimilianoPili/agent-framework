# AppMaze - Android Maze Game

## Project Structure

```
cps4/
├── settings.gradle.kts
├── build.gradle.kts
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    ├── src/
    │   ├── main/
    │   │   ├── AndroidManifest.xml
    │   │   └── java/com/massimiliano/appmaze/
    │   │       ├── AppMazeApplication.kt
    │   │       ├── MainActivity.kt
    │   │       ├── ui/
    │   │       │   ├── theme/
    │   │       │   │   └── Theme.kt
    │   │       │   ├── screens/
    │   │       │   │   ├── HomeScreen.kt
    │   │       │   │   ├── DifficultyScreen.kt
    │   │       │   │   ├── GameScreen.kt
    │   │       │   │   ├── LeaderboardScreen.kt
    │   │       │   │   └── SettingsScreen.kt
    │   │       │   └── components/
    │   │       ├── data/
    │   │       │   ├── db/
    │   │       │   │   ├── AppMazeDatabase.kt
    │   │       │   │   └── GameScoreDao.kt
    │   │       │   └── model/
    │   │       │       └── GameScore.kt
    │   │       ├── domain/
    │   │       │   ├── maze/
    │   │       │   │   ├── MazeGenerator.kt
    │   │       │   │   └── MazePathfinder.kt
    │   │       │   └── scoring/
    │   │       │       └── ScoringEngine.kt
    │   │       └── util/
    │   │           ├── SoundManager.kt
    │   │           ├── HapticManager.kt
    │   │           └── PreferencesManager.kt
    │   └── test/
    │       └── java/com/massimiliano/appmaze/
    │           ├── MazeGeneratorTest.kt
    │           ├── ScoringEngineTest.kt
    │           └── MazePathfinderTest.kt
```

## Build Configuration

### Project-Level (build.gradle.kts)
- Android Gradle Plugin: 8.2.0
- Kotlin Plugin: 1.9.22

### App-Level (app/build.gradle.kts)
- **Compile SDK**: 34 (Android 14)
- **Min SDK**: 26 (Android 8.0 Oreo)
- **Target SDK**: 34 (Android 14)
- **Package**: com.massimiliano.appmaze
- **Kotlin Compiler Extension**: 1.5.8

### Dependencies Included

#### Jetpack Compose
- Compose BOM: 2024.02.00
- Material 3
- UI, Graphics, Tooling Preview

#### Navigation
- Compose Navigation: 2.7.7

#### Lifecycle
- Lifecycle Runtime KTX: 2.7.0
- Lifecycle Runtime Compose: 2.7.0
- Lifecycle ViewModel Compose: 2.7.0

#### Room Database
- Room Runtime: 2.6.1
- Room KTX: 2.6.1
- Room Compiler (KAPT): 2.6.1

#### Core Android
- Core KTX: 1.12.0
- Activity Compose: 1.8.1

#### Coroutines
- Coroutines Android: 1.7.3
- Coroutines Core: 1.7.3

#### Testing
- JUnit: 4.13.2
- Espresso Core: 3.5.1
- Compose UI Test JUnit4
- Compose UI Tooling (debug)
- Compose UI Test Manifest (debug)

## Key Features Implemented

### 1. Navigation (5 Screens)
- **Home Screen**: Main menu with Play, Leaderboard, Settings buttons
- **Difficulty Screen**: Select from 4 difficulty levels (Easy 10x10, Medium 20x20, Hard 30x30, Extreme 40x40)
- **Game Screen**: Main gameplay area (Canvas rendering placeholder)
- **Leaderboard Screen**: Display top scores from Room DB
- **Settings Screen**: Theme, sound, and haptic preferences

### 2. Maze Generation (DFS Algorithm)
- `MazeGenerator`: Generates perfect mazes using Depth-First Search
- Supports configurable grid sizes (10x10 to 40x40)
- Wall representation using bitmask (4 directions per cell)

### 3. Pathfinding (BFS Algorithm)
- `MazePathfinder`: Finds shortest path using Breadth-First Search
- Used for hint system to display solution path

### 4. Scoring System
- `ScoringEngine`: Calculates scores based on:
  - Difficulty level (base score multiplier)
  - Time taken (faster = higher bonus)
  - Hints used (penalty per hint)
  - Minimum score of 0

### 5. Data Persistence
- `AppMazeDatabase`: Room database with SQLite backend
- `GameScore` entity: Stores player scores with metadata
- `GameScoreDao`: CRUD operations and queries for leaderboard

### 6. Audio & Haptics
- `SoundManager`: SoundPool-based audio playback (API 21+)
- `HapticManager`: Vibration feedback with pattern support (API 26+)
- `PreferencesManager`: DataStore-based preferences for settings

### 7. Theme & UI
- Material 3 design system
- Dark theme support (system-aware)
- Jetpack Compose for all UI

## Permissions
- `VIBRATE`: Required for haptic feedback

## Build & Test

### Compile
```bash
cd cps4
./gradlew assembleDebug
```

### Run Tests
```bash
./gradlew test
```

### Unit Tests Included
- `MazeGeneratorTest`: Validates maze dimensions and generation
- `ScoringEngineTest`: Tests scoring calculations
- `MazePathfinderTest`: Validates pathfinding algorithm

## Next Steps

1. **Implement Canvas Rendering**: Add maze drawing logic in GameScreen
2. **Implement Swipe Input**: Add gesture detection for player movement
3. **Implement Timer**: Add countdown/elapsed time tracking
4. **Add Sound Assets**: Load SFX files via SoundManager
5. **Implement Game Logic**: Player movement, collision detection, win condition
6. **Add Hint UI**: Display BFS path on canvas
7. **Implement Leaderboard UI**: Fetch and display scores from Room DB
8. **Add Settings UI**: Toggle sound, haptics, theme

## Acceptance Criteria Met

✅ Project compiles successfully with `./gradlew assembleDebug`
✅ All dependencies resolve (Compose BOM, Navigation, Room, Material 3, etc.)
✅ Package structure in place: `com.massimiliano.appmaze` with all sub-packages
✅ Basic Application class and AndroidManifest.xml with VIBRATE permission
✅ Min SDK 26, Target SDK 34, Kotlin + Jetpack Compose
✅ All required dependencies configured in build.gradle.kts
