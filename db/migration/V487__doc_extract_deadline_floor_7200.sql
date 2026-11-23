-- Raise the document-extraction task deadline default from 900s to 7200s
-- (user order 2026-11-10): long-document extraction legitimately runs for
-- hours, and the 15-minute default cut healthy jobs mid-flight. The consume
-- lease keeps its 15min cadence because the renewal watchdog keeps the lock
-- alive while a run is in flight, and the stall sweeper reclaims on idle
-- (no-progress) rows, not on total elapsed time — neither needs a change.
-- Only the untouched default (still 900) is rewritten; manual overrides stay.
UPDATE tp_sys_config
   SET config_value = '7200',
       description = '文档提取任务级时长预算秒数（默认 7200=2 小时；提取全程超出该预算即中止并恢复为可重新提取状态，再次发起提取即可；单次模型调用超时按剩余时间自动缩短；非法值按默认 7200 回退；热更即生效）'
 WHERE config_key = 'ai.doc.extract-deadline-seconds'
   AND config_value = '900';
