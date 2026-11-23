-- Migration: seed the device-wall thumbnail tuning keys.
-- appauto.wall.thumb-width-px = the pixel width the newest live monitor frame of
-- a device's running task is downscaled to before it feeds the wall grid cell
-- (height keeps the frame aspect ratio; smaller width = lighter cells).
-- appauto.wall.thumb-ttl-seconds = how long one assembled thumbnail stays cached
-- in Redis, i.e. the reuse window that keeps a one-frame-per-second wall poll
-- from re-decoding the same frame (short by design: the cell must go stale
-- quickly when the feed stops).
-- Both are hot keys read per assembly through AppautoConfigReader (no hardcode
-- in the implementation face). Idempotent: ON CONFLICT DO NOTHING.
-- Flyway disabled: apply with manual psql.
-- Usage: psql < db/migration/V477__appauto_wall_thumbnail_keys.sql

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('appauto.wall.thumb-width-px', '240', 'APP自动化', 'int', 0,
     '设备墙格位缩略图的目标宽度像素（默认 240；实时画面按此宽度等比缩放后送格位显示，调整即时生效）'),
    ('appauto.wall.thumb-ttl-seconds', '3', 'APP自动化', 'int', 0,
     '缩略图装配结果的缓存秒数（默认 3；一秒一帧轮询在窗口期内复用同一帧装配，窗口过后设备断流时格位随即转为无画面提示）')
ON CONFLICT (config_key) DO NOTHING;
