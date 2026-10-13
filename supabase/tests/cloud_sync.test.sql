begin;
create extension if not exists pgtap with schema extensions;
set local search_path=public,extensions;
select no_plan();

insert into auth.users(id) values
 ('10000000-0000-4000-8000-000000000001'),('10000000-0000-4000-8000-000000000002');
insert into auth.sessions(id,user_id) values
 ('20000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000001'),
 ('20000000-0000-4000-8000-000000000002','10000000-0000-4000-8000-000000000001'),
 ('20000000-0000-4000-8000-000000000003','10000000-0000-4000-8000-000000000002');
create temporary table test_pair (value jsonb);
grant all on test_pair to authenticated;
set local role authenticated;
set local request.jwt.claims='{"sub":"10000000-0000-4000-8000-000000000001","session_id":"20000000-0000-4000-8000-000000000001","role":"authenticated"}';
select lives_ok($$select sync_register_device('30000000-0000-4000-8000-000000000001','First TV')$$,'register first authorized session');
select lives_ok($$select sync_create_keyring(repeat('k',43))$$,'create key commitment without storing a key');
select throws_ok($$select sync_create_keyring(repeat('x',43))$$,'23505','Recover existing encryption key','cannot replace account encryption key');
select lives_ok($$select sync_push('30000000-0000-4000-8000-000000000001',jsonb_build_array(jsonb_build_object(
 'table_name','sync_providers','id','40000000-0000-4000-8000-000000000001','clock',100,'writer_device_id','30000000-0000-4000-8000-000000000001',
 'deleted',false,'ciphertext',repeat('ciphertext',10),'schema_version',1)))$$,'new provider is accepted');
select is((select count(*)::int from sync_providers),1,'owner reads their provider');
select is(jsonb_array_length(sync_pull('30000000-0000-4000-8000-000000000001',0)),1,'incremental pull contains provider');
select is(jsonb_array_length(sync_pull('30000000-0000-4000-8000-000000000001',(select max(change_seq) from sync_providers))),0,'cursor skips already seen changes');
select lives_ok($$select sync_push('30000000-0000-4000-8000-000000000001',jsonb_build_array(jsonb_build_object(
 'table_name','sync_providers','id','40000000-0000-4000-8000-000000000001','clock',200,'writer_device_id','30000000-0000-4000-8000-000000000001',
 'deleted',true,'ciphertext',repeat('tombstone',10),'schema_version',1)))$$,'delete is a retained tombstone');
select ok((select deleted from sync_providers),'deletion propagates');
select lives_ok($$select sync_push('30000000-0000-4000-8000-000000000001',jsonb_build_array(jsonb_build_object(
 'table_name','sync_providers','id','40000000-0000-4000-8000-000000000001','clock',100,'writer_device_id','30000000-0000-4000-8000-000000000001',
 'deleted',false,'ciphertext',repeat('oldcipher',10),'schema_version',1)))$$,'stale offline edit may retry');
select ok((select deleted from sync_providers),'stale edit does not resurrect deletion');
select is((select count(*)::int from sync_providers),1,'retries do not duplicate providers');
select throws_ok($$insert into sync_providers(user_id,id) values('10000000-0000-4000-8000-000000000002',gen_random_uuid())$$,'42501','permission denied for table sync_providers','direct writes cannot bypass ownership or reconciliation');
select throws_ok($$select sync_push('30000000-0000-4000-8000-000000000001','[{"table_name":"auth.users","id":"40000000-0000-4000-8000-000000000001","clock":100,"writer_device_id":"30000000-0000-4000-8000-000000000001"}]')$$,'22023','Invalid sync operation or device clock','dynamic table names are allowlisted');

-- Malformed content fields must fail before casts/NOT NULL constraints in the dynamic insert.
with valid as (
  select jsonb_build_object('table_name','sync_providers','id','40000000-0000-4000-8000-000000000001',
    'clock',300,'writer_device_id','30000000-0000-4000-8000-000000000001','deleted',false,
    'ciphertext',repeat('ciphertext',10),'schema_version',1) as record
)
select throws_ok(format($q$select sync_push('30000000-0000-4000-8000-000000000001',%L::jsonb)$q$,jsonb_build_array(r)),
  '22023','Invalid sync operation or device clock',description)
from valid cross join lateral (values
  (record-'deleted','reject missing deletion flag'),
  (record||jsonb_build_object('deleted',null),'reject null deletion flag'),
  (record||jsonb_build_object('deleted','true'),'reject string deletion flag'),
  (record||jsonb_build_object('deleted',0),'reject numeric deletion flag'),
  (record-'ciphertext','reject missing ciphertext'),
  (record||jsonb_build_object('ciphertext',null),'reject null ciphertext'),
  (record||jsonb_build_object('ciphertext',123),'reject numeric ciphertext'),
  (record||jsonb_build_object('ciphertext',repeat('x',29)),'reject short ciphertext'),
  (record||jsonb_build_object('ciphertext',repeat('x',65537)),'reject oversized ciphertext'),
  (record-'schema_version','reject missing schema version'),
  (record||jsonb_build_object('schema_version',null),'reject null schema version'),
  (record||jsonb_build_object('schema_version','1'),'reject string schema version'),
  (record||jsonb_build_object('schema_version',2),'reject unsupported schema version'),
  (record||jsonb_build_object('schema_version',1.0),'reject non-integer schema representation')
) invalid(r,description);

-- Every logical entity uses the same authorization, ownership, and incremental path.
select lives_ok(format($q$select sync_push('30000000-0000-4000-8000-000000000001',jsonb_build_array(jsonb_build_object(
 'table_name',%L,'id','40000000-0000-4000-8000-000000000002','clock',100,'writer_device_id','30000000-0000-4000-8000-000000000001',
 'deleted',false,'ciphertext',repeat('ciphertext',10),'schema_version',1)))$q$, t),t||' accepts owner writes')
 from unnest(array['sync_playlists','sync_favorites','sync_watch_progress','sync_profiles','sync_settings']) t;

-- A second independently authenticated device in the same account can pair and resume.
set local request.jwt.claims='{"sub":"10000000-0000-4000-8000-000000000001","session_id":"20000000-0000-4000-8000-000000000002","role":"authenticated"}';
select lives_ok($$select sync_register_device('30000000-0000-4000-8000-000000000002','Second TV')$$,'register another device');
select is(jsonb_array_length(sync_pull('30000000-0000-4000-8000-000000000002',0)),6,'second device sees account data');
insert into test_pair select sync_begin_pairing('30000000-0000-4000-8000-000000000002',repeat('p',120));
select ok((select length(value->>'code')=10 from test_pair),'pair code is TV-friendly');
set local request.jwt.claims='{"sub":"10000000-0000-4000-8000-000000000001","session_id":"20000000-0000-4000-8000-000000000001","role":"authenticated"}';
select lives_ok($$select sync_find_pairing((select value->>'code' from test_pair))$$,'authorized peer can find a request');
select lives_ok($$select sync_approve_pairing((select (value->>'id')::uuid from test_pair),repeat('q',120),repeat('wrapped',15))$$,'peer approves once');
select throws_ok($$select sync_approve_pairing((select (value->>'id')::uuid from test_pair),repeat('q',120),repeat('wrapped',15))$$,'P0002','Pairing code expired or already used','approval cannot be repeated');
set local request.jwt.claims='{"sub":"10000000-0000-4000-8000-000000000001","session_id":"20000000-0000-4000-8000-000000000002","role":"authenticated"}';
select ok(sync_read_pairing((select (value->>'id')::uuid from test_pair))->>'wrapped_key' is not null,'target receives only encrypted key envelope');
select lives_ok($$select sync_consume_pairing((select (value->>'id')::uuid from test_pair))$$,'target consumes once');
select throws_ok($$select sync_consume_pairing((select (value->>'id')::uuid from test_pair))$$,'P0002','Start pairing again','consumed request cannot be reused');
delete from test_pair;
insert into test_pair select sync_begin_pairing('30000000-0000-4000-8000-000000000002',repeat('p',120));
reset role;
update sync_pairings set expires_at=now()-interval '1 second' where id=(select (value->>'id')::uuid from test_pair);
set local role authenticated;
set local request.jwt.claims='{"sub":"10000000-0000-4000-8000-000000000001","session_id":"20000000-0000-4000-8000-000000000001","role":"authenticated"}';
select throws_ok($$select sync_find_pairing((select value->>'code' from test_pair))$$,'P0002','Pairing code expired or already used','expired code is rejected');

-- Another account cannot read, change, pair, revoke, or impersonate the first account.
set local request.jwt.claims='{"sub":"10000000-0000-4000-8000-000000000002","session_id":"20000000-0000-4000-8000-000000000003","role":"authenticated"}';
select lives_ok($$select sync_register_device('30000000-0000-4000-8000-000000000003','Other account')$$,'register other account');
select is((select count(*)::int from sync_providers),0,'cross-account provider read is empty');
select is((select count(*)::int from sync_playlists),0,'cross-account playlist isolation');
select is((select count(*)::int from sync_favorites),0,'cross-account favorite isolation');
select is((select count(*)::int from sync_watch_progress),0,'cross-account watch history isolation');
select is((select count(*)::int from sync_profiles),0,'cross-account profile isolation');
select is((select count(*)::int from sync_settings),0,'cross-account preference isolation');
select is((select count(*)::int from sync_keyring),0,'cross-account key commitment read is empty');
select is((select count(*)::int from sync_pairings),0,'cross-account pairing read is empty');
select is((select count(*)::int from sync_devices where user_id='10000000-0000-4000-8000-000000000001'),0,'cross-account device read is empty');
select throws_ok($$select sync_pull('30000000-0000-4000-8000-000000000001',0)$$,'42501','Device unlinked','cannot impersonate another account device');
select lives_ok($$select sync_unlink_device('30000000-0000-4000-8000-000000000001')$$,'foreign unlink is harmless');
select throws_ok($$select sync_approve_pairing((select (value->>'id')::uuid from test_pair),repeat('q',120),repeat('wrapped',15))$$,'P0002','Pairing code expired or already used','cannot approve another account pairing');
select is(jsonb_array_length(sync_pull('30000000-0000-4000-8000-000000000003',0)),0,'cross-account RPC pull is empty');

set local request.jwt.claims='{"sub":"10000000-0000-4000-8000-000000000001","session_id":"20000000-0000-4000-8000-000000000001","role":"authenticated"}';
select ok((select revoked_at is null from sync_devices where id='30000000-0000-4000-8000-000000000001'),'other account could not unlink owner');
select lives_ok($$select sync_unlink_device('30000000-0000-4000-8000-000000000002')$$,'owner unlinks second device');
set local request.jwt.claims='{"sub":"10000000-0000-4000-8000-000000000001","session_id":"20000000-0000-4000-8000-000000000002","role":"authenticated"}';
select throws_ok($$select sync_pull('30000000-0000-4000-8000-000000000002',0)$$,'42501','Device unlinked','revoked device loses RPC access');
select is((select count(*)::int from sync_providers),0,'revoked device loses direct ciphertext read access');
select throws_ok($$select sync_register_device(gen_random_uuid(),'Bypass')$$,'42501','Revoked session cannot register again','revoked session cannot bypass unlink by choosing another UUID');

-- Signed JWTs whose session was deleted are rejected before JWT expiration.
reset role;
delete from auth.sessions where id='20000000-0000-4000-8000-000000000003';
set local role authenticated;
set local request.jwt.claims='{"sub":"10000000-0000-4000-8000-000000000002","session_id":"20000000-0000-4000-8000-000000000003","role":"authenticated"}';
select throws_ok($$select sync_pull('30000000-0000-4000-8000-000000000003',0)$$,'42501','Sign in again','expired or logged-out session cannot synchronize');
reset role;
set local role anon;
select throws_ok($$select * from sync_providers$$,'42501','permission denied for table sync_providers','anonymous users cannot read ciphertext');
select throws_ok($$select sync_register_device(gen_random_uuid(),'Anonymous')$$,'42501','permission denied for function sync_register_device','anonymous users cannot register');
reset role;
select ok(bool_and(relrowsecurity),'all application tables enable RLS') from pg_class c join pg_namespace n on n.oid=c.relnamespace
 where n.nspname='public' and c.relname like 'sync_%' and c.relkind='r';
select ok(not exists(select 1 from information_schema.role_table_grants where grantee in ('anon','authenticated') and table_name like 'sync_%' and privilege_type in ('INSERT','UPDATE','DELETE')),'all content mutations go through validated RPCs');
select * from finish();
rollback;
