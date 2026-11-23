-- Migration V564: help-chapter and column-comment amendment for the anchor-type
-- registry correction (WE batch, , R61).
-- The ledger whitelist and the executor resolver now accept the two most stable
-- anchor kinds role and label (Playwright get_by_role / get_by_label); the
-- user-facing chapter still enumerated only the older kinds and the column
-- comment still carried the stale value set, so both are amended in this batch.
-- Idempotent: each statement is guarded by IS DISTINCT FROM on the new value,
-- replay after the amendment writes zero rows.

-- 1) Chapter step text: name the two new kinds in the registration walk-through.
UPDATE tp_help_doc
   SET body_md = replace(body_md,
         '类型支持 CSS 选择器、XPath、文本、占位符、alt、testid 等，列表中自上而下即尝试顺序',
         '类型支持 testid、role（可访问名）、label（表单标签）、占位符、文本、alt、CSS 选择器、XPath、rel，列表中自上而下即尝试顺序')
 WHERE domain_code = 'F'
   AND title = '页面元素库：Web 定位锚集中建档'
   AND body_md LIKE '%类型支持 CSS 选择器、XPath、文本、占位符、alt、testid 等%';

-- 2) Append the anchor-kind guidance block (trial order = stability order,
--    with how-to-write examples and the fallback behaviour).
UPDATE tp_help_doc
   SET body_md = body_md || E'\n\n## 定位锚类型与推荐顺序（新增能力）\n'
       || E'登记或修正定位锚时，锚类型按稳定性从高到低依次是：testid 与 role（可访问名）同级最高，其次 label（表单标签）与占位符、文本，再往下是 CSS 选择器、XPath，rel（相对关系锚）用于前面都取不到时的兜底。列表里自上而下即系统的实际尝试顺序，越靠前越稳。\n'
       || E'- role 锚的写法=角色名加冒号加可访问名，例如「button:提交」「textbox:用户名」；页面控件正确标注了角色与可访问名时，这种锚改版后最不容易失效。\n'
       || E'- label 锚的写法=表单标签文字本身，例如「手机号」；表单输入框优先用它。\n'
       || E'- 执行时按列表顺序逐锚尝试，命中即止；前一枚失效会自动回退到后一枚，并在锚统计里留下失效记录供修正参考。\n'
  WHERE domain_code = 'F'
    AND title = '页面元素库：Web 定位锚集中建档'
    AND body_md NOT LIKE '%定位锚类型与推荐顺序（新增能力）%';

-- 3) Column comment: value set and trial-priority order aligned with the
--    whitelist (testid/role first tier, label/placeholder/text second,
--    css/xpath/rel tail).
COMMENT ON COLUMN tp_web_element.anchors IS 'Ordered anchor array: [{type: testid|role|label|placeholder|text|css|xpath|rel, value, strength 0..1, note}]; array order is the trial order, most stable kinds first';
