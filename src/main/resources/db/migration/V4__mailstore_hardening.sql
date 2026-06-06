create unique index if not exists uq_mailboxes_mailbox_message_box on mailboxes(mailbox, message_id, box);

create index if not exists idx_mailboxes_mailbox_flags_created on mailboxes(mailbox, deleted, archived, box, created_at desc);
create index if not exists idx_mailboxes_message_mailbox_deleted on mailboxes(message_id, mailbox, deleted);

create index if not exists idx_sessions_expires_at on sessions(expires_at);
create index if not exists idx_queue_claim on queue(status, next_attempt_at, attempts);

-- PostgreSQL migration path notes:
-- H2 remains the MVP runtime DB. The schema now uses portable keys/indexes that map directly to
-- PostgreSQL btree indexes. JSON-in-CLOB messages.recipients/attachments is intentionally retained
-- for MVP compatibility; the next PostgreSQL migration should split recipients/attachments into
-- child tables or JSONB columns with GIN indexes before external/large-mailbox rollout.
