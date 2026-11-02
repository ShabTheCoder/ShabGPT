
const MODEL = "@cf/google/gemma-4-26b-a4b-it";

export default {
  async fetch(request, env) {
    const headers = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, Authorization"
    };

    const reply = (data, status = 200) =>
      Response.json(data, { status, headers });

    if (request.method === "OPTIONS") {
      return new Response(null, { headers });
    }

    if (request.method === "GET") {
      return reply({
        name: "ShabGPT",
        status: "online"
      });
    }

    if (request.method !== "POST") {
      return reply({ error: "Method not allowed" }, 405);
    }

    if (!env.SHABGPT_API_KEY) {
      return reply({ error: "API key not configured" }, 500);
    }

    if (
      request.headers.get("Authorization") !==
      `Bearer ${env.SHABGPT_API_KEY}`
    ) {
      return reply({ error: "Unauthorized" }, 401);
    }

    try {
      const body = await request.json();

      if (!Array.isArray(body.messages) || !body.messages.length) {
        return reply({ error: "messages is required" }, 400);
      }

      const result = await env.AI.run(MODEL, {
  messages: body.messages,
  max_tokens: Math.min(
    Number(body.max_tokens) || 512,
    1024
  ),
  chat_template_kwargs: {
    enable_thinking: false
  }
});

return reply({
  debug: result
});
      return reply({
        id: crypto.randomUUID(),
        object: "chat.completion",
        model: MODEL,
        choices: [{
          index: 0,
          message: {
            role: "assistant",
            content: result.response ?? ""
          },
          finish_reason: "stop"
        }]
      });

    } catch (error) {
      return reply({
        error: "ShabGPT request failed",
        details: error.message
      }, 500);
    }
  }
};
                           
