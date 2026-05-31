alter table mailboxes add column if not exists archived boolean not null default false;
alter table mailboxes add column if not exists deleted boolean not null default false;
alter table mailboxes add column if not exists read_flag boolean not null default false;
create index if not exists idx_mailboxes_flags on mailboxes(mailbox, box, archived, deleted);
