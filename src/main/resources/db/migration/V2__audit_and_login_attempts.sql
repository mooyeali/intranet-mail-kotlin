create table if not exists audit_events(
  id varchar(64) primary key,
  actor varchar(255),
  action varchar(128) not null,
  target varchar(255),
  detail clob,
  ip varchar(64),
  created_at bigint not null
);

create table if not exists login_attempts(
  id varchar(64) primary key,
  username varchar(255) not null,
  ip varchar(64) not null,
  success boolean not null,
  created_at bigint not null
);

create index if not exists idx_audit_created_at on audit_events(created_at);
create index if not exists idx_login_attempts_lookup on login_attempts(username, ip, created_at);
