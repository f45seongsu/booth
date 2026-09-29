// Supabase Edge Function: booth-archive
// 48시간 지난 포토부스 파일(사진·메이킹·부메랑)을 Dropbox로 옮기고 Supabase에서 삭제
// 매시간 cron으로 호출. 한 번에 BATCH개씩만 처리해서 실행시간 제한에 안 걸리게 함.
import { createClient } from "npm:@supabase/supabase-js@2";

const SB_URL = Deno.env.get("SUPABASE_URL")!;
const SB_SERVICE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const DBX_KEY = Deno.env.get("DROPBOX_APP_KEY")!;
const DBX_SECRET = Deno.env.get("DROPBOX_APP_SECRET")!;
const DBX_REFRESH = Deno.env.get("DROPBOX_REFRESH_TOKEN")!;
const CRON_SECRET = Deno.env.get("CRON_SECRET")!;
const KEEP_HOURS = Number(Deno.env.get("BOOTH_KEEP_HOURS") ?? "48");
const BATCH = 20;
const BUCKET = "booth";

// Dropbox-API-Arg 헤더는 ASCII만 허용 → 한글은 \uXXXX로 이스케이프
function asciiJson(o: unknown) {
  return JSON.stringify(o).replace(/[\u007f-\uffff]/g, (c) => "\\u" + c.charCodeAt(0).toString(16).padStart(4, "0"));
}
function cleanName(s: string) {
  return s.replace(/[\\/:*?"<>|\r\n\t]/g, "").replace(/\s*,\s*/g, "·").trim().slice(0, 60);
}
// "2026-09-23/1790123456789-a1b2c3_b.mp4" → 사람이 읽기 좋은 이름
function niceName(path: string, names: Record<string, string>) {
  const [day, file] = path.split("/");
  const m = file.match(/^(\d{10,})-([a-z0-9]+)(_b)?\.(\w+)$/);
  if (!m) return path;
  const [, ts, rnd, boom, ext] = m;
  const hhmm = new Date(Number(ts)).toLocaleTimeString("en-GB", { timeZone: "Asia/Seoul", hour: "2-digit", minute: "2-digit", hour12: false }).replace(":", "");
  const who = cleanName(names[`${day}/${ts}-${rnd}`] || "") || "게스트";
  const kind = boom ? "부메랑" : ext === "jpg" ? "사진" : "메이킹";
  return `${day}/${day} ${hhmm} ${who} ${kind}.${ext}`;
}

async function dropboxToken(): Promise<string> {
  const r = await fetch("https://api.dropboxapi.com/oauth2/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "refresh_token",
      refresh_token: DBX_REFRESH,
      client_id: DBX_KEY,
      client_secret: DBX_SECRET,
    }),
  });
  const j = await r.json();
  if (!j.access_token) throw new Error("dropbox token failed: " + JSON.stringify(j));
  return j.access_token;
}

async function dropboxUpload(token: string, path: string, body: ArrayBuffer) {
  const r = await fetch("https://content.dropboxapi.com/2/files/upload", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/octet-stream",
      "Dropbox-API-Arg": asciiJson({ path, mode: "add", autorename: true, mute: true }),
    },
    body,
  });
  if (!r.ok) throw new Error(`dropbox upload ${path}: ${r.status} ${await r.text()}`);
}

Deno.serve(async (req) => {
  if (req.headers.get("x-cron-secret") !== CRON_SECRET) {
    return new Response("forbidden", { status: 403 });
  }
  const sb = createClient(SB_URL, SB_SERVICE);
  const cutoff = Date.now() - KEEP_HOURS * 3600 * 1000;
  const moved: string[] = [], failed: string[] = [];

  const { data: folders, error } = await sb.storage.from(BUCKET).list("", { limit: 1000, sortBy: { column: "name", order: "asc" } });
  if (error) return Response.json({ ok: false, error: error.message }, { status: 500 });

  // booth_logs 에서 촬영 id → 회원 이름 매핑
  const names: Record<string, string> = {};
  {
    const since = new Date(Date.now() - 30 * 86400 * 1000).toISOString();
    const { data: logs } = await sb.from("booth_logs").select("photo_url,member_names").gte("created_at", since).limit(5000);
    for (const l of logs ?? []) {
      const m = String(l.photo_url || "").match(/\/booth\/(.+)\.jpg/);
      if (m) names[decodeURIComponent(m[1])] = l.member_names || "";
    }
  }

  let token = "";
  outer: for (const f of folders ?? []) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(f.name)) continue; // 날짜 폴더만
    const { data: files } = await sb.storage.from(BUCKET).list(f.name, { limit: 1000 });
    for (const file of files ?? []) {
      if (!file.id) continue; // 하위 폴더 skip
      const created = new Date(file.created_at ?? 0).getTime();
      if (created > cutoff) continue; // 아직 보관기간 안 지남
      if (moved.length + failed.length >= BATCH) break outer;
      const path = `${f.name}/${file.name}`;
      try {
        if (!token) token = await dropboxToken();
        const { data: blob, error: dErr } = await sb.storage.from(BUCKET).download(path);
        if (dErr || !blob) throw new Error("download: " + dErr?.message);
        await dropboxUpload(token, `/booth/${niceName(path, names)}`, await blob.arrayBuffer());
        const { error: rmErr } = await sb.storage.from(BUCKET).remove([path]); // 백업 성공한 것만 삭제
        if (rmErr) throw new Error("remove: " + rmErr.message);
        moved.push(niceName(path, names));
      } catch (e) {
        failed.push(`${path} :: ${(e as Error).message}`);
      }
    }
  }
  return Response.json({ ok: failed.length === 0, moved: moved.length, files: moved, failed });
});
