-- Migration V563: R61 help-chapter amendment for the two census doors on the
-- Page Element Library page (WE batch S6 piece B, ). The page gained two
-- user-visible actions (bulk filing of migration cards and bulk reference
-- replacement), so the chapter describing this page must state what each door
-- does, what it changes and what stays untouched, in the same batch.
-- Idempotent: the append is guarded by IS DISTINCT FROM on the marker text,
-- replay after the amendment writes zero rows.

UPDATE tp_help_doc
   SET body_md = body_md || E'\n\n## 存量普查批量迁移（新增能力）\n'
       || E'项目里已经写好的 Web 用例大多把页面定位表达式直接写在步骤里，页面一改就要逐条翻脚本。筛选带右侧提供两枚普查入口，配合上面的「测绘此页」把存量一次性收敛到页面元素库：\n'
       || E'- 「批量迁移入库」：扫描本项目全部用例脚本中内联书写的定位表达式，按页面归类后为每一条尚未建档的元素生成一张待确认卡片。点开后弹出「批量迁移入库结果」，逐项写明普查发现的引用条目、本轮新建草案卡、已有待确认草案卡、页面元素库已覆盖。\n'
       || E'- 「批量引用替换」：扫描同一批用例脚本，把已经能在页面元素库找到对应条目的引用改成引用态；库里还没有的元素会整条跳过，绝不改写成语句不明的脚本。结果弹窗写明扫描脚本数、因页面元素库尚无而跳过的引用。\n'
       || E'- 两枚入口都只登记待确认的草案卡，不会改动任何用例或脚本；逐条确认后才会生效，确认之后仍可在卡片历史里回滚。重复点击不会产生重复卡片：第二次普查会把这批引用整批计为「已有待确认草案卡」。\n'
       || E'- 顶栏未选择项目时两枚入口不可用，鼠标停留会说明需要先在顶栏选择项目；普查发起失败时提示「普查迁移发起失败，可稍后重试」，可以稍后重试，已生成的卡片不受影响。\n'
  WHERE domain_code = 'F'
    AND title = '页面元素库：Web 定位锚集中建档'
    AND body_md NOT LIKE '%存量普查批量迁移（新增能力）%';
