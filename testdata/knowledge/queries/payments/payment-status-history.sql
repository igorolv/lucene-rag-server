-- id: payments.payment-status-history
-- purpose: история статусов платежа: кто и когда менял статус, с комментарием оператора
-- when: «платёж не привязался», «платёж отменён, а клиент оплатил», разбор очереди
-- params: :payment_id — ID платежа (экран «Платежи»)
-- scope: PAYMENT.CONTRACT_ID = :contract_id
-- result: строки истории по времени, последняя строка — текущий статус
-- pii: в комментарии оператора возможны ФИО плательщика
-- tables: PAYMENT, PAYMENT_STATUS_HIST, USERS
-- verified: billing@test, 01.11.2026
SELECT h.psh_time                                   AS changed_at,
       CASE h.payment_status_enum WHEN 0 THEN 'получен' WHEN 1 THEN 'не распознан'
            WHEN 2 THEN 'привязан к счёту' WHEN 3 THEN 'отменён' END AS status,
       u.user_name                                  AS changed_by,
       h.psh_note                                   AS note
FROM payment_status_hist h
LEFT JOIN users u ON u.user_id = h.user_id
WHERE h.payment_id = :payment_id
ORDER BY h.psh_time
