export async function apiFetch(url: string, init?: RequestInit) {
  const response = await fetch(url, init);
  if (response.status === 401) {
    const next = window.location.pathname + window.location.search;
    window.location.assign(`/login?next=${encodeURIComponent(next)}`);
    throw new Error("登录已过期，请重新登录。");
  }
  if (!response.ok) {
    const result = await response.json().catch(() => null);
    throw new Error(result?.error ?? `请求失败（${response.status}）。`);
  }
  return response;
}

export function loginDestination(value: string | null) {
  if (!value || !/^\/(search|chat|knowledge|plans|examples|assessment|practice|report|graph|achievements|operations)(\/|\?|$)/.test(value)) return "/search";
  return value;
}
