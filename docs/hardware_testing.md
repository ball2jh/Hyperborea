# Hardware Testing Checklist

Items that need manual verification on the bike.

## Open Questions

### Does the MCU reset incline/resistance on IDLE transition?
When `V1Session.stop()` writes `WORKOUT_MODE=IDLE`, does the MCU automatically return incline to 0% and resistance to the default (~7)?

**How to test:** Start a workout, set incline to 10%+ and resistance high, then stop the workout. Observe whether the bike physically returns to flat/default or stays put.

**If it doesn't reset:** Add explicit `GRADE=0` and `RESISTANCE=<default_raw>` writes in `V1Session.stop()` before the IDLE transition. See the TODO in `V1Session.kt`.
