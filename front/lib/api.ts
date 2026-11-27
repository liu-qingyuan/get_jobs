/** Use the page's origin unless the frontend is running on a separate development server. */
export const API_BASE = process.env.NEXT_PUBLIC_API_BASE || ''

/** 拼接后端接口地址，path 以 / 开头，例如 apiUrl('/api/boss/config') */
export function apiUrl(path: string): string {
  return `${API_BASE}${path}`
}
