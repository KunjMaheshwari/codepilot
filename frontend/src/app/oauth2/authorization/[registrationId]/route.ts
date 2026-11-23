import { NextRequest, NextResponse } from "next/server";

const UPSTREAM_TIMEOUT_MS = 12_000;

function resolveBackendOrigin(): string {
  const configured =
    process.env.BACKEND_URL?.trim() ||
    process.env.NEXT_PUBLIC_API_BASE_URL?.trim() ||
    "http://localhost:8080";
  return configured.replace(/\/+$/, "");
}

function wakingUpHtml(retryPath: string): string {
  const escapedPath = retryPath.replace(/"/g, "&quot;");
  return `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <meta http-equiv="refresh" content="5;url=${escapedPath}" />
    <title>CodePilot — Starting Sign-In Service</title>
    <style>
      body { font-family: system-ui, -apple-system, sans-serif; background: #09090b; color: #fafafa; display: grid; place-items: center; min-height: 100vh; margin: 0; padding: 1.5rem; }
      main { max-width: 440px; border: 1px solid #27272a; border-radius: 12px; padding: 2rem; background: #111113; text-align: center; }
      h1 { font-size: 1.125rem; margin: 0 0 0.75rem; }
      p { color: #a1a1aa; font-size: 0.875rem; line-height: 1.5; margin: 0 0 1.25rem; }
      .actions { display: flex; gap: 0.75rem; justify-content: center; flex-wrap: wrap; }
      a { display: inline-block; padding: 0.5rem 1rem; border-radius: 8px; font-size: 0.875rem; text-decoration: none; font-weight: 500; }
      .primary { background: #fafafa; color: #09090b; }
      .secondary { border: 1px solid #27272a; color: #fafafa; }
    </style>
  </head>
  <body>
    <main>
      <h1>Starting CodePilot sign-in service…</h1>
      <p>The backend service is waking up from idle. This page will automatically retry your GitHub sign-in in 5 seconds.</p>
      <div class="actions">
        <a class="primary" href="${escapedPath}">Retry sign-in now</a>
        <a class="secondary" href="/">Back to CodePilot</a>
      </div>
    </main>
  </body>
</html>`;
}

function wakingUpResponse(retryPath: string): NextResponse {
  return new NextResponse(wakingUpHtml(retryPath), {
    status: 503,
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-store, max-age=0",
      "Retry-After": "5",
    },
  });
}

function isRenderHibernationResponse(response: Response): boolean {
  const routing = response.headers.get("x-render-routing") ?? "";
  if (routing.toLowerCase().includes("hibernate")) {
    return true;
  }
  const contentType = response.headers.get("content-type") ?? "";
  return response.status === 503 && contentType.toLowerCase().includes("text/html");
}

export async function GET(
  request: NextRequest,
  context: { params: Promise<{ registrationId: string }> },
) {
  const { registrationId } = await context.params;
  if (!/^[a-zA-Z0-9_-]+$/.test(registrationId)) {
    return NextResponse.json(
      { status: 400, error: "Bad Request", message: "Invalid OAuth registration" },
      { status: 400 },
    );
  }

  const retryPath = `/oauth2/authorization/${encodeURIComponent(registrationId)}${request.nextUrl.search}`;
  const upstreamUrl = `${resolveBackendOrigin()}${retryPath}`;

  const forwardHeaders = new Headers();
  const host = request.headers.get("x-forwarded-host") ?? request.headers.get("host");
  const proto = request.headers.get("x-forwarded-proto") ?? request.nextUrl.protocol.replace(/:$/, "");
  const cookie = request.headers.get("cookie");
  if (host) forwardHeaders.set("x-forwarded-host", host);
  if (proto) forwardHeaders.set("x-forwarded-proto", proto);
  if (cookie) forwardHeaders.set("cookie", cookie);
  forwardHeaders.set("accept", "*/*");

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), UPSTREAM_TIMEOUT_MS);

  try {
    const upstream = await fetch(upstreamUrl, {
      method: "GET",
      headers: forwardHeaders,
      redirect: "manual",
      cache: "no-store",
      signal: controller.signal,
    });

    if (isRenderHibernationResponse(upstream)) {
      return wakingUpResponse(retryPath);
    }

    const location = upstream.headers.get("location");
    if (upstream.status >= 300 && upstream.status < 400 && location) {
      const responseHeaders = new Headers();
      responseHeaders.set("location", location);
      responseHeaders.set("cache-control", "no-store, max-age=0");

      const setCookies =
        typeof upstream.headers.getSetCookie === "function"
          ? upstream.headers.getSetCookie()
          : [];
      for (const value of setCookies) {
        responseHeaders.append("set-cookie", value);
      }

      return new NextResponse(null, {
        status: upstream.status,
        headers: responseHeaders,
      });
    }

    // Any unexpected non-redirect HTML or 5xx from upstream on OAuth initiation
    if (upstream.status >= 500 || (upstream.headers.get("content-type") ?? "").includes("text/html")) {
      return wakingUpResponse(retryPath);
    }

    const body = await upstream.text();
    return new NextResponse(body, {
      status: upstream.status,
      headers: {
        "Content-Type": upstream.headers.get("content-type") ?? "application/json",
        "Cache-Control": "no-store, max-age=0",
      },
    });
  } catch {
    return wakingUpResponse(retryPath);
  } finally {
    clearTimeout(timeout);
  }
}
