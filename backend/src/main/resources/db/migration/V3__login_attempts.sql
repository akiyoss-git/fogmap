-- Счётчик неудачных попыток входа.
--
-- В базе, а не в памяти процесса: при нескольких экземплярах сервера памятный счётчик даёт
-- злоумышленнику отдельный лимит на каждый из них, то есть перебор просто раскладывается по
-- инстансам.
create table login_attempts (
    key               varchar(128) primary key,
    attempts          integer     not null,
    window_started_at timestamptz not null
);

create index idx_login_attempts_window on login_attempts (window_started_at);
