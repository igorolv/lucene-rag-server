-- id: common.statement-import-errors
-- purpose: ошибки загрузки банковской выписки по строкам
-- when: «выписка загружена, а платежей нет»
-- params: :statement_import_id — ID сеанса загрузки
-- scope: без ограничения
-- result: строки выписки, которые не удалось разобрать, с текстом ошибки
-- pii: нет
-- tables: STATEMENT_IMPORT, STATEMENT_IMPORT_ERROR
-- verified: billing@test, 01.11.2026
SELECT e.line_num, e.error_text
FROM statement_import_error e
WHERE e.statement_import_id = :statement_import_id
ORDER BY e.line_num
