# Kelvin improvement backlog

Updated 2026-11-02: research baseline Kelvin 0.6 / Android-app 3.6.1; SAFE-01 delivered in 0.7, SAFE-02/03 in 0.8.
This is the canonical pending-work checklist. `TODO.md` in the local working copy
keeps historical delivery notes and the private evidence index; it is not published.

**Planning only:** unchecked entries are not implemented features or compatibility
promises. This backlog does not authorize camera commands, firmware changes or
invasive hardware work. New device-control commands need a controlled,
owner-approved validation plan.

## Reading the plan

- **P0:** misleading measurements, parser safety, or evidence needed to trust results.
- **P1:** reliable acquisition, per-device calibration, saved-data integrity and tests.
- **P2:** broader compatibility and measured image-quality improvements.
- **P3:** experimental features or work blocked on new artifacts/hardware.
- **CODE:** observed in current Kelvin source. **STATIC:** decoded host-app/SDK behavior.
  **LIVE:** recorded camera behavior, within the capture's stated limits.
  **PROPOSAL:** a Kelvin improvement, not something proved by decoding the vendor app.
- **Hardware-gated** means implementation/fixtures can be prepared offline, but the
  feature must not be advertised as supported until tested on the relevant hardware.
  **Artifact-blocked** means the named prerequisite is not currently available.

Suggested execution order:

1. TEST-01 next: CI for the expanded regression suites. SAFE-01/02/03 are delivered.
2. SAFE-04/05, DATA-01 through DATA-03 and TEST-02: trustworthy frame admission and replay.
3. DATA-08, CAL-01 through CAL-04 and IMG-07: safe calibration acquisition and reproducible captures.
4. Validate controls and calibration, then alignment and compatibility. Run lifecycle/privacy tests alongside them.
5. Evaluate image filters and super-resolution offline; firmware work remains separately gated.

## Existing baseline — do not reimplement

Kelvin already has branding, versioned APK distribution, live palettes and view
modes, spot/box/delta tools, emissivity/reflected-temperature controls, sticky
sensor-range saturation masks, native-grid photo temperatures, CSV export,
gallery/compare, MP4 recording, timelapse and opt-in compatibility reports.
Hardware-free Kotlin and Python regression suites exist. These do not establish
real-device compatibility or measurement accuracy.

## Evidence register

The following identifiers refer to **private, Git-ignored research notes**, not
files shipped in the public clone. They identify the source of a finding without
redistributing APKs, SDK binaries, decompiled source, images, camera identifiers
or raw USB logs. The local `TODO.md` contains clickable evidence links.

- **R1:** `research/flir-one-app-3.6.1/README.md`, interoperability overview and caveats.
- **R2:** `research/flir-one-app-3.6.1/native-protocol.md`, §§2–7 packet framing, config, fileIO, options and metadata.
- **R3:** `research/flir-one-app-3.6.1/calibration-findings.md`, §§1–6 profiles, units, normalization and archive selection.
- **R4:** `research/flir-one-app-3.6.1/java-app.md`, §§1–6 controls, USB transport, model handling, firmware and radiometry.
- **R5:** `research/flir-one-app-3.6.1/imaging-pipeline.md`, §§2–8 admission, filtering, fusion and unresolved units.
- **R6:** `research/hardware-validation/README.md`, §§1–6 live observations and limitations.
- **R7:** `research/flir-one-app-3.6.1/firmware-findings.md`, encrypted-package envelopes and upload paths.
- **R8:** `research/firmware-discovery/README.md`, bounded readback/public-image investigation.
- **R9:** `research/firmware-discovery/offline-updater.md`, simulator-versus-real-updater distinction.

Important limits on those findings:

- The mirrored APK's signatures were checked for integrity, but its signer was
  not independently authenticated against a trusted vendor reference.
- The live session saved 57 complete frames and a calibration archive, then
  failed on an unexpected config-channel payload. It was **not** a clean soak pass.
- Automatic FFC was observed. Manual FFC, gain and auto-FFC commands were not tested.
  `FFC_RAD_APPROX` is not `FFC_VALID_RAD`; `ON` alone does not establish blade position.
- The archive has two different calibration entries. The traced SDK prefers the
  exact leading-slash entry before its fallback search. ZIP CRC success does not
  verify the resource's separate internal CRC comment or temperature accuracy.
- Processed 480×640 SDK output is not 480×640 independent sensor measurements.
  The traced Pro/ProLT temporal pipeline and consumer upsampling pipeline differ.
  Exported visual-aided filters are not evidence they are wired into this app.
- The firmware envelopes were decoded, **not their encrypted firmware contents**.
  Host-side image improvements do not depend on decrypting camera firmware.

Current implementation references: [Thermal.kt], [FlirCamera.kt], [Iap2Link.kt],
[Iap2Receiver.kt], [RbProtocolReader.kt], [RbJson.kt], [ConfigEaReceiver.kt],
[CameraController.kt], [Captures.kt], [Timelapse.kt], [Recorder.kt],
[CompatibilityDiagnostics.kt], [iap2host.py] and [rbprotocol.py]. Paths use the
current `be.apexlab.kelvin` namespace.

## 1. Measurement and protocol safety

- [x] **SAFE-01 — Represent invalid temperatures instead of fabricated heat** — P0, completed in 0.7.
  - Evidence: the original `Thermal.celsius` fallback returned 220 °C for an impossible corrected signal. Two failing-before regressions reproduced both that value and its false alarm match.
  - Change delivered: finite validity is independent of saturation; invalid pixels are neutral gray and have no extrema/average/alarm contribution. Live, viewer, compare, timelapse and video handle absent measurements explicitly. Float grids retain NaN, JSON emits null and CSV leaves invalid cells empty without erasing real zero.
  - Verified: 38 JVM tests, 10 Python protocol tests and 7 Android persistence/render/CSV tests passed. Native emulator smoke covered invalid/mixed live views, saved viewer, compare, timelapse and actual MP4 encoding/decoding. No physical-camera accuracy/reconnect claim is added. Existing captures are not retroactively repaired without original raw samples.

- [x] **SAFE-02 — Bound iAP2 packet and parameter parsing in both ports** — P0, completed in 0.8.
  - Before-change evidence: a bounded subprocess reproduced Python's zero-length TLV hang; a corrupt-payload reproducer delivered data, sent an ACK and advanced receive state.
  - Change delivered: independent Android/Python receivers check both checksums and legal packet lengths, bound retained input, scan linearly and validate complete TLV/control/SYN structures before using them. Bad headers resynchronize; bad payloads discard exactly the trusted header's packet without ACK/state change. Valid fragmentation, retransmission and sequence wrap remain supported.
  - Verified: both original reproducers now reject safely; independent malformed-input suites pass. Both ports replayed the retained 2,894-byte iAP2 stream (16 data packets), with no live USB calls. See README for cumulative suite counts and receive limits.

- [x] **SAFE-03 — Validate complete RBProtocol framing** — P0, completed in 0.8.
  - Before-change evidence: actual Android/Python config receive paths accepted bad header CRC, missing NUL and unsupported version; the Python frame path yielded a bad-header-CRC frame.
  - Change delivered: both independent readers validate channel magic/version/header CRC, unsigned bounded lengths and frame-section sums, strict NUL-terminated UTF-8 JSON with depth/token limits, and negotiated config-session routing. Zero-payload config notices are separate from JSON. FileIO validates its payload CRC and zero-length EOF; the frame format has no image-payload CRC.
  - Recovery: frame magic search is allowed only between packets; malformed complete candidates fail closed, clear parser state and never become measurements. Config/fileIO bad magic fails explicitly. No checksum is invented and no malformed capture is repaired. Live fileIO/calibration reads remain DATA-08.
  - Verified: 95 JVM, 82 Python and 12 Android framework tests passed. Both ports replayed 57 retained frames with unchanged decoded sample extrema/section lengths and reproduced the archive exactly. The saved config anomaly is rejected; SAFE-04 remains unresolved. No new device commands, resets, model support or accuracy claim; fresh physical streaming validation is still needed.

- [ ] **SAFE-04 — Resolve the config/frame endpoint-overlap anomaly** — P0, research.
  - Evidence: **LIVE**, R6 §5: config-endpoint reads matched portions of frame-endpoint data before the capture failed. Cause is unresolved; this is not yet a demonstrated Kelvin defect.
  - Change: replay the saved transfers offline first. Plan an isolated, owner-approved capture that distinguishes endpoint identity, buffer ownership, concurrent readers and transport behavior without blind USB resets.
  - Done when: a reproducible cause and regression fixture exist, or the remaining uncertainty is narrowly documented. Require a clean, documented sustained-stream run before treating the capture path as a reliable research baseline. Do not blame firmware, macOS or libusb without evidence.

- [ ] **SAFE-05 — Apply FFC-aware measurement and capture policies** — P1, open.
  - Evidence: **STATIC/LIVE/CODE**, R5 §2.1 and R6 §2; Kelvin shows a calibration indicator but still passes FFC frames to downstream consumers.
  - Change: keep the last usable image visibly marked stale while shutter-FFC frames are excluded from measurements/alarms; defer photos/timelapse with bounded waits. Define video hold/drop behavior with correct timing rather than silently measuring shutter frames.
  - Done when: a synthetic replay of the observed FFC transition produces no shutter-frame extrema, alarm or saved temperature sample; the UI recovers or times out explicitly. Preserve approximate-versus-valid FFC metadata without assuming all approximate frames must be discarded. Depends on DATA-02.

## 2. Acquisition, metadata and camera controls

- [ ] **DATA-01 — Reject duplicate thermal exposures using validated telemetry** — P1, open.
  - Evidence: **STATIC/LIVE**, R5 §2.1 and R6 §3: six adjacent duplicate counter pairs had identical thermal blocks; the relevant telemetry counter is at tail offset `0x2c` in this layout.
  - Change: retain the telemetry tail, deduplicate within one stream generation and report received versus unique frame rates. Missing telemetry must not turn every frame into the same counter.
  - Done when: duplicate, counter-wrap, reconnect/reset and missing-tail fixtures preserve genuine frames and deliver each known thermal exposure once. Keep fresh visible updates if only the thermal exposure repeats. Reset temporal state after a new session; do not collapse different sessions by counter alone.

- [ ] **DATA-02 — Preserve typed frame status and provenance** — P1, open.
  - Evidence: **STATIC/LIVE**, R2 §7, R5 §6 and R6 §§2–3. Geometry may arrive in `sledInformation`, not each frame; several optional fields were absent from the live capture.
  - Change: propagate geometry, endianness, source counter, shutter/FFC state, host monotonic receipt time and available device timestamps. Keep raw telemetry values with unknown units explicitly unknown, not labeled °C or seconds by plausibility.
  - Done when: absent/unknown fields are safe, timestamps are not confused with wall time, and capture/diagnostic consumers can distinguish stale, approximate and valid data without retaining raw payloads in public reports.

- [ ] **DATA-03 — Specify native thermal words and layouts per verified device** — P1, open.
  - Evidence: **STATIC/LIVE**, R5 §4.1 masks words to 14 bits then expands them by four; the observed 80×60 block is 10,332 bytes including 492 bytes of telemetry, and all observed image words fit 14 bits.
  - Change: make row prefixes, payload sizes, endian handling, word normalization and telemetry boundaries explicit. Resolve unsupported upper-bit behavior per verified profile; do not interpret flags as extreme heat or copy a masking rule to unrelated devices.
  - Done when: high-bit, endian, short-row and wrong-layout fixtures cannot silently create wrong temperatures; existing verified words retain their conversion. The current layout can be specified now; new layouts require COMP-02 evidence and feed COMP-03 integration. Test variable telemetry-tail lengths separately from image-row completeness rather than assuming every valid block must always be exactly 10,332 bytes.

- [ ] **DATA-04 — Add a tested config-command writer** — P1, hardware-gated.
  - Evidence: **STATIC/LIVE**, R1 manual-FFC example, R2 §§2–4 and R6 §4. Config framing was exercised by the research archive read, but Kelvin does not expose a general production command path; new control options remain untested.
  - Change: independently encode the 16-byte header, protocol, header CRC, compact JSON and trailing NUL. Length is the UTF-8 payload byte count including NUL; test evidenced escaping, option/value order and exact data-key counts. Serialize writes through the active session.
  - Done when: byte-exact synthetic golden packets and partial-write/error tests pass offline. No malformed packets, debug commands or exploratory options are sent to a physical camera. Depends on SAFE-02/03 and a reliable transport for live use.

- [ ] **DATA-05 — Implement manual FFC with observable completion** — P1, hardware-gated.
  - Evidence: **STATIC**, R1/R2/R4 trace `doFFC`; the observed live cycle was automatic, not validation of that command.
  - Change: add a capability-gated action through DATA-04; record option status if received, but determine completion from observed frame-state progress rather than requiring or trusting an acknowledgement alone. Prevent repeated requests and expose failure/timeout. A write succeeding is not FFC completion.
  - Done when: an owner-approved run on the exact supported camera records request, response/state transition and recovery; fixtures cover rejection and timeout. Requires SAFE-05 and DATA-02. No unbounded watchdog retries.

- [ ] **DATA-06 — Decide recording/automatic-FFC policy explicitly** — P2, hardware-gated.
  - Evidence: **STATIC**, R4 §1.2: the vendor toggles `autoFFC` as a boolean during video; its displayed interval is not transmitted as an interval. No such command was tested here.
  - Change: default to preserving automatic FFC and handling frames safely. Evaluate any optional suppression against drift, interruption and recovery risks; distinguish Kelvin's sound preference from actual shutter control.
  - Done when: the accuracy/interruption tradeoff is measured and documented; normal stop, errors, detach and process death have a verified recovery plan before suppression is offered. If restoration after failure cannot be assured, keep suppression unavailable. Depends on DATA-04/05 and TEST-03/05.

- [ ] **DATA-07 — Bound processing latency and reject stale-session callbacks** — P1, open.
  - Evidence: **CODE/STATIC**, per-frame main-thread posts and JPEG processing share the acquisition path; R5 §2.1 describes bounded vendor delivery, not a mandatory queue design for Kelvin.
  - Change: bound work/backpressure, define per-consumer drop policy and attach a session generation to callbacks. Keep slow decode/rendering from silently accumulating old live data or allowing a detached camera to update a new session.
  - Done when: slow-consumer and rapid detach/reattach tests show bounded memory/latency, observable drops and no stale readings; recorder/timelapse timestamp behavior remains correct.

- [ ] **DATA-08 — Implement allowlisted, read-only calibration archive retrieval** — P1, hardware-gated integration.
  - Evidence: **STATIC/LIVE**, R2 §5 and R6 §4: `CameraFiles.zip` was read with fileIO/header/payload CRC and explicit zero-length EOF. Kelvin enables fileIO but does not retrieve calibration in its production driver.
  - Change: implement bounded open/read/close, cancellation, CRC and error handling for the known archive only. Cache privately with device/firmware identity and archive hash; never write camera files or probe guessed paths.
  - Done when: synthetic EOF/disconnect/error/CRC tests pass and an approved read-only device run produces a verified archive without changing device state. Depends on SAFE-03; calibration-file selection is CAL-01.

- [ ] **DATA-09 — Measure settling after start, reconnect and FFC** — P2, research.
  - Evidence: **STATIC**, R5 §§2.1/7 has five post-gain frame skips and six processed-output startup skips. Those counts do not establish Kelvin's settling needs.
  - Change: collect stable-target time series and derive a justified settling indicator/rule; reset any temporal history on relevant transitions. Extend to gain only when supported and tested.
  - Done when: documented drift curves justify the rule and fixtures verify it clears/recovers. Do not copy vendor skip counts without the vendor filter chain. Depends on DATA-01/02 and CAL-07.

## 3. Per-device calibration and temperature trust

- [ ] **CAL-01 — Select calibration ZIP members without aliasing them** — P1, open.
  - Evidence: **LIVE/STATIC**, R6 §4 and R3 §6: `CameraFiles/system/calib.rsc` and `/CameraFiles/system/calib.rsc` differ, including Planck parameters; the traced SDK first prefers exact leading-slash paths.
  - Change: preserve member-name identity for selection; prefer the evidenced exact path and define a bounded, unambiguous fallback or fail explicitly. Process selected bytes safely without extracting absolute paths or traversal entries to disk.
  - Done when: reordered entries, both aliases, missing paths, ambiguous fallbacks, traversal names and oversized archives cannot select the wrong profile or escape storage. Include the corresponding map selection. Depends on DATA-08 for live retrieval, not offline fixtures.

- [ ] **CAL-02 — Port a bounded, typed calibration-resource decoder** — P1, open.
  - Evidence: **STATIC/offline**, R3 §5 has a runnable decoder and a 665-row retained resource; that is not proof of vendor internal-CRC validation.
  - Change: parse the observed format with length/type limits, preserve numeric types and named profiles, and document the resource checksum status. Keep defaults and missing/wrong-type behavior explicit rather than silently guessing parameters.
  - Done when: synthetic resources cover malformed lengths/types, missing/duplicate fields and truncation; selected decoded fields match retained offline results without publishing the private resource. Do not call an unverified footer checksum verified.

- [ ] **CAL-03 — Replace single-unit constants with selected per-device profiles** — P1, open.
  - Evidence: **CODE/STATIC/LIVE**, `Thermal.kt` uses one unit's constants; R3 §§1/6 identify distinct profiles and the correct live archive member.
  - Change: associate coefficients, source path/hash, model, firmware and supported lens/gain with the active device. Keep unverified devices report-only; make any existing reference-profile fallback explicit as unvalidated, never silently transferable.
  - Done when: two synthetic devices cannot share each other's calibration; stale/invalid caches fail visibly; the verified current profile reproduces current reference fixtures. Depends on CAL-01/02 and COMP-02.

- [ ] **CAL-04 — Verify units and normalization stage by stage** — P1, research.
  - Evidence: **STATIC**, R3 §§2/4: range fields use Kelvin, object/window fields use Celsius; the SDK truncates J0 and narrows some values to float, unlike Kelvin's current full-double expression.
  - Change: specify unit conversions and sample-count scaling at each stage; compare full-double and SDK-style normalization using controlled vectors. Do not change precision simply to imitate a stored metadata assignment.
  - Done when: fixtures catch double Celsius/Kelvin conversion, wrong profile and factor-of-four mistakes; any numerical difference is quantified. No bit-for-bit SDK equivalence claim without the complete processing path. Depends on CAL-02/03.

- [ ] **CAL-05 — Research full window/background radiometric correction** — P2, research-gated.
  - Evidence: **STATIC/LIVE**, R3 §3/R6 §4: the selected file contains `tauWin`, `TWin`, `TBg` and emissivity; the SDK consumes them, but the full equation/conventions are not yet reproduced or validated.
  - Change: trace the full host calculation and determine whether/how these terms apply to this optical path, including user overrides. Compare against controlled references before exposing settings or changing temperatures.
  - Done when: the equation, units, defaults and calibration conventions are documented and measured against baselines. Never divide Celsius readings by transmission or assume file values establish accuracy. Depends on CAL-03/04/07; unresolved physics blocks shipping, not safe investigation.

- [ ] **CAL-06 — Gate gain/range controls on actual hardware support** — P2, hardware-gated.
  - Evidence: **STATIC**, R3 §1 and R4 §3: explicit 120 °C/400 °C profiles and model-dependent controls exist. A profile or a −40 °C file limit does not prove an extended range on the consumer unit.
  - Change: require verified gain capabilities, matching complete calibration/range metadata and observed transitions; pair any supported gain change with FFC and measured settling.
  - Done when: unsupported cameras never receive gain commands or advertise extra range; supported hardware has validated transition, saturation and temperature tests. Depends on COMP-02, CAL-03/04 and DATA-05/09.

- [ ] **CAL-07 — Establish reference-scene accuracy and confidence reporting** — P1, research.
  - Evidence: **LIVE**, R6 §§2/6: the recorded state remained approximate after FFC; neither file decoding nor golden math fixtures certify temperatures.
  - Change: define controlled references, warm-up, emissivity, distances and uncertainty; measure repeatability/drift across FFC and sessions. Distinguish invalid, stale, approximate and validated-for-this-profile states in UI and saved provenance. Characterize telemetry units before using them for compensation.
  - Done when: a reproducible report states errors and limits, not just SDK agreement; any proposed drift correction improves held-out references before shipping. Requires DATA-02 and SAFE-01/05. Untested units retain unknown units; no blanket certified-accuracy claim.

## 4. Alignment, image quality and saved-data integrity

- [ ] **IMG-01 — Validate calibration-driven visible/thermal geometry** — P2, research.
  - Evidence: **STATIC**, R5 §5 traces host-side `C1 + C0 / distance`, rotation and magnification; Kelvin already has a manual fixed crop/parallax model. Renderer axes, signs and coefficient units are not yet validated.
  - Change: compare decoded coefficients and fitted geometry on known-distance scenes; retain manual override and capture-time geometry metadata. This is a host transform, not a new camera command.
  - Done when: edge/spot alignment is measured in thermal pixels across validated distances and improves on the current model without regressions. Do not simply expand the slider to the SDK's limits. Depends on CAL-02/03.

- [ ] **IMG-02 — Validate real JPEG dimensions and cross-sensor timing** — P1, open/research.
  - Evidence: **LIVE/STATIC**, R6 §3 recorded 1440×1080 JPEGs, not the SDK constructor's 640×480 metadata. Kelvin already decodes actual JPEG sizes; this is not a missing JPEG decoder.
  - Change: validate dimensions, crop, rotation and alignment throughout live, capture and replay; bound JPEG decode sizes and characterize thermal/visible timing before visible-assisted registration.
  - Done when: synthetic alternate sizes and observed geometry preserve aspect/spot coordinates, invalid images fail safely and timestamp assumptions are documented. Depends on DATA-02 and IMG-01 for calibrated alignment.

- [ ] **IMG-03 — Keep native measurements separate from enhanced display layers** — P1, open.
  - Evidence: **CODE/STATIC**, Kelvin currently measures the native portrait grid; R5 §§1/7 shows vendor output is filtered/resampled. Neither raw-only nor processed data automatically guarantees accuracy.
  - Change: keep native counts/analysis authoritative by default. Any processed measurement mode must be explicit and carry processing/calibration provenance through viewer, compare, share and CSV; visible edges must never become thermal measurements.
  - Done when: changing display enhancement leaves native spot/box/delta/alarms/export numbers unchanged; any intentionally processed values are unmistakably labeled. Depends on IMG-07 for persistent provenance before enhanced capture modes ship.

- [ ] **IMG-04 — Evaluate bad-pixel, striping and lens-map correction separately** — P2, research.
  - Evidence: **STATIC**, R5 §3.2 wires dead-pixel replacement, row/column filtering and optional lens correction. Their presence does not prove Kelvin needs the same algorithms or constants.
  - Change: measure each defect first on flat fields and small real targets, understand map semantics, then trial independent conservative corrections as optional processed layers. Preserve original samples and corrected-pixel masks.
  - Done when: replay metrics show benefit without erasing small hot targets, real gradients or adding measurement bias; provenance identifies applied corrections. Depends on IMG-03/07 and SR-01 for the raw replay corpus; map use also needs CAL-01/02. Completing super-resolution reconstruction is not a prerequisite for testing simpler filters.

- [ ] **IMG-05 — Compare edge-preserving spatial upsampling with ordinary interpolation** — P2, research.
  - Evidence: **STATIC**, R5 §3 selects iterative trilateral upsampling on the traced consumer path; the 480×640 result is processed output, not new native samples. Exact Lightning-to-vendor-type mapping is unproven.
  - Change: benchmark an independently implemented, appropriately licensed display-only method against current interpolation, including edges, noise and false contours. Do not transplant SDK code or claim extra measured pixels.
  - Done when: measured/visual comparisons justify a mode, native measurements are unchanged and exports label their actual data grid. Depends on IMG-03 and SR-01 for the replay corpus.

- [ ] **IMG-06 — Evaluate motion-aware temporal denoising** — P2, research.
  - Evidence: **STATIC**, R5 §3 contains temporal filters; this does not establish suitable parameters, noise independence or performance for Kelvin.
  - Change: combine unique admitted exposures with motion/latency checks; reset at FFC, reconnect and gain/profile changes. Compare noise reduction against lag, ghosting and temperature bias.
  - Done when: held-out static and moving scenes show quantified benefit and bounded latency; enhanced display never silently changes native measurements. Depends on DATA-01/02/07, SAFE-05, IMG-03 and SR-01.

- [ ] **IMG-07 — Version captures for raw samples, dimensions, validity and provenance** — P1, open.
  - Evidence: **CODE**, photos already save `temps.f32`, `sat.u8`, settings and width/height, but readers use global dimensions and source raw counts are not retained. Do not replace or recreate these features blindly.
  - Change: extend the format with explicit version dispatch, native dimensions/orientation, optional original counts, FFC state, calibration hash/profile, timestamps and processing history. SAFE-01 already preserves basic validity in the existing fixed-grid NaN/null encoding; raw samples, generalized geometry and full provenance remain pending. Make gallery, viewer, compare, measurements, recolor, share and CSV use each capture's actual grid.
  - Done when: legacy 60×80 captures remain readable, synthetic larger grids export correctly, raw-backed captures can be recomputed and unknown/truncated formats fail clearly. Private calibration identity/serial must not leak through normal sharing. Depends on SAFE-01 and DATA-02; the dimension-aware format enables subsequent COMP-03 device integration.

- [ ] **IMG-08 — Benchmark processing latency, memory and battery before adding filters** — P2, proposal.
  - Evidence: **CODE/PROPOSAL**, current JPEG, edge and thermal paths allocate/process per frame; no measured bottleneck or achievable frame-rate increase is established by the research.
  - Change: measure receipt-to-display/capture latency, dropped unique frames, allocations, memory and sustained power on the tested phone; skip unused work and reuse bounded buffers where justified.
  - Done when: baseline and candidate results show no hidden latency/memory growth and fit the observed camera cadence. Do not infer 60 fps or a sensor-rate increase from faster rendering. Depends on DATA-01/07; use results to gate IMG-04/05/06 and SR-04.

## 5. Wider hardware and platform compatibility

- [ ] **COMP-01 — Add a distinct native Android-camera transport** — P2, hardware-gated.
  - Evidence: **STATIC**, R4 §3.4 describes Android USB config/fileIO/frame transport; the validated Lightning model uses iAP2 plus native interfaces. Shared VID/PID or protocol names do not make those transports identical.
  - Change: implement only the evidenced native path and discover interfaces/endpoints from a validated descriptor profile, not by deleting the current handshake or opening unknown devices experimentally.
  - Done when: real target hardware passes discovery, permission, stream, disconnect and reconnect tests with its proper calibration. Existing Lightning support remains intact. Depends on SAFE-03, COMP-02/03 and relevant hardware reports.

- [ ] **COMP-02 — Centralize evidenced model, transport and capability decisions** — P1, open.
  - Evidence: **CODE/STATIC**, Kelvin uses a model-prefix gate; R4 §3 uses serial-prefix classification in a different transport/model context. Vendor product/UI feature sets are not proof of physical restrictions or Lightning model equivalence.
  - Change: define the minimum verified device description needed for geometry, calibration and safe commands; keep unknown/conflicting identities report-only. Distinguish on-device capabilities from host features such as multiple measurement tools.
  - Done when: synthetic unknown, conflicting and known-device cases choose safe profiles; serial metadata is private by default and the current known model is not regressed. Do not copy vendor single-spot/product-tier restrictions without a technical reason.

- [ ] **COMP-03 — Generalize dimensions only for observed thermal layouts** — P2, hardware-gated.
  - Evidence: **CODE/STATIC**, current constants cover 80×60; decoded model tables include other sensor classes, but that does not validate an attached Pro/ProLT or another Lightning model.
  - Change: carry validated geometry/endian/layout through decoder, analysis, tools, overlays, recorder and persistence; refuse mismatches instead of guessing by payload length alone.
  - Done when: synthetic alternative grids work end to end and each promoted physical model has matching frame/calibration evidence. Depends on COMP-02, DATA-02/03 and IMG-07 for persisted support.

- [ ] **COMP-04 — Maintain a reproducible phone/camera/adapter compatibility matrix** — P1, open.
  - Evidence: **CODE/LIVE**, diagnostics already exist; only a specific camera/phone pairing has been verified for the app, and the macOS research capture is not an additional Android compatibility pass.
  - Change: collect opt-in reviewed reports with connector/model, adapter/power topology, Android/phone, permission and stage reached; distinguish report-only, experimentally streamed and validated radiometric support.
  - Done when: failures have reproducible steps and supported rows have explicit test evidence; no camera is promoted merely for matching USB ID or image dimensions. Other Lightning/iPhone-targeted camera models belong here as hardware variants.

- [ ] **COMP-05 — Assess an actual iOS app separately from iOS-camera support on Android** — P3, research-gated.
  - Evidence: **CODE/PROPOSAL**, Kelvin is an Android app. An iPhone-targeted camera working through an Android adapter does not establish iPhone app compatibility.
  - Change: investigate permitted iOS accessory APIs, protocol access, hardware/adapters, any entitlement/MFi constraints, distribution and reusable pure logic before committing to a port.
  - Done when: a concrete feasibility report and owner go/no-go identify supported hardware/API access; no promise of a cross-platform toggle or reuse of Android USB-host code.

## 6. Regression coverage, privacy, lifecycle and delivery

- [ ] **TEST-01 — Run regression tests and debug builds in GitHub CI** — P0, open.
  - Evidence: **CODE/PROPOSAL**, Kotlin/Python suites exist but no checked-in CI workflow was found during this review.
  - Change: run the documented JVM and Python suites plus a debug build on pushes/PRs using compatible Java/SDK versions. Keep signing keys, camera data and vendor artifacts out of CI; CI must not require a camera or private calibration.
  - Done when: a failing regression blocks checks, a clean clone passes and release signing stays separate. No automatic upload of private test material or diagnostics.

- [ ] **TEST-02 — Create a sanitized protocol/frame replay corpus** — P1, open.
  - Evidence: **STATIC/LIVE**, R2/R6 give concrete packet shapes, CRCs, FFC transitions, duplicate counters and archive-alias cases; current tests do not cover the complete Android acquisition pipeline.
  - Change: derive synthetic/minimized fixtures for config/fileIO/frame parsing, FFC admission, telemetry wrap/reset, layout mismatches and archive selection. Preserve non-public captures privately for research replay.
  - Done when: both ports have relevant independent regression cases with known expected behavior; public fixtures contain no real images/serials, raw vendor resources or SDK code. Include bounded malformed-input and negative-control cases. Depends on the relevant SAFE/DATA/CAL implementations, but fixture design can start now.

- [ ] **TEST-03 — Automate Android session and lifecycle transitions** — P1, open.
  - Evidence: **CODE**, existing JVM suites do not exercise USB permissions, controller/main-thread races, detach or process/activity lifecycle.
  - Change: add suitable emulator/instrumented or isolated Android-runtime tests for permission denial/regrant, rotation, background/resume, rapid attach/detach, stale callbacks and reconnect while capture is active. Use physical tests for transport claims emulators cannot prove.
  - Done when: transitions leave one owned session, no post-detach measurement/alarm and no stuck capture state; the current supported device passes a documented physical smoke matrix. Depends on DATA-07.

- [ ] **TEST-04 — Verify report, capture, share and backup privacy boundaries** — P1, open.
  - Evidence: **CODE**, reports already redact camera serials by default, while capture metadata privately stores serials and visible images. That is not itself proof that sharing leaks them.
  - Change: test opt-in/opt-out serial masking across nested metadata/event text, FileProvider grants, exported PNG/MP4/CSV and compatibility reports. Replace raw rejected-config JSON logging with bounded structural errors that cannot expose camera identifiers. Audit effective backup/data-extraction policy and define user-facing behavior; do not infer it from source defaults alone.
  - Done when: ordinary shares expose only intended data, opt-in is explicit, report creation never opens unknown USB devices and backup/restore behavior is documented and verified. Existing diagnostic redaction is extended/tested, not reimplemented.

- [ ] **TEST-05 — Make media interruption and deletion behavior explicit and tested** — P1, open.
  - Evidence: **CODE**, background power saving exempts timelapse but not video recording; media/undo/export paths lack automated lifecycle coverage.
  - Change: choose stop-and-save versus supported background recording; handle detach, encoder failure, low storage, cancellation and process restart. Test delete/undo across private files, shared-gallery copies and separately exported CSVs; recover or clearly report partial/orphan output.
  - Done when: no REC timer silently runs against a paused stream, interrupted files are playable or clearly failed, and deletion/export semantics match the UI. Depends on TEST-03 and SAFE-05 for FFC behavior; do not promise cleanup code can run after process death.

- [ ] **APP-01 — Make long timelapse sessions durable and honest about background operation** — P2, proposal.
  - Evidence: **CODE**, samples currently live in an in-memory list until saved; there is no foreground service guaranteeing unattended background logging.
  - Change: choose explicit background/notification behavior within Android restrictions, persist samples incrementally with a bounded working set, and record gaps rather than inventing evenly spaced readings. Preserve first-frame/30-second sampling semantics unless deliberately changed.
  - Done when: rotation, background restrictions, detach and process death have documented recovery/gap behavior; long runs do not grow RAM without bound and restart does not silently lose an entire session. Depends on SAFE-01/05, IMG-07 and TEST-03/05.

## 7. Multi-frame super-resolution — experimental, discuss before integration

The objective remains genuinely recovered thermal detail from multiple shifted
native frames, not a larger colored bitmap or visible-camera outlines. A larger
output grid alone is not an HD or metrological claim. Ordinary averaging may
reduce noise without increasing spatial resolution. Useful new spatial detail
requires appropriate subpixel information and demonstrable reconstruction quality.

- [ ] **SR-01 — Capture private raw research bursts with complete metadata** — P2, research.
  - Evidence: **STATIC/PROPOSAL**, R5 shows model-selected host temporal processing; the existing deferred request calls for 16–32 frames. That burst length is an experimental choice, not a decoded sensor requirement.
  - Change: save original native counts before emissivity/reflected correction, monotonic times, source counters, FFC/shutter state, settings and calibration identity. Optional synchronized gyro data may aid registration. Reject duplicates and bursts crossing recalibration/profile changes.
  - Done when: static textured, flat, edge, low-contrast, moving and distance-change scenes are available for private offline replay; originals and provenance are retained. Depends on DATA-01/02, SAFE-05 and IMG-07.

- [ ] **SR-02 — Build a reproducible offline registration/reconstruction harness** — P3, research.
  - Evidence: **STATIC/PROPOSAL**, R5 §3 wires temporal SR for some vendor types and spatial upsampling for others; exported visual-aided classes are not proof that path is used.
  - Change: evaluate subpixel thermal-only registration, robust accumulation on 2× and exploratory 3× grids, then iterative back-projection. Compare visible-assisted registration only after accounting for parallax, geometry and timing. Use independent implementations with appropriate license/provenance review.
  - Done when: repeated runs have reproducible outputs/metrics, insufficient shifts and scene motion are reported, and raw/native analysis remains available. Depends on SR-01, IMG-01/02/03 and CAL-07 for radiometric interpretation.

- [ ] **SR-03 — Evaluate held-out quality and decide go/no-go with Gilles** — P3, research.
  - Evidence: **PROPOSAL**, decoded pipeline names and 480×640 output do not establish useful extra detail on this unit.
  - Change: compare one frame, ordinary interpolation, edge-preserving spatial upsampling and aligned denoising against reconstruction. Measure edge/detail recovery, noise, temperature bias, ghosting and failure cases on held-out frames, including motion and distance changes.
  - Done when: a written result supports go/no-go, useful output resolution and capture interaction, reviewed with Gilles before integration. A negative result is a valid outcome. No generative temperature data, fabricated accuracy claim or automatic HD branding. Depends on SR-02.

- [ ] **SR-04 — Integrate burst capture only after a positive review** — P3, approval-gated.
  - Evidence: **PROPOSAL**, the original request explicitly defers implementation until discussion and evidence.
  - Change: add quality checks, off-UI-thread processing, bounded memory, cancellation/progress and clear failure feedback for insufficient offsets, moving scenes or FFC. Keep ordinary capture available and validate on supported devices.
  - Done when: measured UX/latency and quality meet the approved criteria without stale measurements, UI stalls or silently degraded results. Depends on SR-03, DATA-07, IMG-08 and TEST-05; no approval means no integration.

- [ ] **SR-05 — Persist and expose reconstructed-grid provenance without breaking old captures** — P3, approval-gated.
  - Evidence: **CODE/PROPOSAL**, capture/viewer/export paths currently assume native geometry; the deferred request requires explicit reconstructed dimensions and provenance.
  - Change: retain originals alongside reconstructed temperatures/grid dimensions, validity/saturation and registration/algorithm metadata. Update gallery, viewer, compare, recolor, measurement mapping, share and CSV to honor per-capture dimensions and distinguish native from processed values.
  - Done when: old 60×80 captures stay usable, enhanced outputs cannot masquerade as native sensor measurements and every exported grid records what it represents. Depends on IMG-03/07 and SR-03/04.

## 8. Firmware visibility and blocked firmware research

- [ ] **FW-01 — Present firmware component identity without conflating versions** — P2, open.
  - Evidence: **LIVE/CODE**, R6 §1 reports separate Rosebud operational/factory/updater and Lepton versions; `CameraInfo` already exposes firmware and Lepton strings. USB `bcdDevice` is another descriptor, not the running firmware version.
  - Change: extend the existing information/report view with available component names, hardware/API identity and provenance, while keeping identifiers private by default. Unknown versions remain unknown, not update recommendations.
  - Done when: the observed differing component versions are labeled correctly and no release-note version or package basename is advertised as compatible without model/component evidence. No vendor-cloud polling is required.

- [ ] **FW-02 — Resume offline firmware analysis only with a genuine new prerequisite** — P3, artifact-blocked.
  - Evidence: **STATIC**, R7–R9: package envelopes are parsed but encrypted; no matching plaintext updater/service image or usable decryption key was obtained. The inspected concrete SDK updater callback is a simulator, not a real device decryptor.
  - Change: first obtain a legitimately accessible, provenance-recorded camera-side updater/plaintext image or matching key through an authorized source. Only then analyze the actual implementation and validate any decryption/integrity result offline.
  - Done when: genuine artifact identity, compatibility and integrity evidence exist, or the item remains explicitly blocked with its missing prerequisite. Do not repeat failed endpoint/string searches without a new lead, treat a public key as a decryptor, use simulator success as proof, or probe guessed camera paths.

- [ ] **FW-03 — Keep any firmware updater behind a separate safety/design gate** — P3, artifact/hardware-gated.
  - Evidence: **STATIC**, R4 §4/R7/R8 trace opaque upload, updater mode and reboot, not a tested recovery workflow. Current package-to-hardware authenticity/compatibility is not established.
  - Change: before implementation require a trusted matching package, component/model/hardware checks, power prerequisites, documented interruption/recovery behavior and explicit owner approval. Review secure artifact distribution separately from normal app updates.
  - Done when: those prerequisites and a controlled recovery-capable test plan are approved. Until then ship no automatic firmware download, reboot, debug option, camera-file write, cross-model flash or invasive extraction path. Firmware decryption is not a prerequisite for the other backlog sections.

[Thermal.kt]: android/app/src/main/java/be/apexlab/kelvin/Thermal.kt
[FlirCamera.kt]: android/app/src/main/java/be/apexlab/kelvin/FlirCamera.kt
[Iap2Link.kt]: android/app/src/main/java/be/apexlab/kelvin/Iap2Link.kt
[CameraController.kt]: android/app/src/main/java/be/apexlab/kelvin/CameraController.kt
[Captures.kt]: android/app/src/main/java/be/apexlab/kelvin/Captures.kt
[Timelapse.kt]: android/app/src/main/java/be/apexlab/kelvin/Timelapse.kt
[Recorder.kt]: android/app/src/main/java/be/apexlab/kelvin/Recorder.kt
[CompatibilityDiagnostics.kt]: android/app/src/main/java/be/apexlab/kelvin/CompatibilityDiagnostics.kt
[iap2host.py]: iap2host.py
[Iap2Receiver.kt]: android/app/src/main/java/be/apexlab/kelvin/Iap2Receiver.kt
[RbProtocolReader.kt]: android/app/src/main/java/be/apexlab/kelvin/RbProtocolReader.kt
[RbJson.kt]: android/app/src/main/java/be/apexlab/kelvin/RbJson.kt
[ConfigEaReceiver.kt]: android/app/src/main/java/be/apexlab/kelvin/ConfigEaReceiver.kt
[rbprotocol.py]: rbprotocol.py
