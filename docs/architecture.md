# Architecture

## 1. Adapter Hierarchy

The core abstraction — interfaces in `:core`, implementations in feature modules.

```mermaid
classDiagram
    direction LR

    class Adapter {
        <<interface>>
        :core
        +prerequisites List~Prerequisite~
        +state StateFlow~AdapterState~
        +canOperate(SystemSnapshot) Boolean
    }

    class HardwareAdapter {
        <<interface>>
        :core
        +deviceInfo DeviceInfo
        +exerciseData StateFlow~ExerciseData~
        +connect()
        +disconnect()
        +sendCommand(DeviceCommand)
    }

    class BroadcastAdapter {
        <<interface>>
        :core
        +connectedClients StateFlow~Set~ClientInfo~~
        +incomingCommands Flow~DeviceCommand~
        +start(Flow~ExerciseData~)
        +stop()
    }

    class FitProAdapter {
        :hardware:fitpro
        +canOperate() checks isUsbHostAvailable
    }

    class FtmsAdapter {
        :broadcast:ftms
        +canOperate() checks isBluetoothLeEnabled
    }

    class DirconAdapter {
        :broadcast:dircon
        +canOperate() checks isWifiEnabled
    }

    Adapter <|-- HardwareAdapter
    Adapter <|-- BroadcastAdapter
    HardwareAdapter <|.. FitProAdapter
    BroadcastAdapter <|.. FtmsAdapter
    BroadcastAdapter <|.. DirconAdapter
```

## 2. Data Flow Types

What flows through the adapter pipeline — exercise telemetry down, device commands up.

```mermaid
classDiagram
    direction LR

    class ExerciseData {
        <<data class>>
        +power Int?
        +cadence Int?
        +speed Float?
        +resistance Int?
        +incline Float?
        +heartRate Int?
        +distance Float?
        +calories Int?
        +elapsedTime Long
    }

    class DeviceCommand {
        <<sealed interface>>
    }

    class SetResistance {
        <<data class>>
        +level Int
    }

    class SetIncline {
        <<data class>>
        +percent Float
    }

    class DeviceInfo {
        <<data class>>
        +name String
        +type DeviceType
        +supportedMetrics Set~Metric~
    }

    class DeviceType {
        <<enumeration>>
        BIKE
        TREADMILL
        ROWER
        ELLIPTICAL
    }

    class Metric {
        <<enumeration>>
        POWER
        CADENCE
        SPEED
        RESISTANCE
        INCLINE
        HEART_RATE
        DISTANCE
        CALORIES
    }

    class AdapterState {
        <<sealed interface>>
    }

    class Inactive {
        <<data object>>
    }

    class Activating {
        <<data object>>
    }

    class Active {
        <<data object>>
    }

    class Error {
        <<data class>>
        +message String
        +cause Throwable?
    }

    class Prerequisite {
        +id String
        +description String
        +isMet (SystemSnapshot) → Boolean
        +fulfill (suspend (SystemController) → FulfillResult)?
    }

    class FulfillResult {
        <<sealed interface>>
    }

    class Success {
        <<data object>>
    }

    class Failed {
        <<data class>>
        +reason String
        +cause Throwable?
    }

    class ClientInfo {
        <<data class>>
        +id String
        +protocol String
        +connectedAt Long
    }

    AdapterState <|-- Inactive
    AdapterState <|-- Activating
    AdapterState <|-- Active
    AdapterState <|-- Error
    FulfillResult <|-- Success
    FulfillResult <|-- Failed
    Prerequisite *-- FulfillResult
    DeviceCommand <|-- SetResistance
    DeviceCommand <|-- SetIncline
    DeviceInfo *-- DeviceType
    DeviceInfo *-- Metric
```

## 3. System Monitoring

Read/write split — `SystemMonitor` observes, `SystemController` mutates.

```mermaid
classDiagram
    direction TB

    class SystemMonitor {
        <<interface>>
        :core
        +snapshot StateFlow~SystemSnapshot~
        +refresh()
    }

    class SystemController {
        <<interface>>
        :core
        +stopService(pkg, cls) Boolean
        +forceStopPackage(pkg) Boolean
        +disablePackage(pkg) Boolean
        +enablePackage(pkg) Boolean
        +uninstallPackage(pkg) Boolean
    }

    class SystemSnapshot {
        <<data class>>
        +status SystemStatus
        +packages List~InstalledPackage~
        +services List~DeclaredService~
        +timestamp Long
    }

    class SystemStatus {
        <<data class>>
        +isBluetoothLeEnabled Boolean
        +isWifiEnabled Boolean
        +isUsbHostAvailable Boolean
        +isAdbEnabled Boolean
    }

    class InstalledPackage {
        <<data class>>
        +packageName String
        +versionName String?
        +versionCode Long
        +isSystemApp Boolean
    }

    class DeclaredService {
        <<data class>>
        +packageName String
        +className String
        +state ServiceState
    }

    class ServiceState {
        <<enumeration>>
        RUNNING
        RUNNING_FOREGROUND
        STOPPED
        DISABLED
    }

    class AndroidSystemMonitor {
        :app
    }

    class AndroidSystemController {
        :app
    }

    SystemMonitor <|.. AndroidSystemMonitor
    SystemController <|.. AndroidSystemController
    SystemMonitor *-- SystemSnapshot
    SystemSnapshot *-- SystemStatus
    SystemSnapshot *-- InstalledPackage
    SystemSnapshot *-- DeclaredService
    DeclaredService *-- ServiceState
```

## 4. Logging

Dual-interface pattern — `AppLogger` for writing, `LogStore` for reading. Both implemented by one singleton.

```mermaid
classDiagram
    direction LR

    class AppLogger {
        <<interface>>
        :core
        +d(tag, message)
        +i(tag, message)
        +w(tag, message)
        +e(tag, message, throwable?)
    }

    class LogStore {
        <<interface>>
        :core
        +entries StateFlow~List~LogEntry~~
        +size StateFlow~Int~
        +clear()
        +export() String
    }

    class LogEntry {
        <<data class>>
        +timestamp Long
        +level LogLevel
        +tag String
        +message String
        +throwable String?
    }

    class LogLevel {
        <<enumeration>>
        DEBUG
        INFO
        WARN
        ERROR
    }

    class RingBufferLogStore {
        :app
        -buffer ArrayDeque~LogEntry~ (5000 max)
        dual-writes to logcat + ring buffer
    }

    class LogExporter {
        :app
        +shareLog(Activity)
        +exportToFile() File
    }

    AppLogger <|.. RingBufferLogStore
    LogStore <|.. RingBufferLogStore
    LogStore *-- LogEntry
    LogEntry *-- LogLevel
    LogExporter --> LogStore
```

## 5. In-App Update System

Two independent update tracks (app APK + firmware OTA) sharing a common download-verify-install pipeline. Entirely within `:app` — no `:core` interfaces needed.

### 5a. Update Class Hierarchy

```mermaid
classDiagram
    direction TB

    class UpdateHttpClient {
        <<interface>>
        +fetchManifest(url) String
        +openDownload(url) DownloadStream
    }

    class DownloadStream {
        <<data class>>
        +inputStream InputStream
        +contentLength Long
    }

    class UpdateInstaller {
        <<interface>>
        +install(path) InstallResult
        +finalize(path)
    }

    class InstallResult {
        <<sealed interface>>
    }

    class IR_Success {
        <<data object>>
        Success
    }

    class IR_Failed {
        <<data class>>
        +reason String
        +cause Throwable?
    }

    class HttpUrlConnectionClient {
        :app
    }

    class AppInstaller {
        :app
        install via su pm install -r
        finalize restarts app via am
    }

    class FirmwareInstaller {
        :app
        install writes /cache/recovery/command
        finalize calls PowerManager.reboot
    }

    UpdateHttpClient <|.. HttpUrlConnectionClient
    UpdateHttpClient *-- DownloadStream
    UpdateInstaller <|.. AppInstaller
    UpdateInstaller <|.. FirmwareInstaller
    UpdateInstaller *-- InstallResult
    InstallResult <|-- IR_Success
    InstallResult <|-- IR_Failed
```

### 5b. Update State Machine

Each track (`appTrack`, `firmwareTrack`) has its own independent `StateFlow<TrackState>`.

```mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Available : manifest has newer version
    Available --> Downloading : download()
    Downloading --> ReadyToInstall : SHA-256 verified
    Downloading --> Error : checksum mismatch / network error
    ReadyToInstall --> Installing : install()
    Installing --> Installed : success
    Installing --> Error : failure
    Installed --> [*] : finalizeInstall() (app restart or device reboot)
    Available --> Idle : dismiss()
    Error --> Idle : dismiss()
```

```mermaid
classDiagram
    direction TB

    class TrackState {
        <<sealed interface>>
    }

    class Idle {
        <<data object>>
    }

    class Available {
        <<data class>>
        +info UpdateInfo
    }

    class Downloading {
        <<data class>>
        +info UpdateInfo
        +progress DownloadProgress
    }

    class ReadyToInstall {
        <<data class>>
        +info UpdateInfo
        +path String
    }

    class Installing {
        <<data class>>
        +info UpdateInfo
    }

    class Installed {
        <<data class>>
        +info UpdateInfo
    }

    class TS_Error {
        <<data class>>
        +message String
        +cause Throwable?
    }

    class UpdateInfo {
        <<data class>>
        +version String
        +url String
        +sha256 String
        +releaseNotes String
    }

    class DownloadProgress {
        <<data class>>
        +bytesDownloaded Long
        +totalBytes Long
    }

    TrackState <|-- Idle
    TrackState <|-- Available
    TrackState <|-- Downloading
    TrackState <|-- ReadyToInstall
    TrackState <|-- Installing
    TrackState <|-- Installed
    TrackState <|-- TS_Error
    Available *-- UpdateInfo
    Downloading *-- UpdateInfo
    Downloading *-- DownloadProgress
    ReadyToInstall *-- UpdateInfo
    Installing *-- UpdateInfo
    Installed *-- UpdateInfo
```

### 5c. Update Orchestration

```mermaid
classDiagram
    direction TB

    class UpdateManager {
        :app @Singleton
        +checking StateFlow~Boolean~
        +appTrack UpdateTrack
        +firmwareTrack UpdateTrack
        +checkForUpdates()
    }

    class UpdateTrack {
        :app (internal constructor)
        +state StateFlow~TrackState~
        +setAvailable(UpdateInfo)
        +download()
        +install()
        +finalizeInstall()
        +dismiss()
    }

    class UpdateManifest {
        <<data class>>
        +app AppUpdate?
        +firmware FirmwareUpdate?
        +parse(json)$ UpdateManifest
    }

    class AppUpdate {
        <<data class>>
        +versionCode Int
        +versionName String
        +url String
        +sha256 String
        +releaseNotes String
    }

    class FirmwareUpdate {
        <<data class>>
        +version String
        +url String
        +sha256 String
        +releaseNotes String
    }

    UpdateManager *-- UpdateTrack : appTrack
    UpdateManager *-- UpdateTrack : firmwareTrack
    UpdateManager --> UpdateManifest : parses
    UpdateManifest *-- AppUpdate
    UpdateManifest *-- FirmwareUpdate
    UpdateTrack --> UpdateInstaller : delegates install
    UpdateTrack --> UpdateHttpClient : delegates download
```

### 5d. Update Data Flow

```mermaid
flowchart LR
    Manifest["Remote JSON\nManifest"] --> Manager["UpdateManager\ncheckForUpdates()"]
    Manager -- "app update?" --> AppTrack["UpdateTrack\n(app)"]
    Manager -- "firmware update?" --> FwTrack["UpdateTrack\n(firmware)"]
    AppTrack -- "download + SHA-256" --> APK["filesDir/update/\nhyperborea.apk"]
    FwTrack -- "download + SHA-256" --> OTA["/cache/\nhyperborea_ota.zip"]
    APK -- "su pm install -r" --> AppInstaller
    OTA -- "/cache/recovery/command" --> FwInstaller["FirmwareInstaller"]
    AppInstaller -- "am force-stop + am start" --> Restart["App Restart"]
    FwInstaller -- "PowerManager.reboot" --> Recovery["Recovery Mode"]
```

## 6. Hilt DI Wiring

How `:app` binds everything together. All bindings are `@Singleton` scope.

```mermaid
flowchart LR
    subgraph AdapterModule["AdapterModule (@Binds)"]
        direction TB
        AM_hw["bindHardwareAdapter()"]
        AM_ftms["bindFtmsAdapter()"]
        AM_dir["bindDirconAdapter()"]
    end

    subgraph PlatformModule
        direction TB
        PM_scope["provideApplicationScope()"]
        PM_log["provideRingBufferLogStore()"]
        PM_al["provideAppLogger()"]
        PM_ls["provideLogStore()"]
        PM_sm["provideSystemMonitor()"]
        PM_sc["provideSystemController()"]
    end

    subgraph UpdateModule["UpdateModule (@Binds)"]
        direction TB
        UM_http["bindHttpClient()"]
    end

    AM_hw --> FitProAdapter:::hw
    AM_ftms --> FtmsAdapter:::bc
    AM_dir --> DirconAdapter:::bc

    PM_log --> RingBufferLogStore:::app
    PM_al --> RingBufferLogStore
    PM_ls --> RingBufferLogStore
    PM_sm --> AndroidSystemMonitor:::app
    PM_sc --> AndroidSystemController:::app
    PM_scope --> CoroutineScope:::ext

    UM_http --> HttpUrlConnectionClient:::app

    FitProAdapter -. "binds as" .-> HardwareAdapter:::core
    FtmsAdapter -. "binds as @IntoSet" .-> BroadcastAdapter:::core
    DirconAdapter -. "binds as @IntoSet" .-> BroadcastAdapter
    RingBufferLogStore -. "binds as" .-> AppLogger:::core
    RingBufferLogStore -. "binds as" .-> LogStore:::core
    AndroidSystemMonitor -. "binds as" .-> SystemMonitor:::core
    AndroidSystemController -. "binds as" .-> SystemController:::core
    HttpUrlConnectionClient -. "binds as" .-> UpdateHttpClient:::app

    classDef core fill:#e8f4f8,stroke:#2980b9
    classDef hw fill:#fef9e7,stroke:#f39c12
    classDef bc fill:#f5eef8,stroke:#8e44ad
    classDef app fill:#eafaf1,stroke:#27ae60
    classDef ext fill:#f2f3f4,stroke:#95a5a6
```

## 7. Runtime Data Flow

The end-to-end pipeline at runtime.

```mermaid
flowchart LR
    USB["USB Serial\n115200 baud"] --> FitPro["FitProAdapter\n:hardware:fitpro"]
    FitPro -- "StateFlow&lt;ExerciseData&gt;" --> Orchestrator
    Orchestrator -- "Flow&lt;ExerciseData&gt;" --> FTMS["FtmsAdapter\n:broadcast:ftms"]
    Orchestrator -- "Flow&lt;ExerciseData&gt;" --> DIRCON["DirconAdapter\n:broadcast:dircon"]
    FTMS -- "BLE GATT" --> Zwift
    DIRCON -- "TCP" --> Wahoo["Wahoo Apps"]

    Zwift -- "resistance/incline" --> FTMS
    FTMS -- "Flow&lt;DeviceCommand&gt;" --> Orchestrator
    Orchestrator -- "sendCommand()" --> FitPro
    FitPro --> USB
```

## Module Dependency Graph

```
:app  →  :core  ←  :hardware:fitpro
  ↓                 :broadcast:ftms
  ↓                 :broadcast:dircon
  └── all modules
```

All feature modules depend only on `:core`. The `:app` module wires everything together via Hilt.

## Relationship Key

| Arrow | Meaning |
|-------|---------|
| `<\|--` solid | Interface inheritance (extends) |
| `<\|..` dashed | Implementation (implements) |
| `*--` | Composition (owns / contains) |
| `-->` | Dependency (uses / provides) |
| `-.->` dashed | DI binding (binds as interface) |
