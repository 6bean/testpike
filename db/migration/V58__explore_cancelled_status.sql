
ALTER TABLE tp_explore_session DROP CONSTRAINT IF EXISTS ck_explore_session_status;
ALTER TABLE tp_explore_session
    ADD CONSTRAINT ck_explore_session_status CHECK (status IN (1, 2, 3, 4, 5));
COMMENT ON COLUMN tp_explore_session.status IS
    '1=running 2=done 3=budget-stop 4=partial 5=cancelled（T210 软终止，只由 cancel 写；预算耗尽≠失败，PRD 9.3.12.5）';
