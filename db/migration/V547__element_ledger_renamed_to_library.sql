
UPDATE tp_help_doc
   SET title = replace(title, '页面元素账本', '页面元素库'),
       body_md = replace(replace(body_md, '页面元素账本', '页面元素库'), '账本', '元素库')
 WHERE (title LIKE '%页面元素账本%' OR body_md LIKE '%页面元素账本%'
        OR (body_md LIKE '%账本%' AND title LIKE '%页面元素%'));
