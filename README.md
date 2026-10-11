# SAMTEX — Smart NFC & eID Reader

SAMTEX is an offline, privacy-first Android application that reads NFC smart cards, with a focus
on the **Algerian biometric national identity card (CNIBE)**, using public Android NFC APIs and
published standards only (ISO/IEC 14443, ISO/IEC 7816-4, ICAO Doc 9303).

It has two levels:

1. **Technical diagnostics** (no access data needed): technologies, ATQA/SAK or ATQB, historical
   bytes / higher-layer response, EF.DIR, EF.CardAccess, ICAO application presence.
2. **Identity reading with the holder's own access data**: the card number, date of birth and expiry
   date (or a CAN) unlock the ICAO LDS1 application through **PACE or BAC** (ICAO Doc 9303 Part 11).
   SAMTEX then reads DG1/DG11/DG12 (and DG2 on request) and checks them with **passive
   authentication**.

> **Limits.** Fingerprints (DG3), iris (DG4) and data held in non-public national applications
> (for example spouse name or e-address, which the official ministry reader obtains through its
> proprietary *DzaEidCard* module) are **not** accessed. No authentication bypass, PIN guessing
> or downgrade is ever attempted. See [docs/CNIBE_COMPATIBILITY.md](docs/CNIBE_COMPATIBILITY.md):
> **identity reading has so far been validated only against a simulated chip with synthetic data.
> It has not been verified on a physical CNIBE.**

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
- **Type A and Type B**: ATQB protocol info is decoded and eID signatures are detected in the Type B
  higher-layer response as well as in Type A historical bytes.
- **Public standard probe** (can be disabled): EF.DIR (application directory, no AID guessing),
  EF.CardAccess (supported PACE configurations) and ICAO LDS1 application presence.
- **Secure access (JMRTD 0.8.8)**: PACE (MRZ or CAN; GM/IM/CAM as supported by JMRTD) when the card
  advertises it, otherwise BAC. A PACE authentication failure is never retried with BAC.
- **Identity data**: MRZ (TD1/TD2/TD3 with check digits), DG11 (incl. names in national characters
  such as Arabic), DG12, optional DG2 portrait. Proprietary DG13 is reported but not decoded.
- **Passive authentication**: data-group hashes vs. EF.SOD, CMS signature with the Document Signer
  certificate, and chain to a **user-imported** CSCA certificate. Authenticity is reported as
  *established* only when all three pass; otherwise *not established* or *failed*.
- **Explicit outcome categories** with Arabic explanations: `NFC_NOT_AVAILABLE`, `NFC_DISABLED`,
  `CARD_REMOVED`, `ISO_DEP_UNAVAILABLE`, `APPLICATION_NOT_FOUND`, `PROTOCOL_UNSUPPORTED`,
  `ACCESS_CREDENTIALS_REQUIRED`, `AUTHENTICATION_FAILED`, `ACCESS_DENIED`, `FILE_NOT_FOUND`,
  `DATA_FORMAT_UNSUPPORTED`, `VERIFICATION_UNAVAILABLE`, `READING_SUCCESSFUL`.
- **APDU diagnostics console** limited to an allowlist of documented read-only commands, plus an
  offline response decoder.
- **Report export** (JSON / TXT) through the Storage Access Framework, identifiers redacted by
  default.
- **Optional technical scan history** (explicit opt-in) encrypted with an Android Keystore AES-GCM
  key. Identity data is never stored.
- **Arabic (default, RTL), French and English** interface. Dark navy / electric-blue design.
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
├── icao/                    ICAO Doc 9303 identity reading
│   ├── access/              AccessCredentials (wipeable, never persisted)
│   ├── mrz/                 MrzParser, MrzCheckDigit
│   ├── lds/                 EF.COM, DG1, DG2, DG11, DG12 parsers
│   ├── pa/                  PassiveAuthenticator, TrustAnchorProvider, CertificateParser
│   └── reader/              IcaoDocumentReader (PACE/BAC via JMRTD), TransportCardService
├── security/                InputValidator, PrivacyManager, SecureStorage (Keystore)
├── diagnostics/             SessionLogger, ReportGenerator, ErrorAnalyzer, Json
└── data/                    ScanController (scan + identity sessions), SettingsRepository,
                             Room history, TrustAnchorStore
```

Scan pipeline (`ScanCardUseCase`):

1. **Passive snapshot** – `TagSnapshotReader` copies the values Android cached during anticollision.
2. **ISO-DEP session** – only if the tag exposes `IsoDep`; `IsoDepSessionManager` refuses concurrent
   sessions, applies `IsoDep.setTimeout`, bounds the whole session and always closes the connection.
3. **Public probes** – each `CardProtocolAdapter` sends only commands from a public specification.
   A failure in one adapter does not abort the others; losing the card ends the session but keeps
   the metadata already collected.
4. **Classification** – `CardClassifier` combines snapshot + probe results with provenance.

Identity pipeline (`IcaoDocumentReader`):

1. EF.CardAccess (public) → which PACE configurations the card supports.
2. PACE (CAN if supplied, else MRZ key) or BAC. JMRTD drives the protocol through
   `TransportCardService`, so the same session guard, timeouts and error translation apply.
3. EF.COM, EF.SOD, DG1, DG11, DG12 (DG2 only if requested). DG3/DG4 are never requested.
4. Passive authentication over exactly the bytes read. Raw buffers are then zeroed.
5. Result with explicit outcome, trust levels and a technical report free of personal data.

The decision logic lives in `IcaoDocumentReader.authenticate()`, and the reasoning is documented
in [docs/CNIBE_COMPATIBILITY.md](docs/CNIBE_COMPATIBILITY.md).

## Requirements

- Android 8.0 (API 26) or newer, with NFC hardware.
- Build: JDK 17+, Android SDK Platform 35, Gradle wrapper (8.11.1, downloaded automatically).

## Build instructions

```bash
./gradlew verifyDependencyLocks  # dependency lock validation
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

1. **Gradle wrapper validation** (checksum of `gradle-wrapper.jar`).
2. **Dependency validation**: `verifyDependencyLocks` fails if the runtime classpaths differ from
   `app/gradle.lockfile` (update deliberately with `./gradlew :app:dependencies --write-locks`).
3. **Kotlin compilation**, **unit tests**, **Android Lint**.
4. **Debug APK** build, existence check and SHA-256. Uploads **`SAMTEX-Android-APK`** plus test/lint reports.
5. **Dependency vulnerability scan**: OSV-Scanner (pinned v2.6.0, checksum-verified) over the lockfile, in a separate job.
6. **Failure reporting**: a table of failed stages and per-test annotations in the run summary.

To download the APK: *Actions → Android Build → latest run → Artifacts → SAMTEX-Android-APK*.

The debug APK is signed with the standard Android debug key and is **not a production release**.
A green build does not mean SAMTEX is production-ready or verified on physical cards.
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
| Card access | The free-form diagnostics console only allows SELECT / READ BINARY / READ RECORD / GET DATA / GET RESPONSE (`ApduPolicy`). BAC/PACE is performed only by the identity reader with data the holder enters, at most one authentication attempt per read, never a PIN, never a downgrade from PACE to BAC after an authentication failure. |
| Identity data | Held only in memory for the session. Hidden until the holder taps "Show my data". Never logged, stored, exported or transmitted. Wiped on "End session", on cancel and when the app leaves the foreground. Screenshots and recents thumbnails blocked (`FLAG_SECURE`). |
| Access data | Kept in `CharArray`s and wiped after the attempt. Unused credentials expire after 2 minutes. (JMRTD's key classes take `String`s, which cannot be wiped.) |
| Trust anchors | No built-in or downloaded CSCA. Users import certificates from an official source. Without one, authenticity is never claimed. |
| Identifiers | Tag IDs redacted by default on screen, in logs, history and reports; copying marks the clip as sensitive. |
| Logs | Per-session in-memory logs. Response data from non-public objects is reduced to its length. Identity reads log only INS + status word. JMRTD/SCUBA `java.util.logging` is disabled. Nothing is written to Logcat. |
| Storage | History is opt-in, stores redacted reports encrypted with AES-256-GCM (Android Keystore). Revoking consent deletes history and the key. Backups/device transfer are disabled. |
| Export | Storage Access Framework (user picks destination). Unredacted identifiers require an explicit checkbox **and** a confirmation dialog. |

## Known limitations

- **Not yet verified on a physical CNIBE** (see the compatibility report).
- Data outside the documented ICAO files (spouse name, e-address, civil status, health, etc.) is held
  in non-public national applications and is not read. Fingerprints/iris require EAC and are not read.
- JPEG 2000 portraits cannot be displayed (Android has no decoder). JPEG portraits are supported.
- Active Authentication / Chip Authentication (clone detection) are not implemented.
- Passive authentication needs a CSCA certificate imported by the user.
- The full ATS is not available through Android APIs.
- MIFARE Classic sectors, DESFire applications and other keyed content are not read.
- Technical explanations generated by the engine are in English; the interface is localized
  (EN/AR/FR).
- Behaviour on specific phones and cards must be validated manually on real hardware.

## Testing

```bash
./gradlew testDebugUnitTest
```

The JVM test suite (130+ tests) covers hex encoding/decoding, ATS and historical-byte parsing, APDU
construction and parsing (all cases), status-word interpretation, invalid input, empty and
truncated responses, unexpected response lengths, GET RESPONSE / wrong-Le handling, connection
interruptions, timeouts, concurrent-session rejection, session lifecycle, EF.CardAccess decoding,
the authorization policy, report generation/redaction and classifier accuracy. It also covers
MRZ parsing (ICAO TD1/TD3 specimens), the ICAO Doc 9303 Appendix D BAC key-derivation vector, LDS
decoding, passive authentication (valid, tampered, untrusted, wrong CSCA) and **end-to-end BAC,
PACE-MRZ and PACE-CAN reads** through JMRTD's real client against `SimulatedEmrtdChip`. That test chip
implements the chip side of BAC, PACE ECDH-GM (brainpoolP256r1, AES-128) and secure messaging. All
documents and certificates in the tests are synthetic. No real citizen data is in the repository.

Tests run against `SimulatedTransport`, `VirtualIsoCard` and `SimulatedEmrtdChip` (in `app/src/test/.../testing`).
**These are simulations**: they validate SAMTEX's logic, not its compatibility with any physical
card. Real-device validation checklist:

1. Install the debug APK on an NFC phone, enable NFC.
2. Hold the card on the NFC antenna area; check *Card Detected → Connecting → Reading → Completed*.
3. Remove the card mid-read and check that *Connection Lost* is shown with partial results.
4. Disable NFC while the app is open and check the *NFC Disabled* state and recovery.
5. Export JSON/TXT and verify identifiers are redacted unless explicitly included.
