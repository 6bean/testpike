-- Migration V652: help-chapter wording sync for the requirement-door fix.
-- The viewer's collapse header used to be labelled with a second name for
-- the same concept; on screen it now carries the single context word family
-- shared with the neighbour-row button. The chapter must quote the exact
-- on-screen literal (help chapter and behavior change in one batch).
UPDATE tp_help_doc
   SET body_md = REPLACE(body_md, '相邻原文', '上下文'),
       updated_at = now()
 WHERE body_md LIKE '%相邻原文%';
