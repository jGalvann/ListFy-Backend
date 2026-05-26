-- Token de teste (role: user)
INSERT INTO token (valor, role, data_expiracao, ativo_token)
VALUES (
           'test-token-user-001',
           'USER',
           '2026-12-31',
           true
       );

-- Token admin (descomente quando precisar)
-- INSERT INTO token (valor, role, data_expiracao, ativo_token)
-- VALUES (
--     gen_random_uuid()::text,
--     'ADMIN',
--     '2026-12-31',
--     true
-- );