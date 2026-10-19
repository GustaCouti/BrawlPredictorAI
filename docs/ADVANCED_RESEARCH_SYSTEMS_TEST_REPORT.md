# Advanced research systems verification report

Environment: Kotlin/JVM compiler available; Java 21. Android SDK and Gradle are not installed in this environment.

## Tests run

- `AdvancedEngineSmokeMain.kt`: PASS. Forecast candidates/probabilities, reachability, intercept, association, A*, HMM, evidence fusion, Monte Carlo, RTS smoothing, Kalman/IMM checks.
- `AdvancedResearchSystemsSmokeMain.kt`: PASS. Exact Hungarian assignment for square and rectangular matrices, conformal residual storage/quantiles, multi-hypothesis track retention, GRU output-head learning on a fixed synthetic target, randomized simulator frame validity, synthetic roll-out statistics.
- `CoreSimulationMain.kt`: PASS, 10,000 synthetic scenarios / 280,000 frames; recall 0.9719131, mean normalized position error 0.0094240, identity-switch rate 0.0093128; tested best horizon 90 ms. Synthetic only.
- `CoreStressMain.kt`: did not finish within the available execution window during this packaging run; no fresh result claimed here.
- `ExtremeMovementMain.kt`: not rerun during this packaging run; prior repository report states its chaotic-motion robustness thresholds were not met.
- Android APK build / Robolectric / device overlay: NOT RUN; Gradle and Android SDK unavailable.

## Scope and limitations

The research add-on is functional lightweight code, not a claim that every research-grade paper implementation has been reproduced. In particular, full pretrained Transformer/Trajectron++/Social-LSTM/diffusion networks, complete CPHD/MHT implementations, production OC-SORT/ByteTrack integration, and nonlinear UKF integration are not present. Synthetic self-play utilities do not imply human-like strategic behaviour. Probabilities need real labeled calibration data and domain validation.

## Follow-up integration fixes
- Fixed UKF update/predict ordering in the live engine.
- Added Tiny GRU to the live forecast candidates and online real-outcome training path.
- Raised the overlay's visible generic motion prediction cap from 3 to 12 (the detector still caps internal tracks at 48 and this does not guarantee enemy classification).
- Core compile and smoke suites rerun successfully after the fixes.
