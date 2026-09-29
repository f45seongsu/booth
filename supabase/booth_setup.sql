-- 1) 포토부스 결과물 버킷 (public)
insert into storage.buckets (id, name, public)
values ('booth', 'booth', true)
on conflict (id) do update set public = true;

-- 2) 브라우저(anon)에서 booth 버킷에 업로드/조회 허용
create policy "booth_anon_insert" on storage.objects
  for insert to anon, authenticated with check (bucket_id = 'booth');
create policy "booth_anon_update" on storage.objects
  for update to anon, authenticated using (bucket_id = 'booth');
create policy "booth_public_read" on storage.objects
  for select to anon, authenticated using (bucket_id = 'booth');

-- 3) 이용 기록 테이블
create table if not exists booth_logs (
  id bigserial primary key,
  member_uids text[],
  member_names text,
  photo_url text,
  making_url text,
  sms_sent boolean default false,
  created_at timestamptz not null default now()
);
grant select, insert, update, delete on booth_logs to anon, authenticated;
grant usage, select on all sequences in schema public to anon, authenticated;
alter table booth_logs disable row level security;
