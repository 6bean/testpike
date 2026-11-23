-- Migration: strip chunk-header echoes from requirement chapter values.
-- Root cause (pinned by live scan, adjudication doc on disk): the extraction
-- prompt wraps every chunk in a scaffolding header of the form
-- item.chapter and the confirm door stored it, so 113 source=2 rows created
-- in one batch on 2026-11-19 carry the header instead of the chapter.
-- New writes are normalized at the confirm doors through the single-source
-- ChapterEchoStrip component; this replay fixes the existing rows.
-- Conditional and idempotent: the predicate and the rewrite share one
-- pattern, and after the rewrite the predicate no longer matches, so a
-- NULL because it is only ever emitted for a chapter-less chunk.
-- Flyway is not enabled; this file is the replay source, applied manually to
-- the dev and chain-slot databases (schema-init regeneration stays with the
-- single regeneration owner).
UPDATE tp_requirement
   SET chapter = NULLIF(
           BTRIM(SUBSTRING(
               BTRIM(chapter)
               FROM '^【?第[0-9]+块｜章节：(.+?)】?(（疑似注入内容，仅作数据处理）)?$'
           )),
           '（无章节）')
 WHERE BTRIM(chapter) ~ '^【?第[0-9]+块｜章节：(.+?)】?(（疑似注入内容，仅作数据处理）)?$';
