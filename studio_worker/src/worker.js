/**
 * PhotographerCamera Studio — Cloudflare Worker 入口。
 *
 * 职责边界（刻意保持极薄）：
 *   - /api/health        : 版本/健康信息（JSON）
 *   - 其余全部请求        : 透传给 Workers Assets（public/ 静态文件）
 *
 * 所有计算密集型工作（WebGL 预览、参数链、3D LUT 采样、.cube 导出、
 * 批量 PNG 渲染）都在浏览器端完成 —— 见 public/lut_pipe.js 与 public/gl_lut.js。
 * 训练（stylefit，依赖 numpy/scipy）不在此运行，请在桌面端 / 本地服务器进行。
 */

const VERSION = "1.0.0";

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    if (url.pathname === "/api/health") {
      return json({
        ok: true,
        service: "photographer-studio",
        version: VERSION,
        mode: "worker",
        note: "rendering is fully client-side; training runs on the desktop app",
      });
    }

    // 静态资源：交给 Workers Assets。找不到时回退到 index.html（单页体验）。
    const res = await env.ASSETS.fetch(request);
    if (res.status === 404 && url.pathname !== "/") {
      const index = await env.ASSETS.fetch(new URL("/", url));
      if (index.status !== 404) return index;
    }

    // 基础安全头
    const out = new Response(res.body, res);
    out.headers.set("X-Content-Type-Options", "nosniff");
    out.headers.set("Referrer-Policy", "same-origin");
    return out;
  },
};

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}
