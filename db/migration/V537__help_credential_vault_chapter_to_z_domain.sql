
UPDATE tp_help_doc
   SET domain_code = 'Z',
       sort_no = (SELECT COALESCE(MAX(sort_no), 0) + 1 FROM tp_help_doc t WHERE t.domain_code = 'Z')
 WHERE domain_code = 'X' AND title LIKE '凭据保险箱%';

