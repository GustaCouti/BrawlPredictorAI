# Implementation status — expanded research build

## Newly added in this revision
- Lightweight sigma-point/UKF-style nonlinear motion estimator and live forecast candidate.
- Tiny causal self-attention forecaster and live candidate (random initialization; not pretrained).
- Ensemble uncertainty sampling and a diffusion-inspired trajectory denoising sampler, both connected to multimodal forecasting.
- PHD-style expected target count and CPHD-inspired cardinality distribution utilities.
- Adaptive tracking-budget calculation for variable target counts and frame-time pressure.
- Smoke checks for all new components.

## Available systems
The codebase also includes Hungarian global assignment, gating, bounded MHT-style hypotheses, Kalman/IMM-style motion estimates, HMM maneuver probabilities, evidence fusion, reachability/interception, tactical scoring, particle forecasts, conformal residual intervals, RTS-style smoothing, A*, flow fields, synthetic domain randomization, and synthetic self-play utilities. The Android screen tracker supports up to 48 internal tracks and 48 motion blobs per frame with up to 12 displayed predictions. These are motion regions, not guaranteed enemy identities; effects, bushes, projectiles and interface animation can be mistaken for targets.

## Important limitations
This is an experimental implementation of lightweight approximations, not a full reproduction of all 49 research systems or a classified defense system. JPDA/PHD/CPHD helpers are not equivalent to complete production-grade multi-target filters; the sampler is diffusion-inspired rather than a trained diffusion model; the attention model is randomly initialized and requires labeled real-match training; the visual signature is not a trained Brawl Stars re-identification model. Trajectron++, Social-LSTM, and production OC-SORT/ByteTrack are not bundled as complete trained implementations. The screen-tracking pipeline remains visual-motion based and does not reliably classify every Brawler by identity.

## Validation
Core Kotlin sources compile with `kotlinc`, and the `AdvancedResearchSystemsSmokeMain` synthetic smoke test passes. This environment has no Gradle wrapper/Android SDK validation, so the Android app has not been compiled into an APK or device-tested in this run. Synthetic tests validate basic behavior only; they do not prove prediction accuracy on real matches. Build the APK through Android Studio or the included CI workflow before installing.

## Integration fixes in the 0.8 working revision
- Fixed the live UKF forecast order: the previous implementation predicted forward and then assimilated the current observation, which pulled the forecast back toward the present. It now assimilates the latest observation first, then predicts forward.
- Connected the existing trainable Tiny GRU forecaster to the live multimodal candidate set and online outcome feedback. The model is randomly initialized; usefulness grows only when real observed outcomes are supplied.
- Removed the overlay's hard-coded three-prediction display cap; it can now draw up to the tracker's configured 12 motion predictions.
- Recompiled the core and reran the two Kotlin smoke suites after these changes.

## Additional hardening in the 0.8 build
- Forecast history now filters non-finite coordinates and duplicate timestamps before deriving velocity.
- Candidate normalization rejects invalid/non-positive model weights and prevents NaN positions from leaking into output.
- Added explicit `forgetTrack`/`retainTracks` lifecycle methods to clear per-target recurrent, particle, UKF, profile, and pending-outcome state when tracks expire.
- Added a variable-target regression smoke test covering eight simultaneous target histories, output finiteness, and stale-track pruning.
- Validation rerun: `AdvancedEngineSmokeMain`, `AdvancedResearchSystemsSmokeMain`, and `VariableTargetForecastMain` all pass.
