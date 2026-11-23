-- V270: reclaim trace columns on tp_exec_task (crash-recovery reconciliation)
-- Why: the 15s offline-sweep job and the manual batch recover action reclaim the same
-- "running task on an offline executor" rows; whoever wins first drains the other's
-- candidate set, so a manual recover could report "nothing to reclaim" with no way to
-- explain where reclaimed tasks went. Recording which path reclaimed a task lets the
-- recover view state, in plain words, how many tasks were already reclaimed by the
-- automatic sweep and are still queued.
-- last_reclaim_source: 1 = automatic offline-sweep job, 2 = manual batch recovery.
-- NULL = never reclaimed (task never dangled, or legacy rows before this migration).
ALTER TABLE tp_exec_task ADD COLUMN IF NOT EXISTS last_reclaim_source SMALLINT;
ALTER TABLE tp_exec_task ADD COLUMN IF NOT EXISTS last_reclaimed_at TIMESTAMPTZ;

COMMENT ON COLUMN tp_exec_task.last_reclaim_source IS
    'Which reclaim path last moved this task back to the pending queue: 1=automatic offline sweep, 2=manual batch recovery; NULL=never reclaimed';
COMMENT ON COLUMN tp_exec_task.last_reclaimed_at IS
    'Timestamp of the last reclaim move (matching last_reclaim_source); NULL=never reclaimed';
