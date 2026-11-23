-- Migration V285: approval-linked automatic launch (D8, default off) + scenario link column.
-- A production press approval may name the perf scenario it was filed for; when the
-- hot-updatable switch perf.approval.auto-launch.enabled is on, approving such a ticket
-- immediately fires the scenario through the exact same single launch channel as the
-- manual and CI doors (safety gate + duration ceiling + GA-2 env lock — zero bypass).
-- The approval itself always commits first: if the launch is rejected by any gate the
-- rejection reason is recorded on the ticket in plain words, never a silent failure and
-- never a rolled-back approval.
--   scenario_id       the perf scenario this ticket covers (NULL = plain approval, no
--                     auto-launch even when the switch is on)
--   auto_launch_note  outcome line written at approve-time when auto-launch fires
--                     ("launched run #N" or "not launched: <gate reason>")
-- Flyway is not enabled: apply manually to the dev database.
-- Usage: docker cp db/migration/V285__perf_approval_auto_launch.sql testpike-postgres:/tmp/ && docker exec -e PGPASSWORD testpike-postgres psql -U testpike -d testpike -f /tmp/V285__perf_approval_auto_launch.sql

ALTER TABLE tp_perf_approval ADD COLUMN IF NOT EXISTS scenario_id BIGINT REFERENCES tp_perf_scenario (id);
ALTER TABLE tp_perf_approval ADD COLUMN IF NOT EXISTS auto_launch_note VARCHAR(500);

COMMENT ON COLUMN tp_perf_approval.scenario_id IS 'Approval-linked perf scenario (tp_perf_scenario.id; NULL = no link, approving never auto-launches)';
COMMENT ON COLUMN tp_perf_approval.auto_launch_note IS 'Auto-launch outcome line recorded when the approval takes effect (run id on success, gate reason on refusal; NULL = switch off or no linked scenario)';

-- Auto-launch switch (K8 discipline, hot-updatable): default off keeps today's behaviour
-- byte-for-byte (approval only unlocks the production window, launching stays a human
-- action). Only an explicit "true" row enables firing on approve.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('perf.approval.auto-launch.enabled', 'false', '系统', 'string', 0,
     '压测审批通过后自动发起关联场景（默认关：批准仅解锁压生产窗口，起压仍由人工发起；开启后批准即按审批单关联的场景与环境走同一发起闸链，被闸拒绝时审批照常生效并在单据上写明未起压原因；热更即生效）')
ON CONFLICT (config_key) DO NOTHING;
