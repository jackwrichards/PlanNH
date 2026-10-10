// A local stand-in for gtnhplanner.com's community API, for testing the planner's Library, sign-in, posting, and
// editing and deleting one's own posts without touching the real site: accounts and posts live in memory, posted
// plans are written to build/dev-client/mock-posts/ for a look (or a run past the website's own schema). It has no
// item lists, so icons go up without the website's picture of them.
//
//   node tools/dev/mock-library.mjs [port]          (default 8789)
//   tools/dev/mc.sh call 'library?url=http://127.0.0.1:8789'   points the running game at it
//   tools/dev/mc.sh call 'library?url=https://gtnhplanner.com' back to the real site
import { createServer } from "node:http";
import { mkdirSync, writeFileSync } from "node:fs";
import { randomUUID } from "node:crypto";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const port = Number(process.argv[2] ?? 8789);
const root = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const postsDir = join(root, "build", "dev-client", "mock-posts");
mkdirSync(postsDir, { recursive: true });

const users = new Map(); // name -> password
const sessions = new Map(); // token -> name
const posts = []; // summaries, newest first
const plans = new Map(); // id -> { name, plan }

const json = (res, status, body, headers = {}) => {
  res.writeHead(status, { "Content-Type": "application/json", ...headers });
  res.end(JSON.stringify(body));
};
// The site keeps a post's icon only when it is a proper EntryIcon; anything else clears it.
const iconOf = (icon) =>
  icon && (icon.kind === "item" || icon.kind === "fluid") && typeof icon.resourceId === "string" && icon.resourceId
    ? icon
    : undefined;
const userOf = (req) => {
  const m = /(?:^|;\s*)gtnh_session=([^;]+)/.exec(req.headers.cookie ?? "");
  return m ? sessions.get(m[1]) : undefined;
};
const signIn = (res, name) => {
  const token = randomUUID();
  sessions.set(token, name);
  json(res, 200, { username: name, isAdmin: false }, {
    "Set-Cookie": `gtnh_session=${token}; Path=/; HttpOnly; SameSite=Lax; Max-Age=15552000`,
  });
};
const body = (req) =>
  new Promise((resolve) => {
    let s = "";
    req.on("data", (c) => (s += c));
    req.on("end", () => {
      try {
        resolve(s ? JSON.parse(s) : {});
      } catch {
        resolve({});
      }
    });
  });

createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${port}`);
  const path = url.pathname;
  console.log(new Date().toISOString().slice(11, 19), req.method, req.url, userOf(req) ? `(as ${userOf(req)})` : "");
  if (req.method === "POST" && path === "/api/community/auth/register") {
    const { username = "", password = "" } = await body(req);
    if (!/^[a-zA-Z0-9_-]{3,24}$/.test(username.trim()))
      return json(res, 400, { error: "Username must be 3-24 characters: letters, numbers, - or _." });
    if (password.length < 6) return json(res, 400, { error: "Password must be at least 6 characters." });
    if (users.has(username.trim())) return json(res, 409, { error: "That username is taken." });
    users.set(username.trim(), password);
    return signIn(res, username.trim());
  }
  if (req.method === "POST" && path === "/api/community/auth/login") {
    const { username = "", password = "" } = await body(req);
    if (!username || !password) return json(res, 400, { error: "Username and password required." });
    if (users.get(username.trim()) !== password) return json(res, 401, { error: "Wrong username or password." });
    return signIn(res, username.trim());
  }
  if (req.method === "GET" && path === "/api/community/auth/me") {
    const name = userOf(req);
    return json(res, 200, { user: name ? { username: name, isAdmin: false } : null });
  }
  if (req.method === "POST" && path === "/api/community/plans") {
    const b = await body(req);
    const name = typeof b.name === "string" ? b.name.trim() : "";
    if (!name) return json(res, 400, { error: "Plan name is required (max 80 chars)." });
    if (!b.deviceId) return json(res, 400, { error: "Missing device id." });
    const who = userOf(req);
    if (!who) return json(res, 401, { error: "Sign in to share plans to the community." });
    if (!b.plan?.nodes?.length) return json(res, 400, { error: "Refusing to share an empty plan." });
    const id = randomUUID();
    writeFileSync(join(postsDir, `${id}.json`), JSON.stringify(b.plan, null, 2));
    plans.set(id, { name, plan: b.plan });
    const now = new Date().toISOString();
    posts.unshift({
      id, name, description: b.description ?? "", authorName: who, gameVersion: b.gameVersion ?? "",
      datasetVersionId: "", tags: [], isPublic: true, icon: iconOf(b.icon), needs: [], outputs: [], totalEuT: 0,
      machineCount: b.plan.nodes.length, nodeCount: b.plan.nodes.length, storageCount: b.plan.storages?.length ?? 0,
      edgeCount: b.plan.edges?.length ?? 0, highestTier: "LV", highestTierIndex: 1, upvotes: 0, downvotes: 0,
      score: 0, downloads: 0, views: 0, commentCount: 0, createdAt: now, lastActivityAt: now,
    });
    console.log(`  posted '${name}' by ${who}: build/dev-client/mock-posts/${id}.json`);
    return json(res, 201, { id });
  }
  const download = /^\/api\/community\/plans\/([^/]+)\/download$/.exec(path);
  if (req.method === "POST" && download) {
    const p = plans.get(download[1]);
    return p ? json(res, 200, p) : json(res, 404, { error: "Plan not found." });
  }
  // A post of one's own, changed field by field or taken down, as the site's PUT and DELETE do.
  const one = /^\/api\/community\/plans\/([^/]+)$/.exec(path);
  if (one && (req.method === "PUT" || req.method === "DELETE")) {
    const b = req.method === "PUT" ? await body(req) : {};
    const who = userOf(req);
    if (!who) return json(res, 401, { error: req.method === "PUT" ? "Sign in to update your post." : "Sign in to take down your post." });
    const post = posts.find((p) => p.id === one[1]);
    if (!post) return json(res, 404, { error: "Plan not found." });
    if (post.authorName !== who) return json(res, 403, { error: "You don't own this post." });
    if (req.method === "DELETE") {
      posts.splice(posts.indexOf(post), 1);
      plans.delete(post.id);
      console.log(`  deleted '${post.name}'`);
      return json(res, 200, { ok: true });
    }
    if (b.name !== undefined) {
      const name = typeof b.name === "string" ? b.name.trim() : "";
      if (!name || name.length > 80) return json(res, 400, { error: "Plan name is required (max 80 chars)." });
      post.name = name;
    }
    if (b.description !== undefined) post.description = typeof b.description === "string" ? b.description.trim().slice(0, 2000) : "";
    if (b.gameVersion !== undefined) post.gameVersion = String(b.gameVersion ?? "");
    if (b.icon !== undefined) post.icon = iconOf(b.icon);
    if (b.plan !== undefined) {
      if (!b.plan?.nodes?.length) return json(res, 400, { error: "Refusing to share an empty plan." });
      writeFileSync(join(postsDir, `${post.id}.json`), JSON.stringify(b.plan, null, 2));
      plans.set(post.id, { name: post.name, plan: b.plan });
      post.machineCount = post.nodeCount = b.plan.nodes.length;
    }
    post.lastActivityAt = new Date().toISOString();
    if (plans.has(post.id)) plans.get(post.id).name = post.name;
    console.log(`  updated '${post.name}': ${Object.keys(b).join(", ")}${b.icon ? ` (icon ${b.icon.resourceId}${b.icon.iconPath ? ", with picture" : ""})` : ""}`);
    return json(res, 200, { id: post.id });
  }
  if (req.method === "GET" && path === "/api/community/plans") {
    const mine = url.searchParams.get("mine") === "1";
    const who = userOf(req);
    const shown = (mine ? posts.filter((p) => p.authorName === who) : posts).map((p) => ({
      ...p,
      isMine: Boolean(who && p.authorName === who),
    }));
    return json(res, 200, { plans: shown, total: shown.length, page: 1, pageSize: 48, gameVersions: ["2.8.4"] });
  }
  json(res, 404, { error: "not found" });
}).listen(port, "127.0.0.1", () => console.log(`mock gtnhplanner.com on http://127.0.0.1:${port}`));
