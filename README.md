# SAMTEX — Smart NFC & eID Reader

SAMTEX is an offline Android application for inspecting NFC smart cards — with a focus on
ISO/IEC 14443-4 (ISO-DEP) cards such as the Algerian electronic identity card — using only the
public Android NFC APIs and documented, read-only ISO/IEC 7816-4 commands.

> **SAMTEX does not read protected personal data.** Names, photographs, fingerprints, addresses
> and identification numbers on government-issued cards are protected by access-control protocols
> (BAC / PACE / EAC) that require credentials or official authorization. SAMTEX reports this data as
> *restricted* and never attempts to access it.

---

## Features

- **Automatic card detection** with Android Reader Mode (NFC-A, NFC-B, NFC-F, NFC-V).
- **Passive metadata** read from the Android NFC stack without sending any command:
  technologies, tag identifier, ATQA, SAK, ATS historical bytes, higher-layer response,
  max transceive length, extended-length support.
- **ISO-DEP engine**: guarded single-session connection, configurable timeouts,
  transceive-length validation, 61XX / 6CXX handling, explicit error classification,
  guaranteed resource release.
- **APDU toolkit**: ISO/IEC 7816-3/-4 command encoding/decoding (all short and extended cases),
  response parsing, status-word interpreter, BER-TLV parser, ATS / historical-byte analyzers.
- **Card classifier** with *Verified / Inferred / Restricted* provenance on every value.
- **Algerian eID signature detection** (`AlgeriaEIDv2` in the historical bytes).
- **Public standard probe** (can be disabled): reads only ICAO Doc 9303 objects that are
  public by design — EF.CardAccess (supported security protocols, e.g. PACE) and the presence
  of the eMRTD application.
- **APDU diagnostics console** limited to an allowlist of documented read-only commands, plus an
  offline response decoder.
- **Report export** (JSON / TXT) through the Storage Access Framework, identifiers redacted by
  default.
- **Optional scan history** (explicit opt-in) encrypted with an Android Keystore AES-GCM key.
- **English, Arabic (RTL) and French** interface.
- **100 % offline**: no `INTERNET` permission, no analytics, no ads, no accounts.

## Screenshots

| Home | Results | Technical details | Diagnostics |
|---|---|---|---|
| _add `docs/screenshots/home.png`_ | _add `docs/screenshots/results.png`_ | _add `docs/screenshots/details.png`_ | _add `docs/screenshots/diagnostics.png`_ |

Screenshots should be captured on a real device with a real card (identifiers redacted).

## Architecture

Clean Architecture + MVVM, Kotlin Coroutines/StateFlow, Hilt DI, Jetpack Compose (Material 3).

```
com.samtex.eidreader
├── ui/                      Compose UI (MVVM)
│   ├── home/                HomeScreen + ScanViewModel (status, animated NFC icon, results panel)
│   ├── results/             ResultsScreen ("Technical Details": hex/ASCII/explanations/copy)
│   ├── diagnostics/         DiagnosticsScreen + ViewModel (APDU console, offline decoder)
│   ├── settings/            SettingsScreen + ViewModel (privacy, language, history)
│   └── components/, theme/, navigation/
├── domain/
│   ├── model/               ScanResult, ScanPhase, DiscoveredCard abstraction
│   └── usecase/             ScanCardUseCase, IdentifyCardUseCase, ParseResponseUseCase,
│                            ExportReportUseCase, RunDiagnosticUseCase
├── nfc/                     NFC engine (platform-independent except discovery/transport/tag reader)
│   ├── discovery/           NfcDiscoveryManager (Reader Mode, adapter state)
│   ├── session/             IsoDepSessionManager (mutex, timeouts, always-close)
│   ├── transport/           ApduTransport, IsoDepTransport, ApduChannel
│   ├── apdu/                CommandApdu, ResponseApdu, StatusWords, ApduParser,
│   │                        ApduValidator, ApduPolicy, BerTlv
│   ├── ats/                 AtsAnalyzer, HistoricalBytesParser, AtqaSakDecoder
│   ├── classifier/          CardClassifier, EidSignatureDetector, IcManufacturers
│   ├── protocol/            CardProtocolAdapter + ICAO 9303 public adapters
│   ├── tag/                 TagSnapshot + TagSnapshotReader
│   └── error/               ErrorKind, NfcEngineException, NfcErrorHandler
├── security/                InputValidator, PrivacyManager, SecureStorage (Keystore)
├── diagnostics/             SessionLogger, ReportGenerator, ErrorAnalyzer, Json
└── data/                    ScanController, SettingsRepository, Room history
```

Scan pipeline (`ScanCardUseCase`):

1. **Passive snapshot** – `TagSnapshotReader` copies the values Android cached during anticollision.
2. **ISO-DEP session** – only if the tag exposes `IsoDep`; `IsoDepSessionManager` refuses concurrent
   sessions, applies `IsoDep.setTimeout`, bounds the whole session and always closes the connection.
3. **Public probes** – each `CardProtocolAdapter` sends only commands from a public specification.
   A failure in one adapter does not abort the others; losing the card ends the session but keeps
   the metadata already collected.
4. **Classification** – `CardClassifier` combines snapshot + probe results with provenance.

New, officially documented and user-authorized access methods can be added as additional
`CardProtocolAdapter` implementations without touching transport or UI code.

## Requirements

- Android 8.0 (API 26) or newer, with NFC hardware.
- Build: JDK 17+, Android SDK Platform 35, Gradle wrapper (8.11.1, downloaded automatically).

## Build instructions

```bash
./gradlew testDebugUnitTest   # unit tests
./gradlew lintDebug           # Android Lint
./gradlew assembleDebug       # → app/build/outputs/apk/debug/SAMTEX-debug.apk
```

Open the project in Android Studio (Ladybug or newer) to run it on a device.

### Release builds

A release APK is signed **only** when all of these environment variables are set:
`SAMTEX_KEYSTORE_PATH`, `SAMTEX_KEYSTORE_PASSWORD`, `SAMTEX_KEY_ALIAS`, `SAMTEX_KEY_PASSWORD`.
No signing material is committed to the repository.

## GitHub Actions

`.github/workflows/android-build.yml` runs on every push and on manual dispatch:

1. Sets up JDK 17, the Android SDK and Gradle caching.
2. Runs unit tests and Android Lint.
3. Builds the debug APK, verifies it exists and computes its SHA-256.
4. Uploads **`SAMTEX-Android-APK`** (`SAMTEX-debug.apk` + `.sha256`) and the test/lint reports.
5. Writes a summary (status, size, checksum) to the run page.

To download the APK: *Actions → Android Build → latest run → Artifacts → SAMTEX-Android-APK*.

The debug APK is signed with the standard Android debug key and is **not a production release**.
A separate `release` job builds a signed release APK only if these repository secrets exist:
`SAMTEX_KEYSTORE_BASE64`, `SAMTEX_KEYSTORE_PASSWORD`, `SAMTEX_KEY_ALIAS`, `SAMTEX_KEY_PASSWORD`.

## NFC compatibility notes

- **Algerian eID** – a previously observed card reported: ISO 14443-4, NfcA + IsoDep,
  ATQA `0x0008`, SAK `0x20`, historical bytes `416C67657269614549447632` (“AlgeriaEIDv2”).
  These values are used as **test fixtures**, not as guaranteed characteristics of every card.
- **Historical bytes ≠ ATS.** Android's `IsoDep.getHistoricalBytes()` returns only the historical
  bytes of the ATS; the full ATS frame (TL, T0, TA, TB, TC) is not exposed. SAMTEX labels the ATS as
  “not exposed by Android”. `AtsAnalyzer` can decode complete ATS frames obtained elsewhere.
- **“Fujitsu” manufacturer.** With a 4-byte UID starting with `08`, ISO/IEC 14443-3 defines the UID
  as *random* (regenerated at each activation). Some tools map `08` to Fujitsu (its ISO/IEC 7816-6
  code); SAMTEX reports the manufacturer as *not identifiable* instead.
- The signature interpretation (“Algerian electronic identity card”) is marked **Inferred**; the
  signature text itself is **Verified**. SAMTEX does not assume which applications the card hosts —
  it checks for them with documented SELECT commands.
- SAMTEX uses `FLAG_READER_SKIP_NDEF_CHECK`, so the platform does not send its own NDEF probe.
- Real-world compatibility depends on the phone's NFC controller and antenna position.
  **Compatibility with physical cards has not been verified by the automated tests.**

## Security and privacy model

| Area | Measure |
|---|---|
| Network | No `INTERNET`/`ACCESS_NETWORK_STATE` permission (explicitly removed from the merged manifest). No telemetry. |
| Card access | Only SELECT / READ BINARY / READ RECORD / GET DATA / GET RESPONSE; VERIFY, authentication, key management, secure messaging and write/lifecycle commands are refused by `ApduPolicy`. No PIN attempts, no brute force, no enumeration. |
| Identifiers | Tag IDs redacted by default on screen, in logs, history and reports; copying marks the clip as sensitive. |
| Logs | Per-session in-memory logs; response data from non-public objects is reduced to its length. Nothing is written to Logcat. |
| Storage | History is opt-in, stores redacted reports encrypted with AES-256-GCM (Android Keystore). Revoking consent deletes history and the key. Backups/device transfer are disabled. |
| Export | Storage Access Framework (user picks destination). Unredacted identifiers require an explicit checkbox **and** a confirmation dialog. |

## Known limitations

- Protected identity data (DG1 MRZ, DG2 photo, DG3 fingerprints, addresses, numbers) is **not read**:
  it requires BAC/PACE with credentials printed on the card, or official terminal authorization.
- The full ATS is not available through Android APIs.
- MIFARE Classic sectors, DESFire applications and other keyed content are not read.
- Technical explanations generated by the engine are in English; the interface is localized
  (EN/AR/FR).
- Behaviour on specific phones and cards must be validated manually on real hardware.

## Testing

```bash
./gradlew testDebugUnitTest
```

The JVM test suite covers hex encoding/decoding, ATS and historical-byte parsing, APDU
construction and parsing (all cases), status-word interpretation, invalid input, empty and
truncated responses, unexpected response lengths, GET RESPONSE / wrong-Le handling, connection
interruptions, timeouts, concurrent-session rejection, session lifecycle, EF.CardAccess decoding,
the authorization policy, report generation/redaction and classifier accuracy.

Tests run against `SimulatedTransport` and `VirtualIsoCard` (in `app/src/test/.../testing`).
**These are simulations**: they validate SAMTEX's logic, not its compatibility with any physical
card. Real-device validation checklist:

1. Install the debug APK on an NFC phone, enable NFC.
2. Hold the card on the NFC antenna area; check *Card Detected → Connecting → Reading → Completed*.
3. Remove the card mid-read and check that *Connection Lost* is shown with partial results.
4. Disable NFC while the app is open and check the *NFC Disabled* state and recovery.
5. Export JSON/TXT and verify identifiers are redacted unless explicitly included.
