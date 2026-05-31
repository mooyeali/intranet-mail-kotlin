create table if not exists users(
  id varchar(64) primary key,
  username varchar(64) not null unique,
  mailbox varchar(255) not null unique,
  password_hash varchar(255) not null,
  created_at bigint not null
);

create table if not exists sessions(
  token varchar(255) primary key,
  mailbox varchar(255) not null,
  created_at bigint not null,
  expires_at bigint not null
);

create table if not exists messages(
  id varchar(64) primary key,
  sender varchar(255) not null,
  recipients clob not null,
  subject varchar(500) not null,
  body clob not null,
  raw clob,
  attachments clob not null,
  read_flag boolean not null,
  created_at bigint not null
);

create table if not exists mailboxes(
  id varchar(64) primary key,
  mailbox varchar(255) not null,
  message_id varchar(64) not null,
  box varchar(16) not null,
  created_at bigint not null
);

create table if not exists queue(
  id varchar(64) primary key,
  message_id varchar(64) not null,
  recipient varchar(255) not null,
  status varchar(32) not null,
  attempts int not null,
  last_error clob,
  next_attempt_at bigint not null,
  created_at bigint not null,
  updated_at bigint not null
);

create index if not exists idx_mailboxes_mailbox on mailboxes(mailbox, box);
create index if not exists idx_queue_status_next on queue(status, next_attempt_at);
