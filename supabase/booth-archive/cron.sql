-- booth-archive 를 매시간 7분에 실행 (48시간 지난 파일 → Dropbox 이동)
-- <CRON_SECRET> 을 Edge Function Secrets 에 넣은 값과 똑같이 바꿔서 실행
create extension if not exists pg_cron;
create extension if not exists pg_net;

select cron.schedule(
  'booth-archive-hourly',
  '7 * * * *',
  $$
  select net.http_post(
    url := 'https://mvgbwsbvngdnxdndwimb.supabase.co/functions/v1/booth-archive',
    headers := jsonb_build_object('Content-Type','application/json','x-cron-secret','<CRON_SECRET>'),
    body := '{}'::jsonb,
    timeout_milliseconds := 140000
  );
  $$
);

-- 확인: select * from cron.job;
-- 실행 기록: select * from net._http_response order by created desc limit 10;
-- 중지: select cron.unschedule('booth-archive-hourly');
