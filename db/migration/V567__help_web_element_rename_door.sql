-- Migration V567: help-chapter amendment for the rename door (WE batch,
-- i-surface, R61 behavior change = chapter updated in the same batch).
-- Mis-named ledger rows used to be unfixable by hand while script references
-- address elements by name; the ledger dialog now carries an inline rename
-- input backed by PUT /web-elements/{id}/name. Idempotent: the append is
-- guarded by NOT LIKE on the new block title, replay writes zero rows.

UPDATE tp_help_doc
   SET body_md = body_md || E'\n\n## 修改元素名称（新增能力）\n'
       || E'元素被脚本按「名称」引用，名字起错（例如 AI 自动命名不准确）时，打开该元素的编辑锚弹窗，标题行的名称框即为改名入口：改成新名后点「修改元素名称」保存。下次执行起，脚本引用自动按新名定位，无需重新登记元素。\n'
       || E'- 同一页面下名称不可重复：与本页已有元素重名时会保存失败，并点名是哪条名称冲突，换个名字或去编辑那条已有元素即可。\n'
       || E'- 改名与保存锚共用同一版本保护：弹窗打开后若这条记录被他人改过，保存会被拒绝并提示刷新重试，不会覆盖别人的修改。\n'
       || E'- 不同页面之间允许同名元素，按「页面+名称」共同定位归属。\n'
  WHERE domain_code = 'F'
    AND title = '页面元素库：Web 定位锚集中建档'
    AND body_md NOT LIKE '%修改元素名称（新增能力）%';
