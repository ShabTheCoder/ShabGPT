
const MODEL = "@cf/google/gemma-4-26b-a4b-it";
const ITERATIONS = 310000;
const SESSION_DAYS = 30;

const headers = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization"
};

function reply(data, status = 200) {
  return Response.json(data, { status, headers });
}

function bytesToBase64(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function base64ToBytes(value) {
  return Uint8Array.from(atob(value), c => c.charCodeAt(0));
}

function toBase64Url(bytes) {
  return bytesToBase64(bytes)
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=/g, "");
}

async function hashPassword(password) {
  const salt = crypto.getRandomValues(new Uint8Array(16));

  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(password),
    "PBKDF2",
    false,
    ["deriveBits"]
  );

  const bits = await crypto.subtle.deriveBits(
    {
      name: "PBKDF2",
      salt,
      iterations: ITERATIONS,
      hash: "SHA-256"
    },
    key,
    256
  );

  return `pbkdf2$${ITERATIONS}$${bytesToBase64(salt)}$${bytesToBase64(new Uint8Array(bits))}`;
}

async function verifyPassword(password, stored) {
  const parts = stored.split("$");
  if (parts.length !== 4 || parts[0] !== "pbkdf2") return false;

  const iterations = Number(parts[1]);
  if (!Number.isInteger(iterations) || iterations < 100000 || iterations > 1000000) {
    return false;
  }

  const salt = base64ToBytes(parts[2]);
  const expected = base64ToBytes(parts[3]);

  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(password),
    "PBKDF2",
    false,
    ["deriveBits"]
  );

  const bits = await crypto.subtle.deriveBits(
    {
      name: "PBKDF2",
      salt,
      iterations,
      hash: "SHA-256"
    },
    key,
    256
  );

  const actual = new Uint8Array(bits);
  if (actual.length !== expected.length) return false;

  let difference = 0;
  for (let i = 0; i < actual.length; i++) {
    difference |= actual[i] ^ expected[i];
  }

  return difference === 0;
}

async function hashToken(token) {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(token)
  );

  return Array.from(new Uint8Array(digest))
    .map(byte => byte.toString(16).padStart(2, "0"))
    .join("");
}

function getToken(request) {
  const authorization = request.headers.get("Authorization") || "";
  if (!authorization.startsWith("Bearer ")) return null;
  return authorization.slice(7).trim() || null;
}

async function createSession(env, userId) {
  const token = toBase64Url(crypto.getRandomValues(new Uint8Array(32)));
  const tokenHash = await hashToken(token);
  const now = Math.floor(Date.now() / 1000);

  await env.DB.prepare(
    "INSERT INTO sessions (id, user_id, expires_at, created_at) VALUES (?, ?, ?, ?)"
  ).bind(
    tokenHash,
    userId,
    now + SESSION_DAYS * 86400,
    now
  ).run();

  return token;
}

async function getSession(request, env) {
  const token = getToken(request);
  if (!token) return null;

  const tokenHash = await hashToken(token);
  const now = Math.floor(Date.now() / 1000);

  const session = await env.DB.prepare(`
    SELECT users.id, users.username, sessions.id AS session_id
    FROM sessions
    JOIN users ON users.id = sessions.user_id
    WHERE sessions.id = ? AND sessions.expires_at > ?
  `).bind(tokenHash, now).first();

  return session || null;
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { headers });
    }

    const url = new URL(request.url);
    const path = url.pathname;

    if (request.method === "GET" && path === "/") {
      return reply({ name: "ShabGPT", status: "online" });
    }

    if (request.method === "POST" && path === "/auth/register") {
      try {
        const body = await request.json();
        const username = String(body.username || "").trim();
        const password = String(body.password || "");

        if (!/^[a-zA-Z0-9_]{3,24}$/.test(username)) {
          return reply({
            error: "Username must be 3–24 characters using letters, numbers or underscores."
          }, 400);
        }

        if (password.length < 10 || password.length > 128) {
          return reply({
            error: "Password must be between 10 and 128 characters."
          }, 400);
        }

        const existing = await env.DB.prepare(
          "SELECT id FROM users WHERE username = ? COLLATE NOCASE"
        ).bind(username).first();

        if (existing) {
          return reply({ error: "Username already taken." }, 409);
        }

        const userId = crypto.randomUUID();
        const passwordHash = await hashPassword(password);
        const now = Math.floor(Date.now() / 1000);

        try {
          await env.DB.prepare(
            "INSERT INTO users (id, username, password_hash, created_at) VALUES (?, ?, ?, ?)"
          ).bind(userId, username, passwordHash, now).run();
        } catch {
          return reply({ error: "Username already taken." }, 409);
        }

        const token = await createSession(env, userId);

        return reply({
          message: "Account created successfully.",
          user: { id: userId, username },
          token
        }, 201);

     } catch (error) {
         return reply({
         error: "Registration failed.",
         details: error.message
      }, 500);
    }
    }

    if (request.method === "POST" && path === "/auth/login") {
      try {
        const body = await request.json();
        const username = String(body.username || "").trim();
        const password = String(body.password || "");

        const user = await env.DB.prepare(
          "SELECT id, username, password_hash FROM users WHERE username = ? COLLATE NOCASE"
        ).bind(username).first();

        if (!user || !(await verifyPassword(password, user.password_hash))) {
          return reply({ error: "Invalid username or password." }, 401);
        }

        const token = await createSession(env, user.id);

        return reply({
          message: "Login successful.",
          user: { id: user.id, username: user.username },
          token
        });

      } catch {
        return reply({ error: "Login failed." }, 500);
      }
    }

    if (request.method === "POST" && path === "/auth/logout") {
      try {
        const session = await getSession(request, env);
        if (!session) {
          return reply({ error: "Invalid or expired session." }, 401);
        }

        await env.DB.prepare(
          "DELETE FROM sessions WHERE id = ?"
        ).bind(session.session_id).run();

        return reply({ message: "Logged out successfully." });

      } catch {
        return reply({ error: "Logout failed." }, 500);
      }
    }

    if (request.method === "GET" && path === "/auth/me") {
      const session = await getSession(request, env);

      if (!session) {
        return reply({ error: "Not logged in." }, 401);
      }

      return reply({
        user: {
          id: session.id,
          username: session.username
        }
      });
    }

    // Existing ShabGPT AI endpoint (temporary owner API-key access).
    if (path === "/" && request.method === "POST") {
      if (!env.SHABGPT_API_KEY) {
        return reply({ error: "API key not configured." }, 500);
      }

      if (
        request.headers.get("Authorization") !==
        `Bearer ${env.SHABGPT_API_KEY}`
      ) {
        return reply({ error: "Unauthorized." }, 401);
      }

      try {
        const body = await request.json();

        if (!Array.isArray(body.messages) || !body.messages.length) {
          return reply({ error: "messages is required." }, 400);
        }

        const result = await env.AI.run(MODEL, {
          messages: body.messages,
          max_tokens: Math.min(Number(body.max_tokens) || 512, 1024),
          chat_template_kwargs: {
            enable_thinking: false
          }
        });

        return reply({
          id: result.id || crypto.randomUUID(),
          object: "chat.completion",
          created: result.created || Math.floor(Date.now() / 1000),
          model: result.model || MODEL,
          choices: result.choices || [],
          usage: result.usage || {}
        });

      } catch {
        return reply({ error: "AI request failed." }, 500);
      }
    }

    return reply({ error: "Not found." }, 404);
  }
};
