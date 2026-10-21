# Release checklist

- [ ] `./gradlew test` green; `python3 cloud/proxy/test_taskbaba_proxy.py` green
- [ ] `gitleaks detect --source . --no-banner` reports no leaks
- [ ] Version bumped in `app/build.gradle.kts` (`packageVersion`) and `CHANGELOG.md`
- [ ] `scripts/build-app.sh` succeeds; app launches on a clean user account with an empty data folder
- [ ] Manual pass: create/complete/postpone tasks, recurring task next day, test notification + its buttons, quiet hours, pause, menu bar actions, floating Baba, launch at login on/off, light + dark mode, reduced motion
- [ ] Permission denial path: deny notifications → in-app reminders still work
- [ ] Optional features: Colab endpoint check + test inference, Drive verification, one integration
- [ ] Screenshots regenerated (`ScreenshotRenderer`) if UI changed
- [ ] Signing/notarization configured **before** publishing binaries (currently ad-hoc only — do not attach unsigned builds as "official")
- [ ] Tag `vX.Y.Z` and write release notes from the changelog
