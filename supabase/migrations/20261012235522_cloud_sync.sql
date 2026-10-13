-- Optional OpenTV cloud sync v1. No provider secrets or account encryption keys exist here.
-- Tombstones are retained in their original table via deleted=true (including ciphertext).
create schema if not exists opentv_private;
revoke all on schema opentv_private from public, anon;
grant usage on schema opentv_private to authenticated;

create table public.sync_devices (
  user_id uuid not null references auth.users(id) on delete cascade,
  id uuid not null,
  session_id uuid not null,
  nickname text not null check (length(nickname) between 1 and 80),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  revoked_at timestamptz,
  primary key (user_id,id), unique(user_id,session_id)
);
create table public.sync_keyring (
  user_id uuid primary key references auth.users(id) on delete cascade,
  commitment text not null check (length(commitment)=43),
  schema_version integer not null default 1 check (schema_version=1),
  created_at timestamptz not null default now()
);
create table public.sync_pairings (
  user_id uuid not null references auth.users(id) on delete cascade,
  id uuid not null default gen_random_uuid(),
  target_device_id uuid not null,
  code text not null check (code ~ '^[0-9A-F]{10}$'),
  public_key text not null check (length(public_key) between 80 and 200),
  approver_public_key text check (length(approver_public_key) between 80 and 200),
  wrapped_key text check (length(wrapped_key) between 60 and 512),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default now()+interval '10 minutes',
  consumed_at timestamptz,
  primary key(user_id,id), unique(user_id,code),
  foreign key(user_id,target_device_id) references public.sync_devices(user_id,id) on delete cascade
);
create index sync_pairings_expiry on public.sync_pairings(expires_at);
create sequence opentv_private.sync_change_seq;

-- These private SECURITY DEFINER functions are the sole write path. Every lookup uses
-- the verified auth.uid/session_id, and no user_metadata or client ownership is trusted.
create function opentv_private.sync_identity() returns uuid
language plpgsql security definer set search_path='' as $$
declare u uuid := auth.uid(); s uuid := (auth.jwt()->>'session_id')::uuid;
begin
  if u is null or s is null or not exists(select 1 from auth.sessions where id=s and user_id=u) then
    raise insufficient_privilege using message='Sign in again';
  end if;
  return u;
end $$;
create function opentv_private.sync_active() returns boolean
language sql stable security definer set search_path='' as $$
  select auth.uid() is not null and exists(
    select 1 from public.sync_devices d join auth.sessions s on s.id=d.session_id and s.user_id=d.user_id
    where d.user_id=(select auth.uid()) and d.session_id=(auth.jwt()->>'session_id')::uuid and d.revoked_at is null
  )
$$;
create function opentv_private.sync_device(device uuid default null) returns uuid
language plpgsql security definer set search_path='' as $$
declare u uuid := opentv_private.sync_identity(); d uuid;
begin
  select id into d from public.sync_devices where user_id=u and session_id=(auth.jwt()->>'session_id')::uuid
    and revoked_at is null and (device is null or id=device);
  if d is null then raise insufficient_privilege using message='Device unlinked'; end if;
  return d;
end $$;

create table public.sync_providers (
  user_id uuid not null references auth.users(id) on delete cascade,
  id uuid not null,
  writer_device_id uuid not null,
  clock bigint not null check(clock>=0),
  deleted boolean not null default false,
  ciphertext text not null check(length(ciphertext) between 30 and 65536),
  schema_version integer not null default 1 check(schema_version=1),
  change_seq bigint not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key(user_id,id),
  foreign key(user_id,writer_device_id) references public.sync_devices(user_id,id) on delete cascade
);
create index sync_providers_incremental on public.sync_providers(user_id,change_seq);
create index sync_providers_writer on public.sync_providers(user_id,writer_device_id);
alter table public.sync_providers enable row level security;
create policy own_read on public.sync_providers for select to authenticated
  using ((select auth.uid())=user_id and (select opentv_private.sync_active()));
revoke all on public.sync_providers from anon, authenticated;
grant select on public.sync_providers to authenticated;

create table public.sync_playlists (
  user_id uuid not null references auth.users(id) on delete cascade,
  id uuid not null,
  writer_device_id uuid not null,
  clock bigint not null check(clock>=0),
  deleted boolean not null default false,
  ciphertext text not null check(length(ciphertext) between 30 and 65536),
  schema_version integer not null default 1 check(schema_version=1),
  change_seq bigint not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key(user_id,id),
  foreign key(user_id,writer_device_id) references public.sync_devices(user_id,id) on delete cascade
);
create index sync_playlists_incremental on public.sync_playlists(user_id,change_seq);
create index sync_playlists_writer on public.sync_playlists(user_id,writer_device_id);
alter table public.sync_playlists enable row level security;
create policy own_read on public.sync_playlists for select to authenticated
  using ((select auth.uid())=user_id and (select opentv_private.sync_active()));
revoke all on public.sync_playlists from anon, authenticated;
grant select on public.sync_playlists to authenticated;

create table public.sync_favorites (
  user_id uuid not null references auth.users(id) on delete cascade,
  id uuid not null,
  writer_device_id uuid not null,
  clock bigint not null check(clock>=0),
  deleted boolean not null default false,
  ciphertext text not null check(length(ciphertext) between 30 and 65536),
  schema_version integer not null default 1 check(schema_version=1),
  change_seq bigint not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key(user_id,id),
  foreign key(user_id,writer_device_id) references public.sync_devices(user_id,id) on delete cascade
);
create index sync_favorites_incremental on public.sync_favorites(user_id,change_seq);
create index sync_favorites_writer on public.sync_favorites(user_id,writer_device_id);
alter table public.sync_favorites enable row level security;
create policy own_read on public.sync_favorites for select to authenticated
  using ((select auth.uid())=user_id and (select opentv_private.sync_active()));
revoke all on public.sync_favorites from anon, authenticated;
grant select on public.sync_favorites to authenticated;

create table public.sync_watch_progress (
  user_id uuid not null references auth.users(id) on delete cascade,
  id uuid not null,
  writer_device_id uuid not null,
  clock bigint not null check(clock>=0),
  deleted boolean not null default false,
  ciphertext text not null check(length(ciphertext) between 30 and 65536),
  schema_version integer not null default 1 check(schema_version=1),
  change_seq bigint not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key(user_id,id),
  foreign key(user_id,writer_device_id) references public.sync_devices(user_id,id) on delete cascade
);
create index sync_watch_progress_incremental on public.sync_watch_progress(user_id,change_seq);
create index sync_watch_progress_writer on public.sync_watch_progress(user_id,writer_device_id);
alter table public.sync_watch_progress enable row level security;
create policy own_read on public.sync_watch_progress for select to authenticated
  using ((select auth.uid())=user_id and (select opentv_private.sync_active()));
revoke all on public.sync_watch_progress from anon, authenticated;
grant select on public.sync_watch_progress to authenticated;

create table public.sync_profiles (
  user_id uuid not null references auth.users(id) on delete cascade,
  id uuid not null,
  writer_device_id uuid not null,
  clock bigint not null check(clock>=0),
  deleted boolean not null default false,
  ciphertext text not null check(length(ciphertext) between 30 and 65536),
  schema_version integer not null default 1 check(schema_version=1),
  change_seq bigint not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key(user_id,id),
  foreign key(user_id,writer_device_id) references public.sync_devices(user_id,id) on delete cascade
);
create index sync_profiles_incremental on public.sync_profiles(user_id,change_seq);
create index sync_profiles_writer on public.sync_profiles(user_id,writer_device_id);
alter table public.sync_profiles enable row level security;
create policy own_read on public.sync_profiles for select to authenticated
  using ((select auth.uid())=user_id and (select opentv_private.sync_active()));
revoke all on public.sync_profiles from anon, authenticated;
grant select on public.sync_profiles to authenticated;

create table public.sync_settings (
  user_id uuid not null references auth.users(id) on delete cascade,
  id uuid not null,
  writer_device_id uuid not null,
  clock bigint not null check(clock>=0),
  deleted boolean not null default false,
  ciphertext text not null check(length(ciphertext) between 30 and 65536),
  schema_version integer not null default 1 check(schema_version=1),
  change_seq bigint not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key(user_id,id),
  foreign key(user_id,writer_device_id) references public.sync_devices(user_id,id) on delete cascade
);
create index sync_settings_incremental on public.sync_settings(user_id,change_seq);
create index sync_settings_writer on public.sync_settings(user_id,writer_device_id);
alter table public.sync_settings enable row level security;
create policy own_read on public.sync_settings for select to authenticated
  using ((select auth.uid())=user_id and (select opentv_private.sync_active()));
revoke all on public.sync_settings from anon, authenticated;
grant select on public.sync_settings to authenticated;
alter table public.sync_devices enable row level security;
create policy own_read on public.sync_devices for select to authenticated using ((select auth.uid())=user_id);
revoke all on public.sync_devices from anon, authenticated;
grant select on public.sync_devices to authenticated;
alter table public.sync_keyring enable row level security;
create policy own_read on public.sync_keyring for select to authenticated using ((select auth.uid())=user_id);
revoke all on public.sync_keyring from anon, authenticated;
grant select on public.sync_keyring to authenticated;
alter table public.sync_pairings enable row level security;
create policy own_read on public.sync_pairings for select to authenticated using ((select auth.uid())=user_id);
revoke all on public.sync_pairings from anon, authenticated;
grant select on public.sync_pairings to authenticated;

create function opentv_private.sync_register_device(device uuid,nickname text) returns void
language plpgsql security definer set search_path='' as $$
declare u uuid:=opentv_private.sync_identity(); s uuid:=(auth.jwt()->>'session_id')::uuid;
begin
  if exists(select 1 from public.sync_devices where user_id=u and session_id=s and revoked_at is not null) then
    raise insufficient_privilege using message='Revoked session cannot register again';
  end if;
  insert into public.sync_devices(user_id,id,session_id,nickname) values(u,device,s,nickname)
    on conflict(user_id,id) do update set nickname=excluded.nickname,updated_at=now()
      where sync_devices.session_id=s and sync_devices.revoked_at is null;
  perform opentv_private.sync_device(device);
end $$;
create function opentv_private.sync_authorize_device(device uuid) returns void
language plpgsql security definer set search_path='' as $$
begin
  perform opentv_private.sync_device(device);
  update public.sync_devices set last_seen_at=now() where user_id=auth.uid() and id=device;
end $$;
create function opentv_private.sync_unlink_device(device uuid) returns void
language plpgsql security definer set search_path='' as $$
declare u uuid:=opentv_private.sync_identity();
begin
  perform opentv_private.sync_device();
  update public.sync_devices set revoked_at=coalesce(revoked_at,now()),updated_at=now() where user_id=u and id=device;
end $$;
create function opentv_private.sync_create_keyring(commitment text) returns void
language plpgsql security definer set search_path='' as $$
declare u uuid:=opentv_private.sync_identity(); existing text;
begin
  perform opentv_private.sync_device();
  insert into public.sync_keyring(user_id,commitment) values(u,commitment) on conflict(user_id) do nothing;
  select k.commitment into existing from public.sync_keyring k where user_id=u;
  if existing<>commitment then raise unique_violation using message='Recover existing encryption key'; end if;
end $$;
create function opentv_private.sync_push(device uuid,records jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=opentv_private.sync_identity(); r jsonb; t text; winner jsonb; result jsonb:='[]';
begin
  perform opentv_private.sync_device(device);
  if jsonb_typeof(records)<>'array' or jsonb_array_length(records)>100 then raise invalid_parameter_value; end if;
  -- Serializes change-sequence allocation with commits and pulls, avoiding sequence/commit races.
  perform pg_catalog.pg_advisory_xact_lock(748291601);
  for r in select * from jsonb_array_elements(records) loop
    t:=r->>'table_name';
    if t not in ('sync_providers','sync_playlists','sync_favorites','sync_watch_progress','sync_profiles','sync_settings')
      or (r->>'writer_device_id')::uuid<>device or (r->>'clock')::bigint>extract(epoch from now())*1000+300000
      or jsonb_typeof(r->'deleted') is distinct from 'boolean'
      or jsonb_typeof(r->'ciphertext') is distinct from 'string'
      or length(r->>'ciphertext') not between 30 and 65536
      or jsonb_typeof(r->'schema_version') is distinct from 'number'
      or r->>'schema_version' is distinct from '1' then
      raise invalid_parameter_value using message='Invalid sync operation or device clock';
    end if;
    execute format('insert into public.%I(user_id,id,writer_device_id,clock,deleted,ciphertext,schema_version,change_seq)
      values($1,$2,$3,$4,$5,$6,$7,nextval(''opentv_private.sync_change_seq''))
      on conflict(user_id,id) do update set writer_device_id=excluded.writer_device_id,clock=excluded.clock,
        deleted=excluded.deleted,ciphertext=excluded.ciphertext,schema_version=excluded.schema_version,
        change_seq=excluded.change_seq,updated_at=now()
      where (excluded.clock,excluded.writer_device_id)>(%I.clock,%I.writer_device_id)',t,t,t)
      using u,(r->>'id')::uuid,device,(r->>'clock')::bigint,(r->>'deleted')::boolean,r->>'ciphertext',(r->>'schema_version')::int;
    execute format('select jsonb_build_object(''table_name'',$1,''id'',id,''clock'',clock,''writer_device_id'',writer_device_id,
      ''deleted'',deleted,''ciphertext'',ciphertext,''schema_version'',schema_version,''change_seq'',change_seq)
      from public.%I where user_id=$2 and id=$3',t) into winner using t,u,(r->>'id')::uuid;
    result:=result||jsonb_build_array(winner);
  end loop;
  return result;
end $$;
create function opentv_private.sync_pull(device uuid,after_sequence bigint) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=opentv_private.sync_identity(); result jsonb;
begin
  perform opentv_private.sync_device(device);
  if after_sequence<0 then raise invalid_parameter_value; end if;
  perform pg_catalog.pg_advisory_xact_lock(748291601);
  select coalesce(jsonb_agg(to_jsonb(r) order by change_seq),'[]') into result from (
select 'sync_providers'::text as table_name,id,clock,writer_device_id,deleted,ciphertext,schema_version,change_seq from public.sync_providers where user_id=u and change_seq>after_sequence
union all
select 'sync_playlists'::text as table_name,id,clock,writer_device_id,deleted,ciphertext,schema_version,change_seq from public.sync_playlists where user_id=u and change_seq>after_sequence
union all
select 'sync_favorites'::text as table_name,id,clock,writer_device_id,deleted,ciphertext,schema_version,change_seq from public.sync_favorites where user_id=u and change_seq>after_sequence
union all
select 'sync_watch_progress'::text as table_name,id,clock,writer_device_id,deleted,ciphertext,schema_version,change_seq from public.sync_watch_progress where user_id=u and change_seq>after_sequence
union all
select 'sync_profiles'::text as table_name,id,clock,writer_device_id,deleted,ciphertext,schema_version,change_seq from public.sync_profiles where user_id=u and change_seq>after_sequence
union all
select 'sync_settings'::text as table_name,id,clock,writer_device_id,deleted,ciphertext,schema_version,change_seq from public.sync_settings where user_id=u and change_seq>after_sequence
  order by change_seq limit 200) r;
  return result;
end $$;
create function opentv_private.sync_begin_pairing(device uuid,public_key text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=opentv_private.sync_identity(); r public.sync_pairings;
begin
  perform opentv_private.sync_device(device);
  if (select count(*) from public.sync_pairings where user_id=u and expires_at>now() and consumed_at is null)>10 then
    raise program_limit_exceeded using message='Too many pending pairings';
  end if;
  insert into public.sync_pairings(user_id,target_device_id,code,public_key)
    values(u,device,upper(encode(extensions.gen_random_bytes(5),'hex')),public_key) returning * into r;
  return jsonb_build_object('id',r.id,'code',r.code,'expires_at',r.expires_at);
end $$;
create function opentv_private.sync_find_pairing(code text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=opentv_private.sync_identity(); d uuid:=opentv_private.sync_device(); r public.sync_pairings;
begin
  select * into r from public.sync_pairings p where p.user_id=u and p.code=sync_find_pairing.code and p.expires_at>now()
    and p.consumed_at is null and p.wrapped_key is null and p.target_device_id<>d;
  if r.id is null then raise no_data_found using message='Pairing code expired or already used'; end if;
  return jsonb_build_object('id',r.id,'public_key',r.public_key);
end $$;
create function opentv_private.sync_approve_pairing(pairing uuid,public_key text,wrapped_key text) returns void
language plpgsql security definer set search_path='' as $$
declare u uuid:=opentv_private.sync_identity(); d uuid:=opentv_private.sync_device();
begin
  update public.sync_pairings p set approver_public_key=sync_approve_pairing.public_key,wrapped_key=sync_approve_pairing.wrapped_key
    where p.user_id=u and p.id=pairing and p.expires_at>now() and p.consumed_at is null
      and p.wrapped_key is null and p.target_device_id<>d;
  if not found then raise no_data_found using message='Pairing code expired or already used'; end if;
end $$;
create function opentv_private.sync_read_pairing(pairing uuid) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=opentv_private.sync_identity(); d uuid:=opentv_private.sync_device(); r public.sync_pairings;
begin
  select * into r from public.sync_pairings where user_id=u and id=pairing and target_device_id=d
    and expires_at>now() and consumed_at is null;
  if r.id is null then raise no_data_found using message='Start pairing again'; end if;
  return jsonb_build_object('wrapped_key',r.wrapped_key,'approver_public_key',r.approver_public_key);
end $$;
create function opentv_private.sync_consume_pairing(pairing uuid) returns void
language plpgsql security definer set search_path='' as $$
declare u uuid:=opentv_private.sync_identity(); d uuid:=opentv_private.sync_device();
begin
  update public.sync_pairings set consumed_at=now(),wrapped_key=null where user_id=u and id=pairing and target_device_id=d
    and expires_at>now() and consumed_at is null and wrapped_key is not null;
  if not found then raise no_data_found using message='Start pairing again'; end if;
end $$;

revoke all on all functions in schema opentv_private from public, anon, authenticated;
grant execute on function opentv_private.sync_active() to authenticated;
create function public.sync_register_device(device uuid,nickname text) returns void
language sql security invoker set search_path='' as $$ select opentv_private.sync_register_device(device,nickname) $$;
revoke all on function public.sync_register_device(uuid,text) from public, anon;
grant execute on function public.sync_register_device(uuid,text) to authenticated;
grant execute on function opentv_private.sync_register_device(uuid,text) to authenticated;
create function public.sync_authorize_device(device uuid) returns void
language sql security invoker set search_path='' as $$ select opentv_private.sync_authorize_device(device) $$;
revoke all on function public.sync_authorize_device(uuid) from public, anon;
grant execute on function public.sync_authorize_device(uuid) to authenticated;
grant execute on function opentv_private.sync_authorize_device(uuid) to authenticated;
create function public.sync_unlink_device(device uuid) returns void
language sql security invoker set search_path='' as $$ select opentv_private.sync_unlink_device(device) $$;
revoke all on function public.sync_unlink_device(uuid) from public, anon;
grant execute on function public.sync_unlink_device(uuid) to authenticated;
grant execute on function opentv_private.sync_unlink_device(uuid) to authenticated;
create function public.sync_create_keyring(commitment text) returns void
language sql security invoker set search_path='' as $$ select opentv_private.sync_create_keyring(commitment) $$;
revoke all on function public.sync_create_keyring(text) from public, anon;
grant execute on function public.sync_create_keyring(text) to authenticated;
grant execute on function opentv_private.sync_create_keyring(text) to authenticated;
create function public.sync_push(device uuid,records jsonb) returns jsonb
language sql security invoker set search_path='' as $$ select opentv_private.sync_push(device,records) $$;
revoke all on function public.sync_push(uuid,jsonb) from public, anon;
grant execute on function public.sync_push(uuid,jsonb) to authenticated;
grant execute on function opentv_private.sync_push(uuid,jsonb) to authenticated;
create function public.sync_pull(device uuid,after_sequence bigint) returns jsonb
language sql security invoker set search_path='' as $$ select opentv_private.sync_pull(device,after_sequence) $$;
revoke all on function public.sync_pull(uuid,bigint) from public, anon;
grant execute on function public.sync_pull(uuid,bigint) to authenticated;
grant execute on function opentv_private.sync_pull(uuid,bigint) to authenticated;
create function public.sync_begin_pairing(device uuid,public_key text) returns jsonb
language sql security invoker set search_path='' as $$ select opentv_private.sync_begin_pairing(device,public_key) $$;
revoke all on function public.sync_begin_pairing(uuid,text) from public, anon;
grant execute on function public.sync_begin_pairing(uuid,text) to authenticated;
grant execute on function opentv_private.sync_begin_pairing(uuid,text) to authenticated;
create function public.sync_find_pairing(code text) returns jsonb
language sql security invoker set search_path='' as $$ select opentv_private.sync_find_pairing(code) $$;
revoke all on function public.sync_find_pairing(text) from public, anon;
grant execute on function public.sync_find_pairing(text) to authenticated;
grant execute on function opentv_private.sync_find_pairing(text) to authenticated;
create function public.sync_approve_pairing(pairing uuid,public_key text,wrapped_key text) returns void
language sql security invoker set search_path='' as $$ select opentv_private.sync_approve_pairing(pairing,public_key,wrapped_key) $$;
revoke all on function public.sync_approve_pairing(uuid,text,text) from public, anon;
grant execute on function public.sync_approve_pairing(uuid,text,text) to authenticated;
grant execute on function opentv_private.sync_approve_pairing(uuid,text,text) to authenticated;
create function public.sync_read_pairing(pairing uuid) returns jsonb
language sql security invoker set search_path='' as $$ select opentv_private.sync_read_pairing(pairing) $$;
revoke all on function public.sync_read_pairing(uuid) from public, anon;
grant execute on function public.sync_read_pairing(uuid) to authenticated;
grant execute on function opentv_private.sync_read_pairing(uuid) to authenticated;
create function public.sync_consume_pairing(pairing uuid) returns void
language sql security invoker set search_path='' as $$ select opentv_private.sync_consume_pairing(pairing) $$;
revoke all on function public.sync_consume_pairing(uuid) from public, anon;
grant execute on function public.sync_consume_pairing(uuid) to authenticated;
grant execute on function opentv_private.sync_consume_pairing(uuid) to authenticated;

-- Postgres Changes only delivers ciphertext and metadata. Reconciliation recovers dropped events.
do $$ declare t text; begin
  if exists(select 1 from pg_publication where pubname='supabase_realtime') then
    foreach t in array array['sync_devices','sync_providers','sync_playlists','sync_favorites','sync_watch_progress','sync_profiles','sync_settings'] loop
      execute format('alter publication supabase_realtime add table public.%I',t);
    end loop;
  end if;
end $$;
