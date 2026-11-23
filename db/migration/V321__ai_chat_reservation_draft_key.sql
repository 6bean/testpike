-- Factory switch for device-reservation draft cards in the online AI dialog.
--
-- Why this key exists:
--   The dialog can propose booking a device with the
--   appauto.reservation.create write tool. A reservation occupies one device
--   for a time window, so nothing runs until the human confirms the card
--   through the shared draft door, and the confirmed create runs the SAME
--   AppautoReservationService.create chain as the reservation calendar's
--   dialog — the window validation, the device-existence check, the conflict
--   refusal (naming the current holder and the expected release time) and the
--   approval-switch routing all re-decide at landing time, so this switch
--   never weakens one of them and the dialog is never a bypass. The owning
--   project is taken from the chat session, so a model-forged device id
--   cannot book another project's lane. The card states the occupied device
--   and window before the human confirms.
--   Operators who want a quieter dialog for one domain can close exactly
--   this key without silencing case, tag, prompt-template, skill, review,
--   plan, batch, report, api-test, api-inventory or perf drafts.
--
-- Behaviour when off: an appauto.reservation.create tool call still refuses
--   to execute, the answer itself is unaffected, and the turn journals one
--   state frame that names this setting path and key so the user knows where
--   to reopen it.
--
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.chat.reservation-draft.enabled', 'true', '系统', 'bool', 0,
     'AI 设备预约草案开关（默认开启；对话中 AI 提出预约设备时附上草案卡，确认后走预约日历同一创建链——时间窗校验、设备存在性与冲突判定照常在创建时复核，冲突会被拒绝并点名占用者与预计释放时间；草案卡会注明本次预约占用的设备与时间窗；项目归属取当前会话项目；是否需审批按预约审批开关照常判定；已有预约的归还与取消仍在预约日历人工操作；关闭时回答照常给出，不再附草案卡）')
ON CONFLICT (config_key) DO NOTHING;
