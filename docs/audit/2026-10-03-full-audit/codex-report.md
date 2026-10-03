# Security Review: src

## Scope

The scan reviewed the canonical include paths and exclusions listed below.

- Scan mode: repository
- Target kind: git_revision
- Target ID: target_sha256_a2d18c0358afd7dceb4ecf75718a74de5541f7b343b0e92b7a29be69ae39cd42
- Revision: 5ee8e2098c9e2d0f70f9d26ff2585b9a893a8e9e
- Inventory strategy: repository
- Included paths: .
- Excluded paths: none
- Runtime or test status: not recorded

### Scan Summary

| Field | Value |
| --- | --- |
| Reportable findings | 5 |
| Severity mix | high: 1, medium: 2, low: 2 |
| Confidence mix | high: 4, medium: 1 |
| Coverage | complete |
| Validation mode | not recorded |

Canonical artifacts: `scan-manifest.json`, `findings.json`, and `coverage.json`. This report is a deterministic projection of those files.

## Threat Model

No explicit canonical threat-model summary was recorded.

## Findings

| Finding | Severity | Confidence | Detailed write-up |
| --- | --- | --- | --- |
| [TLS certificate validation bypassed for whitelisted intranet hosts](#finding-1) | high | high | inline below |
| [Platform login sends account and password over cleartext HTTP](#finding-2) | medium | high | inline below |
| [Credentials and API keys stored in plaintext and packaged into the APK](#finding-3) | medium | high | inline below |
| [Crash logs exempt from rotation and cleanup accumulate unbounded](#finding-4) | low | high | inline below |
| [Exported debug bubble receiver guarded only by FLAG_DEBUGGABLE](#finding-5) | low | medium | inline below |

### Confidence Scale

| Label | Meaning |
| --- | --- |
| high | Direct evidence supports the finding with no material unresolved blocker. |
| medium | Evidence supports a plausible issue, but material runtime or reachability proof remains. |
| low | Evidence is incomplete and the item is retained only for explicit follow-up. |

<a id="finding-1"></a>

### [1] TLS certificate validation bypassed for whitelisted intranet hosts

| Field | Value |
| --- | --- |
| Severity | high |
| Confidence | high |
| Confidence rationale | Bypass conditions are unconditional in code and the full source-to-sink chain was traced line-by-line. |
| Category | tls-validation |
| CWE | CWE-295 |
| Affected lines | main/java/com/xiaoduo/deskpet/net/HttpClients.kt:25, main/java/com/xiaoduo/deskpet/net/HttpClients.kt:47-49, main/java/com/xiaoduo/deskpet/net/HttpClients.kt:78-88 |

#### Summary

The custom X509ExtendedTrustManager accepts any certificate for the three whitelisted intranet IPs and the hostname verifier is bypassed for them, so an on-path LAN attacker can impersonate the platform 443 endpoint, intercept bearer tokens and uploaded files, and inject SSE/TTS content.

#### Root Cause

The convenience feature 'trust self-signed certs for intranet hosts' was implemented as 'skip all validation for whitelisted IPs' instead of pinning the expected certificate, collapsing both certificate-chain validation and hostname verification to no-ops for the primary server channel.

#### Validation

Bypass conditions are unconditional in code and the full source-to-sink chain was traced line-by-line. Validation details were not recorded separately.

Validation method: Static source-to-sink trace of the full OkHttp TLS configuration in HttpClients.create() and every consumer of HttpClients.shared.

#### Dataflow

The canonical finding records the affected path at main/java/com/xiaoduo/deskpet/net/HttpClients.kt:25, main/java/com/xiaoduo/deskpet/net/HttpClients.kt:47-49, main/java/com/xiaoduo/deskpet/net/HttpClients.kt:78-88, but no expanded source-to-sink narrative was recorded.

#### Reachability

Reachability was not recorded beyond the canonical finding summary and affected locations.

#### Severity

**High** — The scan assigned high severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

#### Remediation

Replace the early-return in WhitelistTrustManager.checkServerTrusted with pinning: bundle the expected self-signed certificate (or its SPKI SHA-256) for each whitelisted IP and compare the leaf certificate before accepting; keep the default TrustManager for all other hosts and remove the hostnameVerifier bypass (verify the expected IP/CN mapping instead). Add a unit test asserting a mismatched certificate for 10.0.18.166 fails the handshake.

Tests:
- Handshake against a MockWebServer presenting an unrelated self-signed certificate while the client targets a whitelisted host must fail.

<a id="finding-2"></a>

### [2] Platform login sends account and password over cleartext HTTP

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Default constant, cleartext allowlist, and the exact POST body are directly verified in source; behavior is deterministic. |
| Category | cleartext-transport |
| CWE | CWE-319 |
| Affected lines | main/res/xml/network_security_config.xml:7-11, main/java/com/xiaoduo/deskpet/net/NetConfig.kt:90, main/java/com/xiaoduo/deskpet/agui/AguiClient.kt:283-296 |

#### Summary

The platform account and password are POSTed as JSON over cleartext HTTP by default (http://10.0.18.166:8080), and the ~25-minute token expiry re-sends the password on every re-login, letting a passive on-path attacker harvest the admin credentials.

#### Root Cause

Only the agent-stream endpoint was migrated behind the 443 self-signed reverse proxy; the login endpoint kept the original intranet HTTP :8080 contract, so the most sensitive request in the app is the only one that never upgrades to TLS.

#### Validation

Default constant, cleartext allowlist, and the exact POST body are directly verified in source; behavior is deterministic. Validation details were not recorded separately.

Validation method: Static trace of the login flow from NetConfig defaults through NetEndpoints.loginUrl to the OkHttp POST, cross-checked against network_security_config.xml.

#### Dataflow

The canonical finding records the affected path at main/res/xml/network_security_config.xml:7-11, main/java/com/xiaoduo/deskpet/net/NetConfig.kt:90, main/java/com/xiaoduo/deskpet/agui/AguiClient.kt:283-296, but no expanded source-to-sink narrative was recorded.

#### Reachability

Reachability was not recorded beyond the canonical finding summary and affected locations.

#### Severity

**Medium** — The scan assigned medium severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

#### Remediation

Serve login over the existing 443 reverse proxy (e.g. POST https://{host}/ai-server/auth/login) and switch NetEndpoints.loginUrl to derive the https:// URL from the platform host exactly as agentStreamUrl already does; if :8080 must remain for legacy devices, gate cleartext login behind an explicit settings opt-in and stop re-sending the password by extending token refresh.

Tests:
- Contract test asserting loginUrl() starts with https:// whenever platformBase host is set.

<a id="finding-3"></a>

### [3] Credentials and API keys stored in plaintext and packaged into the APK

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Storage calls and BuildConfig wiring are direct, grep-verified facts; exposure follows from standard Android platform properties. |
| Category | insecure-storage |
| CWE | CWE-312, CWE-798 |
| Affected lines | main/java/com/xiaoduo/deskpet/net/NetConfig.kt:103-105, main/java/com/xiaoduo/deskpet/net/NetConfig.kt:236-263, main/java/com/xiaoduo/deskpet/net/TokenStore.kt:43-45 |

#### Summary

Account password, platform bearer token, and cloud API keys (OpenAI/Aliyun/Volcengine) are persisted in plaintext SharedPreferences, and BuildConfig-injected API keys are packaged into the APK where static extraction recovers them.

#### Root Cause

Secrets were treated as ordinary settings: the same SharedPreferences file that stores endpoint URLs also stores the password, bearer token, and vendor API keys, and a build-time convenience injects fallback keys as compile-time constants.

#### Validation

Storage calls and BuildConfig wiring are direct, grep-verified facts; exposure follows from standard Android platform properties. Validation details were not recorded separately.

Validation method: Repo-wide storage-path grep plus Android API surface check for Keystore/EncryptedSharedPreferences.

#### Dataflow

The canonical finding records the affected path at main/java/com/xiaoduo/deskpet/net/NetConfig.kt:103-105, main/java/com/xiaoduo/deskpet/net/NetConfig.kt:236-263, main/java/com/xiaoduo/deskpet/net/TokenStore.kt:43-45, but no expanded source-to-sink narrative was recorded.

#### Reachability

Reachability was not recorded beyond the canonical finding summary and affected locations.

#### Severity

**Medium** — The scan assigned medium severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

#### Remediation

Move secrets to Android Keystore-backed storage (e.g. EncryptedSharedPreferences or Jetpack Security) for password, token, and API keys; remove the BuildConfig-baked key fallback and require per-device key entry (or a backend-issued short-lived token); if a bundled key is unavoidable, scope it to a least-privilege proxy key.

Tests:
- Round-trip test: values written through NetConfig saveAll read back through the Keystore-backed store; grep-style contract test asserting no secret keys are written to plain SharedPreferences.

<a id="finding-4"></a>

### [4] Crash logs exempt from rotation and cleanup accumulate unbounded

| Field | Value |
| --- | --- |
| Severity | low |
| Confidence | high |
| Confidence rationale | The filename-prefix mismatch is mechanically verifiable across CrashReporter/LogRotator/PetLogger; no other cleanup path exists. |
| Category | log-retention |
| CWE | CWE-459 |
| Affected lines | main/java/com/xiaoduo/deskpet/util/LogRotator.kt:21-28, main/java/com/xiaoduo/deskpet/util/CrashReporter.kt:40-47, main/java/com/xiaoduo/deskpet/util/PetLogger.kt:29-33 |

#### Summary

Crash logs are written as crash-YYYY-MM-DD.log but rotation, cleanup, and export filters only match xiaoduo-\*.log, so stack traces accumulate indefinitely in the app logs directory (which is also the FileProvider root).

#### Root Cause

CrashReporter was added later with its own crash- prefix, but the retention policy stayed anchored to the xiaoduo-\*.log name pattern, so the two filename conventions never reunited under one cleanup rule.

#### Validation

The filename-prefix mismatch is mechanically verifiable across CrashReporter/LogRotator/PetLogger; no other cleanup path exists. Validation details were not recorded separately.

Validation method: Filename-convention mismatch traced across CrashReporter, LogRotator, PetLogger, and file_paths.xml.

#### Dataflow

The canonical finding records the affected path at main/java/com/xiaoduo/deskpet/util/LogRotator.kt:21-28, main/java/com/xiaoduo/deskpet/util/CrashReporter.kt:40-47, main/java/com/xiaoduo/deskpet/util/PetLogger.kt:29-33, but no expanded source-to-sink narrative was recorded.

#### Reachability

Reachability was not recorded beyond the canonical finding summary and affected locations.

#### Severity

**Low** — The scan assigned low severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

#### Remediation

Extend LogRotator (or add a sibling filter) to recognize crash-\<date\>.log and include those files in expiredFiles cleanup with their own retention window; alternatively have CrashReporter reuse the xiaoduo-\<date\>.log naming with a marker line, so a single filter governs rotation, cleanup, and export.

Tests:
- Unit test: expiredFiles(\[crash-2020-01-01.log\], today) returns the stale crash file; integration test: simulated crash logs are deleted after KEEP_DAYS.

<a id="finding-5"></a>

### [5] Exported debug bubble receiver guarded only by FLAG_DEBUGGABLE

| Field | Value |
| --- | --- |
| Severity | low |
| Confidence | medium |
| Confidence rationale | Code behavior is certain, but impact requires a debuggable build in the field plus a malicious co-installed app. |
| Category | component-exposure |
| CWE | CWE-926 |
| Affected lines | main/java/com/xiaoduo/deskpet/ui/overlay/DebugBubbleReceiver.kt:24-26, main/AndroidManifest.xml:57-65 |

#### Summary

DebugBubbleReceiver is exported with no permission; in debug builds any co-installed app can drive the SYSTEM_ALERT_WINDOW bubble with arbitrary text, and release builds are protected only by the runtime FLAG_DEBUGGABLE guard.

#### Root Cause

ColorOS cannot deliver shell broadcasts to non-exported receivers, so the debug affordance was exported in the main manifest for all builds and the release safety was delegated entirely to a runtime FLAG_DEBUGGABLE check instead of manifest-level separation.

#### Validation

Code behavior is certain, but impact requires a debuggable build in the field plus a malicious co-installed app. Validation details were not recorded separately.

Validation method: Manifest export posture plus receiver guard and overlay sink trace.

#### Dataflow

The canonical finding records the affected path at main/java/com/xiaoduo/deskpet/ui/overlay/DebugBubbleReceiver.kt:24-26, main/AndroidManifest.xml:57-65, but no expanded source-to-sink narrative was recorded.

#### Reachability

Reachability was not recorded beyond the canonical finding summary and affected locations.

#### Severity

**Low** — The scan assigned low severity; no separate canonical severity rationale was recorded.

Additional runtime or deployment evidence could raise or lower this severity.

#### Remediation

Set android:exported="false" on the receiver for release builds (manifest placeholders or a debug-only manifest overlay), or add a signature-level custom permission to the intent filter; keep the adb (shell) path working by registering the receiver in debug-only source instead of the main manifest.

Tests:
- Robolectric test: broadcast from a non-debug application context is dropped; instrumented test: release build ignores DEBUG_BUBBLE actions.

## Reviewed Surfaces

| Surface | Risk Area | Outcome | Notes |
| --- | --- | --- | --- |
| TLS trust and 443 transport (HttpClients, AG-UI stream, upload) | Transport security | Reported | WhitelistTrustManager accepts any certificate for whitelisted IPs; hostname verification skipped (candidate-892860315d5c1c76). |
| Platform login transport | Transport security | Reported | Password POSTed over cleartext HTTP by default; cleartext allowed for 10.0.18.166 (candidate-b994bbefc24e7bae). |
| Credential and key storage | Secrets at rest | Reported | Password/token/API keys in plaintext SharedPreferences; BuildConfig-injected keys packaged into APK (candidate-2f70cd165037d766). |
| Logging and crash retention | Sensitive data lifecycle | Reported | crash-\*.log bypasses xiaoduo-\*.log-only rotation/cleanup/export filters; unbounded retention (candidate-c6336a896b504f67). |
| Exported components | Attack surface | Reported | DebugBubbleReceiver exported without permission; FLAG_DEBUGGABLE guard protects release only (candidate-d94011af437600be). |
| Device skills and shelf MCP bridge | Physical device control | Rejected | Device commands route through conservative whitelisted intent matchers; the latent runId JSON-template weakness (candidate-a324a24d57ae03c6) was suppressed: runId is client-generated and tools are whitelisted. |
| AG-UI client, SSE consumer, file upload | Server communication | No issue found | Token never logged; SSE parsed defensively; upload bounded to 20MB; error envelopes truncated. |
| Cloud ASR/TTS/LLM clients (OpenAI/Aliyun/Volcengine) | Server communication | No issue found | Keys sent only in headers, never logged; timeouts bounded; malformed responses handled without crashes. |
| Chat codecs, session store, view model and screens | Data integrity / UI | No issue found | Corrupt JSON degrades safely; busy/epoch guards prevent cross-session writes; no link/URI handling in message rendering. |
| Markdown and bubble rendering | Content injection | No issue found | Server text rendered as styled plain text only (bold/italic/code spans); no Linkify, WebView, URI launch, or clickable-link surface found repo-wide. |
| Voice pipeline (VAD, KWS, ASR/TTS session, wake-ack cache) | Parser robustness | No issue found | WAV parsing bounds-checked; buffers bounded (DROP_OLDEST/deques); cache file names sanitized+hashed; no transcript logging. |
| General utilities (prefs, logger, app launcher, gestures) | Hardening | No issue found | No secrets in logs (repo-wide grep); launcher targets user-configured packages; rotation logic clean for its own file class. |
| Bundled binary assets (ONNX models, WAV, PNG/WebP) | Data assets | Not applicable | 17 binary assets verified as standard-format data via header/chunk inspection; no executable content; consumed by fixed asset paths. |
| Test source set (84 files + fixtures) | Quality gates | No issue found | Unit tests lock down reviewed logic; only dummy credentials in test fixtures; parity WAVs are well-formed test data. |

## Open Questions And Follow Up

- Confirm whether production deployments segment 10.0.18.x from user Wi-Fi; the TLS whitelist bypass and cleartext login severity both hinge on realistic LAN attacker access.
  - Follow-up prompt: Review network segmentation for 10.0.18.166/97/24 and decide whether the WhitelistTrustManager can be replaced with SPKI pinning.
- Determine which release artifacts embed non-empty BuildConfig cloud keys (secrets/cloud.properties) so key rotation can be scoped.
  - Follow-up prompt: Inventory distributed APKs for embedded CLOUD_KEY/ALIYUN/VOLC values and rotate any that shipped.
