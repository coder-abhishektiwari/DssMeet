-- 016: drop the call-booking (schedule) feature entirely.
-- No table references `schedule` (verified: no schedule_id columns / FKs in),
-- and no code reads it after the feature removal — safe to drop.

DROP TABLE IF EXISTS schedule;
