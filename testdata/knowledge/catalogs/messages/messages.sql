-- Каталог сообщений биллинга: knowledge/catalogs/messages/messages-billing.tsv
-- Только чтение. TSV с заголовком: kind, call, text, file, line, method
SELECT kind, call, text, file, line, method
FROM billing_messages
ORDER BY file, line
