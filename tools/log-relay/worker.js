// Write-only Telegram relay for diagnostic logs (KPower, KGhost, KSafe, ...).
//
// The apps POST a multipart form with a `document` file (+ optional `caption`, `app`). The Worker adds
// the bot token and the chat id, both kept as Cloudflare secrets, and calls Telegram sendDocument.
// Whoever extracts the relay URL from an APK can only drop documents into the dev chat: no reading,
// forwarding, deleting, and no access to the rest of the bot.
//
// Secrets (wrangler secret put ...): BOT_TOKEN, CHAT_ID
// Var (wrangler.toml): ALLOWED_APPS = "kpower,kghost,ksafe"

const MAX_BODY = 120 * 1024;          // Karoo chunks are <= 85 KB + multipart overhead
const MAX_CAPTION = 1000;             // Telegram caption limit is 1024

export default {
  async fetch(request, env) {
    if (request.method !== "POST") return json(405, "POST only");
    const len = Number(request.headers.get("content-length") || 0);
    // Header may be absent (chunked upload via the Companion proxy); doc.size is checked below anyway.
    if (len > MAX_BODY) return json(413, "Body too large");
    if (!(request.headers.get("content-type") || "").startsWith("multipart/form-data")) {
      return json(415, "multipart/form-data expected");
    }

    let form;
    try { form = await request.formData(); } catch { return json(400, "Bad multipart body"); }

    const doc = form.get("document");
    if (!(doc instanceof File) || doc.size === 0 || doc.size > MAX_BODY) return json(400, "document missing");

    const allowed = (env.ALLOWED_APPS || "").split(",").map((s) => s.trim()).filter(Boolean);
    const app = String(form.get("app") || "").toLowerCase();
    if (allowed.length && !allowed.includes(app)) return json(403, "Unknown app");

    const caption = `[${app || "?"}] ${String(form.get("caption") || "")}`.slice(0, MAX_CAPTION);

    const out = new FormData();
    out.append("chat_id", env.CHAT_ID);              // never taken from the client
    out.append("caption", caption);
    out.append("document", doc, (doc.name || "log.txt").slice(0, 120));

    const tg = await fetch(`https://api.telegram.org/bot${env.BOT_TOKEN}/sendDocument`, {
      method: "POST",
      body: out,
    });
    // Return only ok/description: the full Telegram reply (chat, file ids) stays here.
    let body = {};
    try { body = await tg.json(); } catch {}
    return json(tg.status, body.ok ? null : body.description || "Telegram error");
  },
};

function json(status, description) {
  const ok = status >= 200 && status < 300 && !description;
  return new Response(JSON.stringify(ok ? { ok: true } : { ok: false, description }), {
    status: ok ? 200 : status,
    headers: { "content-type": "application/json" },
  });
}
